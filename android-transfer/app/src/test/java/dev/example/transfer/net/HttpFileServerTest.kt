package dev.example.transfer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files

/**
 * 在普通 JVM 上开真实 socket 跑一遍 HttpFileServer —— 不需要模拟器或真机。
 *
 * android.util.Log 在单元测试里是桩，靠 app/build.gradle.kts 里
 * testOptions.unitTests.isReturnDefaultValues = true 让它返回默认值。
 */
class HttpFileServerTest {

    private fun newRoot(): File = Files.createTempDirectory("httpfs-test").toFile()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private data class Resp(val code: Int, val body: String, val contentLength: Long, val contentType: String?)

    private fun request(url: String, method: String = "GET"): Resp {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 3000
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Resp(code, body, conn.contentLengthLong, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }

    private fun withServer(root: File, block: (base: String) -> Unit) {
        val port = freePort()
        val server = HttpFileServer(root, port)
        server.start()
        try {
            block("http://127.0.0.1:$port")
        } finally {
            server.stop()
        }
    }

    @Test
    fun servesIndexListingAndDownloadsFile() {
        val root = newRoot()
        File(root, "hello.txt").writeText("你好, transfer!")

        withServer(root) { base ->
            val index = request("$base/")
            assertEquals(200, index.code)
            assertTrue("列表页应包含文件名", index.body.contains("hello.txt"))

            val file = request("$base/hello.txt")
            assertEquals(200, file.code)
            assertEquals("你好, transfer!", file.body)
            assertTrue("应声明为附件下载", file.contentType!!.startsWith("text/plain"))
        }
    }

    @Test
    fun headReturnsLengthWithoutBody() {
        val root = newRoot()
        File(root, "data.bin").writeBytes(ByteArray(2048) { 7 })

        withServer(root) { base ->
            val resp = request("$base/data.bin", method = "HEAD")
            assertEquals(200, resp.code)
            assertEquals(2048L, resp.contentLength)
            assertEquals("HEAD 不应返回正文", "", resp.body)
        }
    }

    @Test
    fun missingFileReturns404() {
        val root = newRoot()
        withServer(root) { base ->
            assertEquals(404, request("$base/nope.txt").code)
        }
    }

    @Test
    fun rejectsPathTraversal() {
        val parent = newRoot()
        val root = File(parent, "served").apply { mkdirs() }
        File(parent, "secret.txt").writeText("不该被读到")

        withServer(root) { base ->
            // %2e%2e 让请求在 HTTP 层不被规范化，由服务端 decode 后再判断
            val resp = request("$base/%2e%2e/secret.txt")
            assertEquals("目录穿越必须被挡住", 404, resp.code)
            assertTrue("不能泄露父目录内容", !resp.body.contains("不该被读到"))
        }
    }

    @Test
    fun unsupportedMethodReturns405() {
        val root = newRoot()
        File(root, "a.txt").writeText("x")
        withServer(root) { base ->
            assertEquals(405, request("$base/a.txt", method = "POST").code)
        }
    }

    @Test
    fun guessesMimeAndHumanSize() {
        assertEquals("image/png", HttpFileServer.guessMime("a.PNG"))
        assertEquals("application/octet-stream", HttpFileServer.guessMime("noext"))
        assertEquals("2.0 KB", HttpFileServer.humanSize(2048))
        assertEquals("512 B", HttpFileServer.humanSize(512))
    }
}
