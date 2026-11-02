package dev.example.transfer.net

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * 测试专用：把本地 WebSocket 桥接到 TCP（默认连本机 sshd 的 22）。
 *
 * 用途：**把"隧道/Cloudflare/QUIC"这个变量去掉**，单独验证我自己的 WS 字节管道。
 *  - 本地桥 + 我的 SftpClient      → 失败 ⇒ 我的代码有问题
 *  - 本地桥通过、经隧道失败        → 问题在隧道这一跳
 */
class LocalWsTcpBridge(
    private val tcpHost: String = "127.0.0.1",
    private val tcpPort: Int = 22,
    /** 每帧人为加多少毫秒延迟（模拟高 RTT 链路；0 = 不加） */
    private val delayMs: Int = 0,
) : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {

    private val sockets = HashMap<WebSocket, Socket>()
    private val pumps = HashMap<WebSocket, Thread>()
    val receivedBytes = java.util.concurrent.atomic.AtomicLong()
    val sentBytes = java.util.concurrent.atomic.AtomicLong()

    override fun onStart() {
        println("本地桥已启动: ws://127.0.0.1:$port → tcp://$tcpHost:$tcpPort")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        try {
            val tcp = Socket(tcpHost, tcpPort)
            tcp.tcpNoDelay = true
            synchronized(sockets) { sockets[conn] = tcp }
            val t = Thread({
                val buf = ByteArray(64 * 1024)
                try {
                    val input = tcp.getInputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val copy = buf.copyOf(n)
                        sentBytes.addAndGet(n.toLong())
                        delay()
                        conn.send(copy)
                    }
                } catch (e: Exception) {
                    // 连接结束，正常
                } finally {
                    runCatching { conn.close() }
                }
            }, "bridge-tcp-to-ws")
            t.isDaemon = true
            synchronized(pumps) { pumps[conn] = t }
            t.start()
        } catch (e: Exception) {
            println("本地桥：连 TCP 失败 ${e.message}")
            conn.close()
        }
    }

    override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
        val tcp = synchronized(sockets) { sockets[conn] } ?: return
        val n = bytes.remaining()
        if (n == 0) return
        val copy = ByteArray(n)
        bytes.get(copy)
        receivedBytes.addAndGet(n.toLong())
        delay()
        try {
            tcp.getOutputStream().write(copy)
            tcp.getOutputStream().flush()
        } catch (e: Exception) {
            runCatching { conn.close() }
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        onMessage(conn, ByteBuffer.wrap(message.toByteArray(Charsets.ISO_8859_1)))
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        println("本地桥：连接关闭 code=$code remote=$remote 累计 收=${receivedBytes.get()} 发=${sentBytes.get()}")
        cleanup(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        println("本地桥：error ${ex}")
        if (conn != null) cleanup(conn)
    }

    private fun delay() {
        if (delayMs > 0) runCatching { Thread.sleep(delayMs.toLong()) }
    }

    private fun cleanup(conn: WebSocket) {
        val tcp = synchronized(sockets) { sockets.remove(conn) }
        runCatching { tcp?.close() }
        synchronized(pumps) { pumps.remove(conn) }
    }

    fun shutdown() {
        synchronized(sockets) { sockets.values.forEach { runCatching { it.close() } }; sockets.clear() }
        runCatching { stop(1000) }
    }
}
