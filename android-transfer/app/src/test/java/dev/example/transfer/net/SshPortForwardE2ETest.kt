package dev.example.transfer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.CookieManager
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 手机版 DSH 客户端的**传输层**（SSH 本地端口转发）—— 与 App 里跑的是同一份代码：
 * IPv6/22 端口 + 同一把 key + `setPortForwardingL` → 本机 `127.0.0.1:<port>` → 服务器 `127.0.0.1:3080`。
 *
 * 需要环境变量，否则整类跳过：
 *   E2E_KEY / E2E_USER / E2E_HOST / E2E_PORT
 *   E2E_TOKEN（可选）· E2E_REMOTE_FILE（可选，配合 E2E_TOKEN 验证"经转发下载"）
 *
 * 跑法（真 sshd 上）：
 *   E2E_KEY=/tmp/k/id_ed25519 E2E_USER=user E2E_HOST=127.0.0.1 \
 *   E2E_TOKEN=<dsh 的 launch token> E2E_REMOTE_FILE=/home/user/…/x.apk \
 *     ./gradlew testDebugUnitTest --tests '*SshPortForwardE2ETest*'
 */
class SshPortForwardE2ETest {

    private val keyPath: String? = System.getenv("E2E_KEY")
    private val user: String = System.getenv("E2E_USER") ?: "user"
    private val host: String = System.getenv("E2E_HOST") ?: "127.0.0.1"
    private val sshPort: Int = System.getenv("E2E_PORT")?.toIntOrNull() ?: 22
    private val token: String? = System.getenv("E2E_TOKEN")
    private val remoteFile: String? = System.getenv("E2E_REMOTE_FILE")

    private fun requireEnv() {
        assumeTrue("未设 E2E_KEY，跳过", keyPath != null)
        assumeTrue("私钥不存在：$keyPath", File(keyPath!!).isFile)
    }

    private fun openForward(): SshPortForward {
        // localPort = 0 → 让系统挑一个空闲端口（跑测试的机器上可能已经有别的东西占了 3080）
        return SshPortForward(
            user = user, host = host, sshPort = sshPort, privateKeyPath = keyPath!!,
            localPort = 0, remotePort = 3080,
        )
    }

    @Test
    fun port_forward_reaches_dsh_and_self_check_passes() {
        requireEnv()
        openForward().use { fwd ->
            fwd.start()
            println("E2E 端口转发就绪：本机 127.0.0.1:${fwd.boundPort} → 服务器 127.0.0.1:3080")
            println("E2E 自检结果 = ${fwd.selfCheckResult}")
            assertTrue(
                "自检必须拿到 HTTP 响应行（拿不到就说明这把 key 不允许端口转发），实际=${fwd.selfCheckResult}",
                fwd.selfCheckResult.startsWith("HTTP/"),
            )
            assertTrue(
                "没带 cookie 时 DSH 应当回 401，实际=${fwd.selfCheckResult}",
                fwd.selfCheckResult.contains("401"),
            )
        }
    }

    @Test
    fun token_mints_cookie_and_download_works_through_forward() {
        requireEnv()
        assumeTrue("未设 E2E_TOKEN / E2E_REMOTE_FILE，跳过", token != null && remoteFile != null)
        val local = File(remoteFile!!)
        assumeTrue("远端文件不存在：$remoteFile", local.isFile)

        openForward().use { fwd ->
            fwd.start()
            val base = "http://127.0.0.1:${fwd.boundPort}"
            // 用 JDK 自带的 CookieManager 处理 Set-Cookie / 回传 cookie（和浏览器行为一致）
            CookieHandler.setDefault(CookieManager())

            val index = URL("$base/?token=$token").openConnection() as HttpURLConnection
            index.instanceFollowRedirects = false
            assertEquals("token 换 cookie 应当 303", 303, index.responseCode)
            println("E2E token → cookie：HTTP ${index.responseCode}")

            val encoded = URLEncoder.encode(remoteFile, "UTF-8")
            val dl = URL("$base/dsh-download-stream/get?path=$encoded").openConnection() as HttpURLConnection
            dl.setRequestProperty("Range", "bytes=0-65535") // 只取头 64KB：够证明通路，也不浪费
            val code = dl.responseCode
            assertTrue("带 cookie 下载应当 200/206，实际=$code", code == 200 || code == 206)
            val head = dl.inputStream.use { it.readBytes() }
            val magic = ByteArray(4)
            RandomAccessFile(local, "r").use { it.readFully(magic) }
            println("E2E 经转发下载：HTTP $code，取回 ${head.size} 字节，前 4 字节 = ${magic.joinToString(" ") { b -> "%02x".format(b) }}")
            assertTrue("取回的数据不能是空的", head.isNotEmpty())
            assertEquals(
                "取回的前 4 字节必须和远端文件一致（PK… = APK/zip）",
                magic.joinToString(" ") { b -> "%02x".format(b) },
                head.take(4).joinToString(" ") { b -> "%02x".format(b) },
            )
        }
    }
}
