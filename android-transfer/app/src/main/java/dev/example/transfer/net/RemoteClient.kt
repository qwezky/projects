package dev.example.transfer.net

import java.io.File

/** 列目录的结果：变了给条目 + 新的版本号（etag），没变就只有 [Unchanged]（服务端回 304、没有 body）。 */
sealed interface DirList {
    data class Changed(val entries: List<RemoteEntry>, val etag: String?) : DirList
    data object Unchanged : DirList
}

/** 远端目录项。以前挂在 SftpClient 里面，加了 HTTP 通道之后挪到顶层，两条通道共用。 */
data class RemoteEntry(
    val name: String,
    val size: Long,
    val dir: Boolean,
    val modified: Int,
)

/**
 * 「服务器上的文件系统」这个抽象。两条实现：
 *  - [SftpClient]：SSH/SFTP。直连 IPv6 最快（实测调参后 67 MB/s），但走 Cloudflare 隧道时
 *    每个操作要等一个来回、≥64KB 还会被 QUIC 那一跳弄坏。
 *  - [HttpTransferClient]：https://transfer.example.com 上的 HTTP 通道。没 IPv6 也能用，
 *    实测同一条隧道下上传 2.4 MB/s、下载 1.6–2.3 MB/s，支持 Range 续传。
 *
 * 上层（SshUploadRepository / ViewModel）只认这个接口，所以"走哪条路"是连接层的事，
 * 上传/下载/列目录/断点续传的代码一条都不用分叉。
 */
interface RemoteClient : java.io.Closeable {
    /** 给界面看的一行说明，例如「直连 2001:db8::1」「HTTP trans.example.com」。 */
    val label: String

    /** 这条连接还能用吗。缓存连接前必须问一句，否则拿死连接去撞会报出方向错误的异常。 */
    val isAlive: Boolean

    /** 远端文件大小；**确定不存在**才返回 -1，其他错误一律抛。 */
    fun remoteSize(remotePath: String): Long

    /** "问不到就当不知道"的版本，只用于"顺便看一眼"的场景。 */
    fun remoteSizeOrNull(remotePath: String): Long?

    fun exists(remotePath: String): Boolean = remoteSize(remotePath) >= 0

    fun list(remoteDir: String): List<RemoteEntry>

    /**
     * 带版本号问一次目录：服务端没变就回 [DirList.Unchanged]（HTTP 侧是真的 304，不传 body）。
     *
     * 默认实现（SFTP 没有目录版本号这个概念）总是返回 Changed —— 上层照样能用，
     * 只是"只读变更"这一层优化只对 HTTP 通道生效。
     */
    fun listWithEtag(remoteDir: String, etag: String?): DirList =
        DirList.Changed(list(remoteDir), null)

    /** 目录不存在就建（含中间层）。返回 true = 本来就在。 */
    fun ensureDir(remoteDir: String): Boolean

    fun readText(remotePath: String): String

    /** 整文件覆盖上传；返回远端应有的字节数。 */
    fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long

    /** 断点续传：远端已有 [existing] 字节时从那里接着写。 */
    fun resumeUpload(
        local: File,
        remotePath: String,
        existing: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long

    /** 断点续传下载：远端从 [resumeFrom] 处开始读，追加写到 [dest] 尾部。 */
    fun downloadTo(
        remotePath: String,
        dest: File,
        resumeFrom: Long = 0,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long

    /** 出问题时把连接层现场打出来（给日志/测试用）。 */
    fun diagnostics(where: String)
}
