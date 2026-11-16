package dev.example.transfer.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 走 https://transfer.example.com 的 HTTP 通道（服务端：~/dsh-work/trans-http）。
 *
 * 为什么要有它：没有 IPv6 时，原来只剩「SSH 装进 WebSocket 过 Cloudflare」一条路，
 * 而那条路实测每个操作等一个来回（隧道内 RTT 257–290ms）、≥64KB 还会被 QUIC 那一跳弄坏。
 * HTTP 走同一条隧道：上传 2.4 MB/s、下载 1.6–2.3 MB/s，Range 续传天然支持。
 *
 * 用的 API（都在服务端 server.js 里，改动两边要一起改）：
 *   GET  /api/ping                         探活（open() 里用它确认地址/token 对不对）
 *   GET  /api/stat?path=                   大小/类型；不存在 404
 *   GET  /api/list?path=                   目录项
 *   GET  /api/read?path=                   小文本（≤256KB）
 *   GET  /api/get?path=                    下载；支持 Range: bytes=N-
 *   PUT  /api/put?path=&offset=N           上传（原样 body；offset>0 为续传）
 *   POST /api/mkdir?path=                  建目录
 *
 * 鉴权：请求头 `X-Trans-Token`。token 在服务器的 ~/.config/trans-http/token，
 * 也写在 App 的配置里（[dev.example.transfer.data.SshConfig.httpToken]），可在界面改。
 *
 * 不用 OkHttp：这个工程刻意避免 KMP 多构件的变体解析问题（见 WebSocketSshSocketFactory 的注释），
 * 而 HttpURLConnection 对"流式上传/下载 + 进度 + 断点"已经够用，且零依赖。
 */
/** 搜索结果里的一条（路径是服务器的绝对路径）。 */
data class FindHit(
    val path: String,
    val name: String,
    val dir: Boolean,
    val size: Long,
    val mtime: Long,
)

