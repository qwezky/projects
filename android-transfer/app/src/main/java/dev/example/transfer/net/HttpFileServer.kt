package dev.example.transfer.net

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简只读 HTTP 文件服务：把 root 目录暴露给同一局域网的浏览器下载。
 *
 * 刻意只用 java.net，不引入 NanoHTTPD/Ktor：
 *  - 只实现 GET / HEAD
 *  - 只读，不提供上传（上传要处理 multipart，个人自用没必要）
 *  - 不做鉴权（局域网内自用；要暴露到公网请自行加 token 校验）
 */
class HttpFileServer(
    private val root: File,
    private val port: Int,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "transfer-http-worker").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()

    /** 端口被占用等错误会抛出 IOException，调用方负责提示。 */
    fun start() {
        if (running.getAndSet(true)) return
        root.mkdirs()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port), 16)
        serverSocket = ss
        acceptThread = Thread {
            while (running.get() && !ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept 失败", e)
                    break
                }
                pool.execute { handleQuietly(client) }
            }
        }.apply {
            isDaemon = true
            name = "transfer-http-accept"
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    // ---------------------------------------------------------------- 内部实现

    private fun handleQuietly(client: Socket) {
        try {
            handle(client)
        } catch (e: Exception) {
            Log.w(TAG, "请求处理失败", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val out = BufferedOutputStream(client.getOutputStream())
        try {
            val requestLine = readLine(client.getInputStream()) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondText(out, 400, "Bad Request", "请求格式错误")
                return
            }
            val method = parts[0].uppercase(Locale.ROOT)
            val rawTarget = parts[1]

            // 丢掉请求头（本服务不解析它们）
            while (true) {
                val line = readLine(client.getInputStream()) ?: break
                if (line.isEmpty()) break
            }

            if (method != "GET" && method != "HEAD") {
                respondText(out, 405, "Method Not Allowed", "只支持 GET/HEAD", headOnly = method == "HEAD")
                return
            }

            val path = URLDecoder.decode(rawTarget.substringBefore('?').substringBefore('#'), "UTF-8")
            val relative = path.trimStart('/')
            val headOnly = method == "HEAD"

            if (relative.isEmpty()) {
                respondIndex(out, headOnly)
            } else {
                respondFile(out, relative, headOnly)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle 失败", e)
            runCatching { respondText(out, 500, "Internal Server Error", "服务内部错误") }
        } finally {
            runCatching { out.flush() }
        }
    }

    private fun respondIndex(out: OutputStream, headOnly: Boolean) {
        val all = root.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val onlyFiles = all.filter { it.isFile }
        val rows = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (f in onlyFiles) {
            rows.append("<li><a href=\"/").append(encodePath(f.name)).append("\">")
                .append(escapeHtml(f.name)).append("</a> <small>")
                .append(humanSize(f.length())).append(" · ").append(df.format(Date(f.lastModified())))
                .append("</small></li>\n")
        }
        val html = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>传输助手 · 文件列表</title>
            <style>
              body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:24px;line-height:1.6}
              h1{font-size:20px} ul{list-style:none;padding:0}
              li{padding:10px 0;border-bottom:1px solid #eee}
              small{color:#666} a{color:#1b5e20;text-decoration:none;word-break:break-all}
            </style></head><body>
            <h1>传输助手 · 文件列表</h1>
            <p><small>共 ${onlyFiles.size} 个文件，点文件名即可下载。</small></p>
            <ul>
            $rows
            </ul>
            </body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        writeResponse(out, 200, "OK", "text/html; charset=utf-8", html.size.toLong(), headOnly) {
            it.write(html)
        }
    }

    private fun respondFile(out: OutputStream, relative: String, headOnly: Boolean) {
        val rootCanonical = root.canonicalFile
        val target = File(root, relative).canonicalFile
        // 防目录穿越：解析后的路径必须仍在 root 之内
        if (!target.path.startsWith(rootCanonical.path + File.separator) || !target.isFile) {
            respondText(out, 404, "Not Found", "文件不存在", headOnly)
            return
        }
        val extra = mapOf(
            "Content-Disposition" to "attachment; filename*=UTF-8''${URLEncoder.encode(target.name, "UTF-8")}",
            "Last-Modified" to httpDate(target.lastModified()),
        )
        writeResponse(out, 200, "OK", guessMime(target.name), target.length(), headOnly, extra) {
            target.inputStream().use { input -> input.copyTo(it, 64 * 1024) }
        }
    }

    private fun respondText(
        out: OutputStream,
        status: Int,
        reason: String,
        message: String,
        headOnly: Boolean = false,
    ) {
        val body = message.toByteArray(Charsets.UTF_8)
        writeResponse(out, status, reason, "text/plain; charset=utf-8", body.size.toLong(), headOnly) {
            it.write(body)
        }
    }

    private inline fun writeResponse(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        length: Long,
        headOnly: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
        body: (OutputStream) -> Unit,
    ) {
        val headers = StringBuilder()
        headers.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        headers.append("Content-Type: ").append(contentType).append("\r\n")
        headers.append("Content-Length: ").append(length).append("\r\n")
        for ((k, v) in extraHeaders) headers.append(k).append(": ").append(v).append("\r\n")
        headers.append("Connection: close\r\n\r\n")
        out.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) body(out)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8 * 1024) return sb.toString()
        }
    }

    companion object {
        private const val TAG = "HttpFileServer"

        private val MIME = mapOf(
            "txt" to "text/plain; charset=utf-8",
            "log" to "text/plain; charset=utf-8",
            "json" to "application/json",
            "xml" to "application/xml",
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css",
            "js" to "application/javascript",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "svg" to "image/svg+xml",
            "pdf" to "application/pdf",
            "zip" to "application/zip",
            "gz" to "application/gzip",
            "tar" to "application/x-tar",
            "7z" to "application/x-7z-compressed",
            "apk" to "application/vnd.android.package-archive",
            "mp3" to "audio/mpeg",
            "mp4" to "video/mp4",
            "mkv" to "video/x-matroska",
        )

        fun guessMime(name: String): String =
            MIME[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"

        fun humanSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1L shl 20))
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / (1L shl 10))
            else -> "$bytes B"
        }

        private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

        private fun encodePath(name: String): String =
            URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        private fun httpDate(epochMillis: Long): String =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .also { it.timeZone = TimeZone.getTimeZone("GMT") }
                .format(Date(epochMillis))
    }
}
