package dev.example.transfer.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 一次下载的结果。contentType 用来识别"下到的其实是网页"（校园网门户劫持）。 */
data class DownloadOutcome(
    val file: File,
    val contentType: String?,
    val bytes: Long,
)

/**
 * 最小可用下载器：标准库 HttpURLConnection，不引入 OkHttp。
 * 走 Dispatchers.IO，通过 onProgress(已下载字节, 总字节) 回报进度（总字节未知时为 -1）。
 */
object Downloader {

    suspend fun download(
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            // 有些服务器/CDN 对没有 UA 的请求直接拒绝
            setRequestProperty("User-Agent", "TransferDemo/1.0")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            check(code in 200..299) { "HTTP $code ${conn.responseMessage}" }

            val total = conn.contentLengthLong
            val contentType = conn.contentType
            dest.parentFile?.mkdirs()

            var written = 0L
            conn.inputStream.use { input ->
                dest.outputStream().buffered().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    var lastPct = -1L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        // 每 256KB 或每变化 1% 回报一次，避免高频刷 UI
                        val pct = if (total > 0) written * 100 / total else -1L
                        if (written - lastReport >= 256 * 1024 || pct != lastPct) {
                            lastReport = written
                            lastPct = pct
                            onProgress(written, total)
                        }
                    }
                    onProgress(written, total)
                }
            }

            // 空响应要当失败：拿到了 200 但零字节，多半不是真下载
            check(written > 0) { "服务器返回 200 但内容为空（0 字节）" }

            DownloadOutcome(dest, contentType, written)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 下到的其实是网页吗？
 *
 * 校园网 / 公共 WiFi 的认证门户经常把所有 HTTP 请求劫持成登录页，**照样返回 200**，
 * 于是"下载成功"却存下来一个 HTML 文件。判定：Content-Type 是 text/html，但 URL 本身不是 .html/.htm。
 *
 * 这个函数原来放在 ui/main 里（界面层），但它是下载器的语义；卡片 ② 下线后挪回 net 包，
 * 逻辑一行没改，单测也照旧跑。
 */
internal fun looksLikeWebPageInsteadOfFile(url: String, contentType: String?): Boolean {
    if (contentType?.lowercase()?.contains("text/html") != true) return false
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return !path.endsWith(".html") && !path.endsWith(".htm")
}
