package dev.example.transfer.net

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext

/**
 * 把 WebSocket 当成一条**字节管道**用。
 *
 * 为什么需要它：Cloudflare 隧道的 `ssh://` 入口只吃 WebSocket 升级，
 * 所以手机要在 App 里连上 sshd，必须自己把字节搬进 WS 帧里。
 * （已实测：裸 WS 升级 → 101，然后发一帧就收到 `SSH-2.0-OpenSSH_9.6p1 ...`）
 *
 * 刻意用 Java-WebSocket 而不是 OkHttp：OkHttp 5.x 是 KMP 多构件，
 * JVM 单元测试与 Android 会解析到**不同变体**，那样"JVM 端到端验证"就失去意义。
 *
 * 两个踩过的坑（都靠诊断量定位，别删）：
 *  1. **零长度消息不能当 EOF**：对端发一个空帧就会让 JSch 的
 *     `ChannelSftp.fill()` 看到 `read()<=0` 而抛 "inputstream is closed"。
 *  2. 关闭原因必须留证：否则批量传输中断时只能看到 JSch 那句含糊的包装异常。
 */
internal class SshWebSocketClient(uri: URI) : WebSocketClient(uri) {

    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val eof = AtomicBoolean(false)

    @Volatile
    private var closeInfo: String? = null

    @Volatile
    private var errorInfo: String? = null

    private val receivedMessages = AtomicLong()
    private val receivedBytes = AtomicLong()
    private val sentBytes = AtomicLong()

    private var current: ByteArray = EMPTY
    private var currentOffset = 0

    fun awaitOpen(timeoutMs: Int): Boolean {
        if (uri.scheme.equals("wss", ignoreCase = true)) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            setSocketFactory(ctx.socketFactory)
        }
        setConnectionLostTimeout(KEEPALIVE_SECONDS)
        setTcpNoDelay(true)
        return connectBlocking(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    }

    override fun onOpen(handshake: ServerHandshake) = Unit

    override fun onMessage(bytes: ByteBuffer) {
        val n = bytes.remaining()
        if (n == 0) return // 空帧直接忽略，绝不能当 EOF
        val copy = ByteArray(n)
        bytes.get(copy)
        receivedMessages.incrementAndGet()
        receivedBytes.addAndGet(n.toLong())
        chunks.put(copy)
    }

    override fun onMessage(message: String) {
        if (message.isEmpty()) return
        val b = message.toByteArray(Charsets.ISO_8859_1)
        receivedMessages.incrementAndGet()
        receivedBytes.addAndGet(b.size.toLong())
        chunks.put(b)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        closeInfo = "WS 关闭 code=$code reason=${reason ?: ""} 由对端发起=$remote"
        diagnostics("onClose")
        finish()
    }

    override fun onError(ex: Exception) {
        errorInfo = ex.toString()
        diagnostics("onError")
        finish()
    }

    /** 中断时把现场打到标准输出 —— 测试报告里能直接看到，比 JSch 那句包装异常有用得多。 */
    fun diagnostics(where: String) {
        println(
            "WS诊断[$where] 收到消息=${receivedMessages.get()} 收到字节=${receivedBytes.get()} " +
                "已发字节=${sentBytes.get()} 队列剩=${chunks.size} " +
                "close=${closeInfo ?: "-"} error=${errorInfo ?: "-"}"
        )
    }

    private fun finish() {
        if (eof.compareAndSet(false, true)) {
            chunks.put(SENTINEL) // 唤醒阻塞中的 read()；真实数据帧永不为空
        }
    }

    private fun eofDetail(): String {
        val parts = mutableListOf<String>()
        errorInfo?.let { parts += "error=$it" }
        closeInfo?.let { parts += it }
        if (parts.isEmpty()) parts += "对端未给出原因（可能仍连着但流已结束）"
        return parts.joinToString("；")
    }

    fun inputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (currentOffset < current.size) {
                    val n = minOf(len, current.size - currentOffset)
                    System.arraycopy(current, currentOffset, b, off, n)
                    currentOffset += n
                    return n
                }
                val next = try {
                    chunks.take()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                if (next === SENTINEL) {
                    if (eof.get()) return -1
                    continue
                }
                current = next
                currentOffset = 0
            }
        }

        override fun available(): Int = current.size - currentOffset + chunks.sumOf { it.size }

        override fun close() {
            runCatching { this@SshWebSocketClient.close() }
            finish()
        }
    }

    fun outputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (eof.get()) throw IOException("WebSocket 已结束（${eofDetail()}）")
            // send() 会保留引用，必须交出独占的数组
            val payload = if (off == 0 && len == b.size) b.copyOf() else b.copyOfRange(off, off + len)
            try {
                // 已用 javap 核实：Java-WebSocket 的 send(byte[]) 返回 void，失败靠抛异常
                send(payload)
                sentBytes.addAndGet(len.toLong())
            } catch (e: Exception) {
                throw IOException("WebSocket 发送失败：${e.message}", e)
            }
        }

        override fun close() {
            runCatching { this@SshWebSocketClient.close() }
            finish()
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private val SENTINEL = ByteArray(0)
        private const val KEEPALIVE_SECONDS = 20
    }
}

/**
 * JSch 的 SocketFactory 只有三个方法（已 javap 核实），
 * 所以不需要继承 java.net.Socket 去覆盖一堆方法 —— 用一个未连接的空壳当载体即可，
 * 真正读写的流由我们返回。
 */
class WebSocketSshSocketFactory(
    private val wsUrl: String,
    private val connectTimeoutMs: Int,
) : com.jcraft.jsch.SocketFactory {

    private var client: SshWebSocketClient? = null

    /** 出问题时把 WS 现场打出来（给测试/日志用）。 */
    fun diagnostics(where: String) = client?.diagnostics(where) ?: println("WS诊断[$where] 尚未创建连接")

    override fun createSocket(host: String, port: Int): Socket {
        val c = SshWebSocketClient(URI(wsUrl))
        val ok = try {
            c.awaitOpen(connectTimeoutMs)
        } catch (e: Exception) {
            throw IOException("WebSocket 握手失败（${wsUrl}）：${e.message}", e)
        }
        if (!ok) throw IOException("WebSocket 握手超时（${wsUrl}，${connectTimeoutMs}ms）")
        client = c
        return Socket()
    }

    override fun getInputStream(socket: Socket): InputStream =
        (client ?: throw IOException("先调 createSocket")).inputStream()

    override fun getOutputStream(socket: Socket): OutputStream =
        (client ?: throw IOException("先调 createSocket")).outputStream()
}
