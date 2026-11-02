package dev.example.transfer.net

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import java.io.File

/**
 * 经 SSH 的 SFTP 客户端。两种传输，共用这一份代码：
 *  - `wsUrl == null`  → 直连 TCP（手机有公网 IPv6 时走这条，路径最短、最快）
 *  - `wsUrl != null`  → 走 Cloudflare WebSocket 隧道（任何网络都能用，兜底）
 *
 * 相比 HTTP 上传的好处：SSH 是长连接流，**不碰 Cloudflare 那个 100MB 单请求体上限**；
 * SFTP 原生支持断点续传、列目录、改名、删除，且两个方向都能走。
 */
class SftpClient private constructor(
    private val session: Session,
    private val channel: ChannelSftp,
    private val wsFactory: WebSocketSshSocketFactory?,
) : java.io.Closeable {

    data class RemoteEntry(val name: String, val size: Long, val dir: Boolean, val modified: Int)

    data class Options(
        val user: String,
        val host: String,
        val port: Int = 22,
        val privateKeyPath: String,
        val passphrase: String? = null,
        /** 非空 = 走隧道，例如 `wss://ssh.example.com/` */
        val wsUrl: String? = null,
        /** TCP 连接 + SSH 握手 的超时。直连探测时应该给短一点，否则 IPv6 黑洞化会干等。 */
        val connectTimeoutMs: Int = 20_000,
        /** 会话读超时（session.timeout）。传输中长时间没有数据的上限。 */
        val readTimeoutMs: Int = 60_000,
        /** 0 = JSch 默认。默认窗口偏小，高 RTT 下吞吐会被"窗口 ÷ RTT"卡死。 */
        val windowSizeMax: Int = 0,
        /** 0 = 默认。上限 258048。 */
        val packetSize: Int = 0,
        /** SFTP 流水线深度（治停等协议在高 RTT 下的折扣）。0 = 默认。 */
        val bulkRequests: Int = 0,
        /**
         * SSH 保活间隔。0 = 关闭。
         *
         * 为什么必须开：真人真事 —— 93MB 的录屏传到一半，服务端日志只写了
         * `session closed`（没有任何 fatal），客户端也没超时；连接是**静默**死的
         * （手机侧移动 IPv6 换了地址/中间设备丢了状态时就会这样）。
         * 没有保活，双方都会一直以为连接还活着，直到下一次操作报 "Pipe closed"，
         * 而那时错误信息已经离真正的原因很远了。
         */
        val serverAliveIntervalMs: Int = 20_000,
        val serverAliveCountMax: Int = 3,
    )

    companion object {

        fun open(options: Options): SftpClient {
            val jsch = JSch()
            if (options.passphrase != null) {
                // 用 byte[] 重载：String 版已过时（避免口令按不同字符集编码）
                jsch.addIdentity(
                    options.privateKeyPath,
                    options.passphrase.toByteArray(Charsets.UTF_8),
                )
            } else {
                jsch.addIdentity(options.privateKeyPath)
            }

            val session = jsch.getSession(options.user, options.host, options.port)
            session.setConfig("StrictHostKeyChecking", "no")
            // 服务端设了 MaxAuthTries 3：只走公钥，别让多方法重试把连接试掉
            session.setConfig("PreferredAuthentications", "publickey")
            val factory = options.wsUrl?.let { url ->
                WebSocketSshSocketFactory(url, options.connectTimeoutMs).also { session.setSocketFactory(it) }
            }
            session.timeout = options.readTimeoutMs
            // 保活：静默半死的连接靠它发现（详见 Options.serverAliveIntervalMs）
            if (options.serverAliveIntervalMs > 0) {
                session.setServerAliveInterval(options.serverAliveIntervalMs)
                session.setServerAliveCountMax(options.serverAliveCountMax)
            }
            session.connect(options.connectTimeoutMs)
            // JSch 的 SO_TIMEOUT 是这么走的（字节码核对 Session.connect(int)）：
            // 握手期按 connectTimeout 设一次，认证成功后再按 session.timeout 覆盖一次。
            // 直连探测特意用 6s 短超时，这里显式再设成"传输用"的超时值，
            // 免得 6 秒这个值跟着会话活到传输阶段去（那会让每次 6 秒抖动都掐断长传）。
            runCatching { session.setTimeout(options.readTimeoutMs) }

            val channel = try {
                session.openChannel("sftp") as ChannelSftp
            } catch (e: Exception) {
                runCatching { session.disconnect() }
                throw e
            }
            // ⚠️ 这两个必须在 connect() 之前设。JSch 自己的报错就是
            //    "local window size max cannot be changed after channel is connected"
            if (options.windowSizeMax > 0) channel.setLocalWindowSizeMax(options.windowSizeMax)
            if (options.packetSize > 0) channel.setLocalPacketSize(options.packetSize)
            if (options.bulkRequests > 0) channel.setBulkRequests(options.bulkRequests)
            channel.connect(options.connectTimeoutMs)

            return SftpClient(session, channel, factory)
        }
    }

    /** 传输中断时把 WebSocket 现场打出来（关闭码/原因/收发字节数）。 */
    fun diagnostics(where: String) = wsFactory?.diagnostics(where) ?: println("WS诊断[$where] 本次是直连 TCP，无 WS 信息")

    /**
     * 会话和通道是否都还活着。
     *
     * 上传前必须问一句：失败后如果继续拿这条缓存连接去用，下一个文件会立刻报
     * "Pipe closed"，而错误信息看起来像目录问题（这就是真机上踩的那次）。
     */
    val isAlive: Boolean
        get() = session.isConnected && channel.isConnected

    /** 上传（覆盖）；返回远端应有的字节数。 */
    fun upload(local: File, remotePath: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
        val m = ProgressMonitor(
            total = local.length(), startOffset = 0,
            jschCountsStartOffset = false, onProgress = onProgress,
        )
        channel.put(local.absolutePath, remotePath, m, ChannelSftp.OVERWRITE)
        return m.absolute
    }

    /**
     * 断点续传：远端已有 [existing] 字节时，JSch 的 RESUME 会自己 stat 远端拿偏移、
     * 自己 skip 本地前 N 字节、再以追加方式写。
     *
     * 两个由字节码确认的行为（`ChannelSftp.put(String,String,monitor,int)`）：
     *  - 本地文件比远端已存在的还短 ⇒ 直接抛 `SftpException(4, "failed to resume for …")`；
     *    一样长 ⇒ 立刻返回，什么都不传。
     *  - `init()` 之后会**先回调一次 `count(existing)`**（把"远端已有大小"当成第一次进度），
     *    所以进度累加时必须把这一次排除掉，否则进度会直接翻倍。
     */
    fun resumeUpload(
        local: File,
        remotePath: String,
        existing: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val m = ProgressMonitor(
            total = local.length(), startOffset = existing,
            jschCountsStartOffset = true, onProgress = onProgress,
        )
        channel.put(local.absolutePath, remotePath, m, ChannelSftp.RESUME)
        return m.absolute
    }

    fun download(remotePath: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long {
        dest.parentFile?.mkdirs()
        val total = remoteSize(remotePath)
        dest.outputStream().buffered().use { out ->
            // 下载用的是默认（覆盖）模式，JSch 不会先报一次起点，所以从 0 起算
            channel.get(
                remotePath,
                out,
                ProgressMonitor(
                    total = total, startOffset = 0,
                    jschCountsStartOffset = false, onProgress = onProgress,
                ),
            )
        }
        return dest.length()
    }

    /**
     * 断点续传下载：远端从 [resumeFrom] 处开始读，追加写到 [dest] 尾部。
     *
     * 依据（`_get` 字节码核对过）：
     *   `offset = (mode == RESUME) ? skip : 0` —— SFTP 的 READ 请求自带 64 位偏移，
     *   所以续传**不会重传前面那段**（这点和"自己 read 丢弃"的土办法有本质区别：
     *   1.9GB/2GB 的进度接着下，不会再把 1.9GB 拉一遍）。
     * [resumeFrom] 必须等于 [dest] 里已有的字节数，否则拼出来的文件是错的。
     */
    fun downloadTo(
        remotePath: String,
        dest: File,
        resumeFrom: Long = 0,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        val total = remoteSize(remotePath)
        if (total < 0) throw IllegalStateException("远端没有这个文件：$remotePath")
        if (resumeFrom >= total) return dest.length() // 已经全了，不用再拉
        dest.parentFile?.mkdirs()
        val m = ProgressMonitor(
            total = total, startOffset = resumeFrom,
            jschCountsStartOffset = false, onProgress = onProgress,
        )
        java.io.FileOutputStream(dest, /* append = */ resumeFrom > 0).buffered(256 * 1024).use { out ->
            channel.get(
                remotePath,
                out,
                m,
                if (resumeFrom > 0) ChannelSftp.RESUME else ChannelSftp.OVERWRITE,
                resumeFrom,
            )
        }
        return dest.length()
    }

    /**
     * 远端文件大小；**确定不存在**才返回 -1，其他错误一律抛。
     *
     * ⚠️ 这里曾经是 `runCatching { channel.stat(p).size }.getOrDefault(-1L)`，
     * 结果把"会话已死"和"文件不存在"混成同一个 -1，后果有二：
     *  1. 界面错报「远程目录不存在且创建失败」（目录一直在）；
     *  2. 续传判断 `existing in 1 until local` 变成 false ⇒ 从头 OVERWRITE 重传一遍，
     *     已经传上去的 93MB 白费。
     * 所以宁可不返回 -1：调用方想容忍"不确定"，用 [remoteSizeOrNull]。
     */
    fun remoteSize(remotePath: String): Long =
        try {
            channel.stat(remotePath).size
        } catch (e: com.jcraft.jsch.SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) -1L
            else throw IllegalStateException("查远端文件大小失败（$remotePath）：${SshErrors.describe(e)}", e)
        } catch (e: Exception) {
            throw IllegalStateException("查远端文件大小失败（$remotePath）：${SshErrors.describe(e)}", e)
        }

    /** "问不到就当不知道"的版本：只用在"顺便看一下"的场景，不要用它做续传判断。 */
    fun remoteSizeOrNull(remotePath: String): Long? =
        try {
            remoteSize(remotePath)
        } catch (e: Exception) {
            null
        }

    fun list(remoteDir: String): List<RemoteEntry> {
        @Suppress("UNCHECKED_CAST")
        val entries = channel.ls(remoteDir) as java.util.Vector<ChannelSftp.LsEntry>
        return entries
            .map { e -> RemoteEntry(e.filename, e.attrs.size, e.attrs.isDir, e.attrs.mTime) }
            .sortedBy { it.name }
    }

    fun exists(remotePath: String): Boolean = remoteSize(remotePath) >= 0

    /**
     * 在**同一条 SSH 会话**上开一个 exec 通道跑 [command]，返回它的标准输出。
     *
     * ⚠️ v2.5 起本工程**没有调用方**了：唯一用它的「存档区触发归档」搬到了日志 App
     * （那边有一份同样的实现）。保留在这里是因为传输层是共用件，删它要连带动 E2E 用例。
     * 服务端的 forced command 会拿
     * `SSH_ORIGINAL_COMMAND` **逐字符**比对，所以 [command] 原样发出 ——
     * 不拼引号、不加 `sh -c`、不补换行（多一个字符就会被拒）。
     *
     * 为什么不新开一条连接：SFTP 通道与 exec 通道是 SSH 在同一条会话上多路复用的两条路，
     * 先 `sftp` 再 `exec` 是协议内的正常用法，还省掉一次握手/选路。但如果这条会话已经死了
     * （服务端只在报文层才发现），这里会抛 `Pipe closed` 之类 —— 由调用方作废连接后重试。
     *
     * 读输出是读到 EOF 为止：服务端收到触发会立刻回一行就结束命令，正常不会等太久；
     * 万一服务端不返回，读操作由 `session.timeout` 兜底，不会无限期挂着。
     */
    fun execOnce(command: String, timeoutMs: Int = 15_000): String {
        val ch = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(command)
        // 命令不接受 stdin，显式置空，免得通道等一个永远不会来的输入
        ch.setInputStream(null)
        // stderr 必须接住：不读的话服务端把那条管道写满就会把命令卡住
        val err = java.io.ByteArrayOutputStream()
        ch.setErrStream(err)
        return try {
            ch.connect(timeoutMs)
            val out = ch.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val exit = ch.exitStatus
            if (out.isEmpty() && exit != 0) {
                // ⚠️ 必须用 String(bytes, charset)：ByteArrayOutputStream.toString(Charset) 是
                // API 33 才有的方法，minSdk 26 上调它会 NoSuchMethodError（lint 抓到的真 bug；
                // 10-15 那次的归档说明里记着"没修"，2026-10-15 本次在两个工程里一起修掉）。
                val detail = String(err.toByteArray(), Charsets.UTF_8).trim().ifBlank { "没有输出" }
                throw IllegalStateException("命令 `$command` 退出码 $exit：$detail")
            }
            out
        } finally {
            runCatching { ch.disconnect() }
        }
    }

    /**
     * 确保远程目录存在；不存在就建（**父目录一起建**）。
     * 返回 true = 本来就存在，false = 这次创建成功。
     *
     * 为什么要逐级：SFTP 的 `mkdir` 不递归。比如日志 App 要把文件送进
     * `/home/user/phone-archive/raw`，若 `phone-archive` 还没建，单层 mkdir 会报
     * "目录不存在，创建也失败"，把"父目录缺一层"说成"路径/权限不对"，排查时会跑偏。
     * 对本来就存在（或只差一层）的目录，这个过程与单层 mkdir 的结果完全一致。
     *
     * ⚠️ 报错必须分清两件事，否则会把人带偏：
     *  - 查目录**失败**（会话死了等）→ 说"查目录失败"，不要把锅甩给目录；
     *  - 查到了确实不存在、创建又失败 → 才是"目录不存在且创建失败"。
     */
    fun ensureDir(remoteDir: String): Boolean {
        val dir = remoteDir.trimEnd('/')
        if (dir.isEmpty()) throw IllegalArgumentException("远程目录不能为空")

        // 从目标往上找到第一个存在的祖先，沿途按"从浅到深"记下要建的层级
        val missing = ArrayDeque<String>()
        var cur = dir
        while (true) {
            val exists = try {
                channel.stat(cur).size >= 0
            } catch (e: com.jcraft.jsch.SftpException) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw IllegalStateException("查远程目录失败（$cur）：${SshErrors.describe(e)}", e)
                }
                false // 这一层真的不存在 → 记下来，继续往上问
            } catch (e: Exception) {
                throw IllegalStateException("查远程目录失败（$cur）：${SshErrors.describe(e)}", e)
            }
            if (exists) break
            missing.addFirst(cur)
            val slash = cur.lastIndexOf('/')
            if (slash <= 0) break // 已经到根（或相对路径的头部）
            cur = cur.substring(0, slash)
        }
        if (missing.isEmpty()) return true

        for (layer in missing) {
            try {
                channel.mkdir(layer)
            } catch (e: Exception) {
                throw IllegalStateException("远程目录 $layer 不存在，创建也失败：${SshErrors.describe(e)}", e)
            }
        }
        return false
    }

    /**
     * 从 InputStream 上传 —— **App 的真实场景**：SAF 内容 URI 只有流，没有本地文件路径。
     * [resumeFrom] > 0 时先跳过本地流前 N 字节，再以 APPEND 模式写远端（等价于续传）。
     * 返回本次实际写入的字节数。
     *
     * ⚠️ 这里走的是 JSch 的 **InputStream 形式**（`put(InputStream, dst, monitor, mode)`）。
     * 早先误用了 **OutputStream 形式**（`put(dst, monitor, mode)` 返回 OutputStream），
     * 实测它是**逐块等 ack 的停等**：模拟 RTT 80ms 时 392 KB/s，而 InputStream 形式 544 KB/s；
     * 真机上差距更大 —— scp 同链路能跑 2.9 MB/s，旧实现只有 35–75 KB/s。
     * 结论：**别再改回 OutputStream 形式。**（对照实验见 SftpE2ETest.measures_upload_forms_under_latency）
     */
    fun uploadStream(
        input: java.io.InputStream,
        remotePath: String,
        totalBytes: Long,
        resumeFrom: Long = 0,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = uploadInputStream(input, remotePath, totalBytes, resumeFrom, onProgress)

    /**
     * 流式上传：JSch 的 `put(InputStream, dst, monitor, mode)` —— 流水线发送，不等逐块 ack。
     *
     * [resumeFrom] > 0 时这里走的是 **APPEND + 自己 skip**（不是 RESUME）。
     * 字节码核对：InputStream 版本的 put 里只有 `init()`、没有那次 `count(offset)`，
     * 所以返回值就是**本次实际写入的字节数**；进度回调里的绝对量由监视器加上起点算出。
     */
    fun uploadInputStream(
        input: java.io.InputStream,
        remotePath: String,
        totalBytes: Long,
        resumeFrom: Long = 0,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long {
        if (resumeFrom > 0) {
            var skipped = 0L
            while (skipped < resumeFrom) {
                val n = input.skip(resumeFrom - skipped)
                if (n <= 0) break
                skipped += n
            }
        }
        val mode = if (resumeFrom > 0) ChannelSftp.APPEND else ChannelSftp.OVERWRITE
        val m = ProgressMonitor(
            total = totalBytes, startOffset = resumeFrom,
            jschCountsStartOffset = false, onProgress = onProgress,
        )
        channel.put(input, remotePath, m, mode)
        return m.written
    }

    /** 读远程文本文件。用它读 `/proc/net/if_inet6` 做公网 IPv6 发现（免 DDNS）。 */
    fun readText(remotePath: String): String =
        channel.get(remotePath).use { it.readBytes().toString(Charsets.UTF_8) }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }

    /**
     * 进度累加器。JSch 的 `SftpProgressMonitor.count(v)` 收到的是**增量**，不是累计值
     * （源码/字节码确认：`offset += count` 与 `monitor.count(count)` 是分开的两句），
     * 所以必须在这里累加 —— 把返回值当总量会错得离谱（实测 3MB 上传返回过 12192）。
     *
     * 另外 `put(...,RESUME)` 在 `init()` 之后会**先报一次 `count(existing)`**
     * （"远端已有大小"）；[jschCountsStartOffset] 就是为了把这一次排除在
     * "本次写入量"之外，否则续传时进度会从起点再叠加一遍、直接冲到 100%。
     */
    private class ProgressMonitor(
        private val total: Long,
        /** 本次 attempt 开始前远端已有的字节数 */
        private val startOffset: Long,
        /** JSch 会不会把 [startOffset] 当成第一次 count 报进来：路径版 RESUME 会，APPEND 不会 */
        private val jschCountsStartOffset: Boolean,
        private val onProgress: (Long, Long) -> Unit,
    ) : SftpProgressMonitor {

        private var firstCallback = true

        /** 本次 attempt 实际写入的字节数（不含续传起点） */
        var written = 0L
            private set

        /** 传完后远端应有的字节数（续传起点 + 本次写入）——给调用方做"传完了没有"的判据 */
        val absolute: Long get() = startOffset + written

        override fun init(op: Int, src: String?, dest: String?, max: Long) {
            written = 0L
            firstCallback = true
            // 起始就报 startOffset：续传时进度条不该先跳回 0
            onProgress(startOffset.coerceAtLeast(0L), total)
        }

        override fun count(count: Long): Boolean {
            if (jschCountsStartOffset && firstCallback) {
                // 这一次是"远端已有大小"，不是本次写入
            } else {
                written += count
            }
            firstCallback = false
            val hi = if (total > startOffset) total else startOffset
            onProgress(absolute.coerceIn(startOffset.coerceAtLeast(0L), hi), total)
            return true
        }

        override fun end() = Unit
    }
}