class HttpTransferClient private constructor(
    baseUrl: String,
    private val deviceId: String,
    private val token: String,
    private val connectTimeoutMs: Int,
) : RemoteClient {

    private val base: String = baseUrl.trim().trimEnd('/')

    @Volatile
    private var alive = true

    override val label: String
        get() = "HTTP ${base.removePrefix("https://").removePrefix("http://")}"

    override val isAlive: Boolean get() = alive

    companion object {
        /** 建连并做一次探活。地址/token 不对就当场抛，和 SftpClient.open 的失败时机一致。 */
        fun open(
            baseUrl: String,
            deviceId: String,
            token: String,
            connectTimeoutMs: Int = 20_000,
        ): HttpTransferClient {
            require(baseUrl.isNotBlank()) { "HTTP 通道地址没填" }
            require(token.isNotBlank()) { "HTTP 通道密钥没填" }
            val c = HttpTransferClient(baseUrl, deviceId, token, connectTimeoutMs)
            val info = c.ping()
            println("HTTP 通道已连通：${c.base}（服务端 ${info.optString("version")}，根 ${info.optString("root")}）")
            return c
        }

        /**
         * 在服务器上按文件名搜（`GET /api/find`，服务端跑 `find -maxdepth`）。
         *
         * 一次性请求，**不依赖当前走的是哪条传输路线**：即使现在用直连 SFTP 传文件，
         * 搜索也走 HTTP（SFTP 那条 key 只允许 sftp 子系统，跑不了 find）。
         */
        fun search(
            baseUrl: String,
            deviceId: String,
            token: String,
            base: String,
            q: String,
            limit: Int = 100,
        ): List<FindHit> {
            val c = HttpTransferClient(baseUrl, deviceId, token, 10_000)
            val conn = c.open(
                "GET",
                c.url("/api/find", mapOf("q" to q, "base" to base, "limit" to limit.toString())),
            )
            val json = c.readJson(conn, "搜索（$q）")
            val arr = json.optJSONArray("hits") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FindHit(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    dir = o.optBoolean("dir", false),
                    size = o.optLong("size", 0L),
                    mtime = o.optLong("mtime", 0L),
                )
            }
        }

        /**
         * 问服务器自己要它的公网 IPv6（`GET /api/hostinfo`），用来做直连候选。
         *
         * 等价于"经隧道 SFTP 读 /proc/net/if_inet6"，但不用为它开一条 SSH 会话
         * （隧道握手实测 ~2s，这里一个请求几十~几百 ms）。拿不到就返回空表，调用方按"没 IPv6"处理。
         */
        fun probeIpv6(
            baseUrl: String,
            deviceId: String,
            token: String,
            connectTimeoutMs: Int = 8_000,
        ): List<String> {
            val c = HttpTransferClient(baseUrl, deviceId, token, connectTimeoutMs)
            val conn = c.open("GET", c.url("/api/hostinfo"))
            val json = c.readJson(conn, "问服务器 IPv6")
            val arr = json.optJSONArray("ipv6") ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it) }
        }
    }

    // ------------------------------------------------------------------ 底层

    // ⚠️ path 一律原样发出（绝对路径 `/home/user/...`），**不要**在这里去掉开头的 `/`：
    //    服务端按"绝对路径 > 相对 /home/user"解析，去掉斜杠会被拼成 /home/user/home/user/...
    //    —— 2026-11-12 就是这么让列目录全挂的。
    private fun url(path: String, query: Map<String, String?> = emptyMap()): URL {
        val qs = query.entries
            .filter { it.value != null }
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val full = base + path + if (qs.isEmpty()) "" else "?$qs"
        return URL(full)
    }

    private fun open(method: String, u: URL): HttpURLConnection =
        (u.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = 60_000
            setRequestProperty("X-Trans-Token", token)
            if (deviceId.isNotBlank()) setRequestProperty("X-Trans-Device", deviceId)
            setRequestProperty("Accept", "application/json, */*")
            instanceFollowRedirects = true
            useCaches = false
        }

    private fun fail(conn: HttpURLConnection, what: String): Nothing {
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val body = runCatching {
            val s = if (code >= 400) conn.errorStream else conn.inputStream
            s?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        }.getOrDefault("")
        val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("").ifBlank { body.take(300) }
        if (code == 401) throw IOException("HTTP 通道 token 不对（401）—— 去界面把 token 改成服务器 ~/.config/trans-http/token 里那串")
        throw IOException("$what 失败（HTTP $code）：$msg")
    }

    private fun readJson(conn: HttpURLConnection, what: String): JSONObject {
        val code = conn.responseCode
        if (code !in 200..299) fail(conn, what)
        val text = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        return JSONObject(text)
    }

    private fun ping(): JSONObject {
        val conn = open("GET", url("/api/ping"))
        return try {
            readJson(conn, "探活")
        } catch (e: Exception) {
            alive = false
            throw e
        }
    }

    // ------------------------------------------------------------------ 读

    override fun remoteSize(remotePath: String): Long {
        val conn = open("GET", url("/api/stat", mapOf("path" to remotePath)))
        return try {
            when (val code = conn.responseCode) {
                200 -> JSONObject(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                    .optLong("size", 0L)
                404 -> -1L
                else -> {
                    fail(conn, "查大小（$remotePath）")
                    @Suppress("UNREACHABLE_CODE") -1L
                }
            }
        } catch (e: Exception) {
            alive = if (e is IOException && e.message?.contains("HTTP 4") == true) alive else false
            throw IllegalStateException("查远端文件大小失败（$remotePath）：${e.message}", e)
        }
    }

    override fun remoteSizeOrNull(remotePath: String): Long? =
        try {
            remoteSize(remotePath)
        } catch (e: Exception) {
            null
        }

    override fun list(remoteDir: String): List<RemoteEntry> =
        when (val r = listWithEtag(remoteDir, null)) {
            is DirList.Changed -> r.entries
            DirList.Unchanged -> emptyList() // 不带 etag 时服务端不会回 304，这里只是兜底
        }

    override fun listWithEtag(remoteDir: String, etag: String?): DirList {
        val conn = open("GET", url("/api/list", mapOf("path" to remoteDir)))
        if (!etag.isNullOrBlank()) conn.setRequestProperty("If-None-Match", etag)
        if (conn.responseCode == 304) return DirList.Unchanged
        val json = readJson(conn, "列目录（$remoteDir）")
        val arr: JSONArray = json.optJSONArray("entries") ?: JSONArray()
        val out = ArrayList<RemoteEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += RemoteEntry(
                name = o.optString("name"),
                size = o.optLong("size", 0L),
                dir = o.optBoolean("dir", false),
                modified = (o.optLong("mtime", 0L) / 1000).toInt(),
            )
        }
        val newEtag = conn.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
            ?: json.optString("etag").takeIf { it.isNotBlank() }
        return DirList.Changed(out.sortedBy { it.name }, newEtag)
    }

    override fun ensureDir(remoteDir: String): Boolean {
        val conn = open("POST", url("/api/mkdir", mapOf("path" to remoteDir)))
        readJson(conn, "建目录（$remoteDir）")
        return true
    }

    override fun readText(remotePath: String): String {
        val conn = open("GET", url("/api/read", mapOf("path" to remotePath)))
        if (conn.responseCode !in 200..299) fail(conn, "读文本（$remotePath）")
        return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    // ------------------------------------------------------------------ 写

    override fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit): Long =
        putFile(local, remotePath, offset = 0L, onProgress = onProgress)

    override fun resumeUpload(
        local: File,
        remotePath: String,
        existing: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long = putFile(local, remotePath, offset = existing, onProgress = onProgress)

    private fun putFile(local: File, remotePath: String, offset: Long, onProgress: (Long, Long) -> Unit): Long {
        val total = local.length()
        require(total >= offset) { "本地文件比远端还短（$total < $offset），不能续传" }
        val conn = open(
            "PUT",
            url("/api/put", mapOf("path" to remotePath, "offset" to offset.toString())),
        )
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(total - offset)
        try {
            BufferedInputStream(local.inputStream(), 256 * 1024).use { input ->
                var skipped = 0L
                while (skipped < offset) {
                    val n = input.skip(offset - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                BufferedOutputStream(conn.outputStream, 256 * 1024).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = offset
                    onProgress(done, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            val json = readJson(conn, "上传（$remotePath）")
            val size = json.optLong("size", -1L)
            if (size != total) throw IllegalStateException("传完后远端大小 $size ≠ 本地 $total")
            return size
        } catch (e: Exception) {
            alive = false
            throw IllegalStateException("上传 $remotePath 失败：${e.message}", e)
        }
    }

    // ------------------------------------------------------------------ 下载

    override fun downloadTo(
        remotePath: String,
        dest: File,
        resumeFrom: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val total = remoteSize(remotePath)
        if (total < 0) throw IllegalStateException("远端没有这个文件：$remotePath")
        if (resumeFrom >= total) return dest.length()

        val conn = open("GET", url("/api/get", mapOf("path" to remotePath)))
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        val code = conn.responseCode
        if (code != 200 && code != 206) fail(conn, "下载（$remotePath）")

        dest.parentFile?.mkdirs()
        var done = if (code == 206) resumeFrom else 0L
        try {
            BufferedInputStream(conn.inputStream, 256 * 1024).use { input ->
                java.io.FileOutputStream(dest, /* append = */ code == 206).buffered(256 * 1024).use { out ->
                    val buf = ByteArray(256 * 1024)
                    onProgress(done, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        } catch (e: Exception) {
            alive = false
            throw IllegalStateException("下载 $remotePath 失败：${e.message}", e)
        }
        if (dest.length() != total) {
            throw IllegalStateException("下载后本地 ${dest.length()} 字节 ≠ 远端 $total 字节")
        }
        return dest.length()
    }

    override fun diagnostics(where: String) {
        println("HTTP 通道[$where] base=$base alive=$alive（HTTP 没有长连接可诊断，看服务端 journalctl --user -u trans-http）")
    }

    override fun close() {
        // HttpURLConnection 每次请求都是独立连接，没有需要主动关闭的会话
        alive = false
    }
}
