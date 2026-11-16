package dev.example.journal.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 走 https://transfer.example.com 的 HTTP 通道。
 *
 * 为什么日志 App 也要它：存档原来是"SSH 装进 WebSocket 过 Cloudflare"，而那条路
 * 每个操作等一个来回、≥64KB 的流会被 QUIC 那一跳弄坏；没有 IPv6 时更是只剩它。
 * HTTP 走同一条隧道实测 1.6–2.6 MB/s，而且支持 Range / offset 续传。
 *
 * 和传输助手那份是同一套接口形状，服务端也是同一个（~/dsh-work/trans-http）。
 */
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
        /** 问服务器自己的公网 IPv6（做直连候选，省掉开一条 SSH 会话说）。 */
        fun probeIpv6(baseUrl: String, deviceId: String, token: String, connectTimeoutMs: Int = 8_000): List<String> {
            val c = HttpTransferClient(baseUrl, deviceId, token, connectTimeoutMs)
            val conn = c.open("GET", c.url("/api/hostinfo"))
            val arr = c.readJson(conn, "问服务器 IPv6").optJSONArray("ipv6") ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it) }
        }

        fun open(baseUrl: String, deviceId: String, token: String, connectTimeoutMs: Int = 20_000): HttpTransferClient {
            require(baseUrl.isNotBlank()) { "HTTP 通道地址没填" }
            require(token.isNotBlank()) { "HTTP 通道密钥没填" }
            val c = HttpTransferClient(baseUrl, deviceId, token, connectTimeoutMs)
            val info = c.ping()
            println("HTTP 通道已连通：${c.base}（服务端 ${info.optString("version")}）")
            return c
        }
    }

    // ------------------------------------------------------------------ 底层

    private fun url(path: String, query: Map<String, String?> = emptyMap()): URL {
        val qs = query.entries
            .filter { it.value != null }
            .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        return URL(base + path + if (qs.isEmpty()) "" else "?$qs")
    }

    private fun open(method: String, u: URL): HttpURLConnection =
        (u.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = 60_000
            setRequestProperty("X-Trans-Token", token)
            if (deviceId.isNotBlank()) setRequestProperty("X-Trans-Device", deviceId)
            setRequestProperty("Accept", "application/json, */*")
            useCaches = false
        }

    private fun fail(conn: HttpURLConnection, what: String): Nothing {
        val code = runCatching { conn.responseCode }.getOrDefault(-1)
        val body = runCatching {
            val s = if (code >= 400) conn.errorStream else conn.inputStream
            s?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
        }.getOrDefault("")
        val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("").ifBlank { body.take(300) }
        if (code == 401) throw IOException("这台设备没在服务器登记（401）—— 把「设备 ID / 密钥」改成服务器 `trans-http-device list` 里这台设备那一对")
        if (code == 403) throw IOException("这台设备已被服务器禁用（403）—— 在服务器上重新 enable 或重新登记")
        throw IOException("$what 失败（HTTP $code）：$msg")
    }

    private fun readJson(conn: HttpURLConnection, what: String): JSONObject {
        if (conn.responseCode !in 200..299) fail(conn, what)
        return JSONObject(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
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
            when (conn.responseCode) {
                200 -> JSONObject(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }).optLong("size", 0L)
                404 -> -1L
                else -> {
                    fail(conn, "查大小（$remotePath）")
                    @Suppress("UNREACHABLE_CODE") -1L
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("查远端文件大小失败（$remotePath）：${e.message}", e)
        }
    }

    override fun remoteSizeOrNull(remotePath: String): Long? =
        try {
            remoteSize(remotePath)
        } catch (e: Exception) {
            null
        }

    override fun remoteMtime(remotePath: String): Long {
        val conn = open("GET", url("/api/stat", mapOf("path" to remotePath)))
        return try {
            if (conn.responseCode != 200) 0L
            else JSONObject(conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
                .optLong("mtime", 0L) / 1000
        } catch (e: Exception) {
            0L
        }
    }

    override fun list(remoteDir: String): List<RemoteEntry> {
        val conn = open("GET", url("/api/list", mapOf("path" to remoteDir)))
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
        return out.sortedBy { it.name }
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
        putFile(local, remotePath, 0L, onProgress)

    override fun resumeUpload(
        local: File,
        remotePath: String,
        existing: Long,
        onProgress: (Long, Long) -> Unit,
    ): Long = putFile(local, remotePath, existing, onProgress)

    private fun putFile(local: File, remotePath: String, offset: Long, onProgress: (Long, Long) -> Unit): Long {
        val total = local.length()
        require(total >= offset) { "本地文件比远端还短（$total < $offset），不能续传" }
        val conn = open("PUT", url("/api/put", mapOf("path" to remotePath, "offset" to offset.toString())))
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
            val size = readJson(conn, "上传（$remotePath）").optLong("size", -1L)
            if (size != total) throw IllegalStateException("传完后远端大小 $size ≠ 本地 $total")
            return size
        } catch (e: Exception) {
            alive = false
            throw IllegalStateException("上传 $remotePath 失败：${e.message}", e)
        }
    }

    // ------------------------------------------------------------------ 归档

    override fun triggerArchive(): String {
        val conn = open("POST", url("/api/archive/trigger"))
        val json = readJson(conn, "触发归档")
        return json.optString("message").ifBlank { "QUEUED（HTTP 通道）" }
    }

    override fun diagnostics(where: String) {
        println("HTTP 通道[$where] base=$base alive=$alive")
    }

    override fun close() {
        alive = false
    }
}
