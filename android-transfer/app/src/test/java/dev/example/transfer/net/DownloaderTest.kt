package dev.example.transfer.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.example.transfer.net.looksLikeWebPageInsteadOfFile
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * 下载器测试：全部跑在 JVM 上，用真实 socket，不需要手机或模拟器。
 *
 * 重点覆盖两类"手机上看起来像下不下来"的情况：
 *  1. 认证门户劫持：HTTP 照样 200，但内容是登录页 HTML
 *  2. 200 但零字节
 */
class DownloaderTest {

    private fun tempDir(): File = Files.createTempDirectory("dl-test").toFile()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    /** 一个"校园网门户"：不管请求什么路径，都回 200 + text/html。返回 (端口, 关闭函数)。 */
    private fun startHijackServer(html: String): Pair<Int, () -> Unit> {
        val ss = ServerSocket(0)
        val worker = thread(isDaemon = true, name = "hijack-server") {
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (e: Exception) {
                    break
                }
                client.use { c ->
                    val input = c.getInputStream()
                    while (true) {
                        val line = readLine(input) ?: break
                        if (line.isEmpty()) break
                    }
                    val body = html.toByteArray(Charsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Content-Length: ").append(body.size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    c.getOutputStream().apply {
                        write(head.toByteArray(Charsets.ISO_8859_1))
                        write(body)
                        flush()
                    }
                }
            }
        }
        return ss.localPort to {
            runCatching { ss.close() }
            worker.interrupt()
        }
    }

    @Test
    fun downloadsRealFileAndReportsProgress() {
        val root = tempDir()
        val payload = "你好, transfer!\n".repeat(2000) // ~多字节内容，验编码与字节数
        File(root, "hello.txt").writeText(payload, Charsets.UTF_8)

        val port = ServerSocket(0).use { it.localPort }
        val server = HttpFileServer(root, port)
        server.start()
        try {
            val dest = File(tempDir(), "out.txt")
            val progress = mutableListOf<Pair<Long, Long>>()
            val outcome = runBlocking {
                Downloader.download("http://127.0.0.1:$port/hello.txt", dest) { done, total ->
                    progress.add(done to total)
                }
            }

            assertEquals("字节数应与磁盘一致", File(root, "hello.txt").length(), outcome.bytes)
            assertEquals(payload, dest.readText(Charsets.UTF_8))
            assertTrue("应报告过进度", progress.isNotEmpty())
            assertEquals("最后一次进度应是完整大小", outcome.bytes, progress.last().first)
            assertTrue("应识别出 text/plain", outcome.contentType!!.startsWith("text/plain"))
            assertFalse("真文件不该被判成网页", looksLikeWebPageInsteadOfFile("http://127.0.0.1:$port/hello.txt", outcome.contentType))
        } finally {
            server.stop()
        }
    }

    @Test
    fun detectsCaptivePortalHtmlMasqueradingAsDownload() {
        val (port, stop) = startHijackServer("<html><body>请先完成校园网认证</body></html>")
        try {
            val dest = File(tempDir(), "portal.txt")
            // 注意：URL 以 .txt 结尾（用户以为在下文件），但服务器回的是 text/html
            val outcome = runBlocking {
                Downloader.download("http://127.0.0.1:$port/ubuntu/dists/noble/Release", dest) { _, _ -> }
            }
            assertTrue("应识别出 text/html", outcome.contentType!!.contains("text/html"))
            assertTrue(
                "这种'下载成功'必须被判成网页劫持，否则用户会以为下不下来",
                looksLikeWebPageInsteadOfFile("http://127.0.0.1:$port/ubuntu/dists/noble/Release", outcome.contentType),
            )
        } finally {
            stop()
        }
    }

    @Test
    fun emptyBodyIsTreatedAsFailure() {
        val (port, stop) = startHijackServer("")
        try {
            val dest = File(tempDir(), "empty.bin")
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    Downloader.download("http://127.0.0.1:$port/whatever", dest) { _, _ -> }
                }
            }
        } finally {
            stop()
        }
    }

    @Test
    fun httpErrorIsReported() {
        val root = tempDir()
        val port = ServerSocket(0).use { it.localPort }
        val server = HttpFileServer(root, port)
        server.start()
        try {
            val dest = File(tempDir(), "nope.txt")
            val e = assertThrows(IllegalStateException::class.java) {
                runBlocking { Downloader.download("http://127.0.0.1:$port/missing.txt", dest) { _, _ -> } }
            }
            assertTrue("错误信息里要带状态码，方便手机上看状态栏判断", e.message!!.contains("404"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun htmlUrlIsNotFlaggedAsHijack() {
        // 用户就是明确要下一个 .html，那 text/html 是正常的，不该误报
        assertFalse(looksLikeWebPageInsteadOfFile("http://x/a.html", "text/html; charset=utf-8"))
        assertFalse(looksLikeWebPageInsteadOfFile("http://x/a.htm?x=1", "text/html"))
        assertFalse(looksLikeWebPageInsteadOfFile("http://x/a.zip", "application/zip"))
        assertTrue(looksLikeWebPageInsteadOfFile("http://x/Release", "text/html"))
        assertTrue(looksLikeWebPageInsteadOfFile("http://x/a.zip", "text/html"))
    }
}
