package dev.example.transfer.net

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * SSH 本地端口转发：本机 `127.0.0.1:<localPort>` → （服务器侧）`127.0.0.1:<remotePort>`。
 *
 * 用途：把服务器上只监听 loopback 的 DSH 界面搬到手机本机，再由 WebView 打开
 * （见 ui/dsh/DshActivity）。这样手机上也不走 Cloudflare 隧道 —— 隧道那条路实测
 * 下载 12.5MB 只到 36KB 就断，而这条是 IPv6 上的 SSH，和文件传输同一把 key、同一个 22 端口。
 *
 * 为什么需要 [selfCheck]（不是可有可无的）：
 * `authorized_keys` 里的 `restrict` 隐含 `no-port-forwarding`。用这种 key 时本地端口
 * **照样能绑上**、`session.isConnected` 也**是 true**，但每次连接都被服务器拒绝、
 * 一个字节都拿不到 —— 表现就是"隧道说连上了，页面打不开"，而且完全看不出原因。
 * 所以连上后立刻发一个最小 HTTP 请求，能读到响应行才算真的通。
 */
class SshPortForward(
    private val user: String,
    private val host: String,
    private val sshPort: Int,
    private val privateKeyPath: String,
    private val localPort: Int,
    private val remoteHost: String = "127.0.0.1",
    private val remotePort: Int,
) : Closeable {

    @Volatile
    private var session: Session? = null

    @Volatile
    var boundPort: Int = -1
        private set

    @Volatile
    var selfCheckResult: String = ""
        private set

    /** 建会话 + 建转发 + 自检。任何一步失败都抛带"怎么办"的异常。 */
    fun start() {
        close()
        val jsch = JSch()
        jsch.addIdentity(privateKeyPath)
        val s = jsch.getSession(user, host, sshPort)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey")
        s.setTimeout(READ_TIMEOUT_MS)
        s.setServerAliveInterval(20_000)
        s.setServerAliveCountMax(3)
        try {
            s.connect(CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            throw IllegalStateException("连接/认证失败：${SshErrors.describe(e)}\n（用的私钥：$privateKeyPath）", e)
        }
        boundPort = try {
            s.setPortForwardingL(localPort, remoteHost, remotePort)
        } catch (e: Exception) {
            runCatching { s.disconnect() }
            throw IllegalStateException(
                "本地端口 $localPort 建立转发失败：${SshErrors.describe(e)}\n" +
                    "（端口被占用，或服务器上的 key 不允许端口转发）", e,
            )
        }
        session = s
        try {
            selfCheckResult = selfCheck()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * 自检：从本地端口发一个最小 HTTP 请求，读到响应行才算转发真的通。
     * DSH 未登录时应该回 `HTTP/1.1 401 Unauthorized` —— 那就说明通了。
     */
    fun selfCheck(): String {
        val port = if (boundPort > 0) boundPort else localPort
        val why = "本地端口 $port 能连上，但服务器一个字节都没回 —— 服务器上这把 key 不允许端口转发。\n" +
            "authorized_keys 里的 restrict 隐含 no-port-forwarding。两种改法：\n" +
            " ① 用一把允许转发的 key；\n" +
            " ② 把这把 key 那行的 restrict 换成 " +
            "no-pty,no-agent-forwarding,no-X11-forwarding,no-user-rc，并加上 permitopen=\"$remoteHost:$remotePort\""
        try {
            Socket().use { sock ->
                try {
                    sock.connect(InetSocketAddress("127.0.0.1", port), 5_000)
                } catch (e: Exception) {
                    throw IllegalStateException("本地端口 $port 连不上：${SshErrors.describe(e)}", e)
                }
                sock.soTimeout = 6_000
                val out = sock.getOutputStream()
                out.write(
                    ("GET / HTTP/1.0\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n")
                        .toByteArray(StandardCharsets.UTF_8),
                )
                out.flush()
                val buf = ByteArray(128)
                val n = try {
                    sock.getInputStream().read(buf)
                } catch (e: SocketTimeoutException) {
                    throw IllegalStateException("$why\n（现象：等了 6 秒没有任何响应）", e)
                }
                if (n <= 0) {
                    throw IllegalStateException("$why\n（现象：连接被立刻关闭、一个字节都没回）")
                }
                val first = String(buf, 0, n, StandardCharsets.ISO_8859_1).split("\r\n")[0]
                if (!first.startsWith("HTTP/")) {
                    throw IllegalStateException("转发通了，但回的不是 HTTP 响应：$first")
                }
                return first
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("转发自检失败：${SshErrors.describe(e)}", e)
        }
    }

    val isAlive: Boolean
        get() = session?.isConnected == true

    override fun close() {
        session?.let { s ->
            runCatching { s.delPortForwardingL(localPort) }
            runCatching { s.disconnect() }
        }
        session = null
        boundPort = -1
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
