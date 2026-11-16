package dev.example.journal.net

import java.io.File

/** 远端目录项。以前挂在 SftpClient 里面，加了 HTTP 通道之后挪到顶层。 */
data class RemoteEntry(
    val name: String,
    val size: Long,
    val dir: Boolean,
    val modified: Int,
)

/**
 * 「服务器上的文件系统 + 归档触发」这个抽象。两条实现：
 *  - [SftpClient]：SSH/SFTP。直连 IPv6 最快，但走 Cloudflare 隧道时每个操作要等一个来回，
 *    ≥64KB 的流还会被 QUIC 那一跳弄坏。
 *  - [HttpTransferClient]：https://transfer.example.com 上的 HTTP 通道（2026-11-13 起本 App 也用它）。
 *
 * 上层（SshRepository / ViewModel）只认这个接口，所以"走哪条路"是连接层的事。
 */
interface RemoteClient : java.io.Closeable {
    /** 界面上给人看的一行，例如「直连 2001:db8::1」「HTTP trans.example.com」。 */
    val label: String

    /** 这条连接还能用吗。缓存连接前必须问一句。 */
    val isAlive: Boolean

    /** 远端文件大小；**确定不存在**才返回 -1，其他错误一律抛。 */
    fun remoteSize(remotePath: String): Long

    fun remoteSizeOrNull(remotePath: String): Long?

    /** 远端 mtime（epoch 秒）；拿不到返回 0。 */
    fun remoteMtime(remotePath: String): Long

    fun exists(remotePath: String): Boolean = remoteSize(remotePath) >= 0

    fun list(remoteDir: String): List<RemoteEntry>

    /** 目录不存在就建（含中间层）。返回 true = 本来就在。 */
    fun ensureDir(remoteDir: String): Boolean

    fun readText(remotePath: String): String

    fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long

    fun resumeUpload(
        local: File,
        remotePath: String,
        existing: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long

    /** 让服务器跑一轮归档，返回它回的那一行（SFTP 走 exec，HTTP 走 /api/archive/trigger）。 */
    fun triggerArchive(): String

    fun diagnostics(where: String)
}
