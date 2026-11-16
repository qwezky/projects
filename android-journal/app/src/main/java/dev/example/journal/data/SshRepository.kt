package dev.example.journal.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.example.journal.net.HttpTransferClient
import dev.example.journal.net.RemoteClient
import dev.example.journal.net.ServerAddressDiscovery
import dev.example.journal.net.SftpClient
import dev.example.journal.net.SshErrors
import dev.example.journal.net.SshKeyManager
import java.io.BufferedInputStream
import java.io.File
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** 一次上传的文件（来自 SAF 内容 URI）。 */
data class UploadItem(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    /** SAF 给的 MIME（可能就是 null/octet-stream，所以存档白名单还要看扩展名兜底） */
    val mime: String? = null,
)

/** 连接结果：走了哪条路、发现了什么地址、耗时多少。 */
data class SshConnection(
    val client: RemoteClient,
    val route: SshRoute,
    val host: String,
    val discoveredIpv6: List<String>,
    val elapsedMs: Long,
) {
    val viaDirect: Boolean get() = route == SshRoute.DIRECT

    val label: String
        get() = when (route) {
            SshRoute.DIRECT -> "直连 $host"
            SshRoute.HTTP -> "HTTP ${host.removePrefix("https://").removePrefix("http://")}"
            SshRoute.TUNNEL -> "经 SSH 隧道（$host）"
            SshRoute.AUTO -> host
        }
}

/** 一次上传的最终结果。 */
data class UploadOutcome(
    val remotePath: String,
    /** 传完后远端的字节数 */
    val bytes: Long,
    /** 本地暂存副本的 sha256 —— 在服务器上 `sha256sum` 同一个文件即可确认逐字节一致 */
    val sha256: String,
    /** 用了几轮（>1 = 中间断过、靠断点续传接上） */
    val attempts: Int,
    /** 第一轮开始时远端已有多少字节（>0 = 是接着上次传的） */
    val resumedFrom: Long,
    /** 远端本来就有同长度文件，这一轮没重传 */
    val skippedAlreadyComplete: Boolean,
    val label: String,
    val directHost: String?,
    val viaTunnel: Boolean,
)

/** 读回来的一个远端文本文件（待办索引就是用它读的）。 */
data class RemoteText(
    val text: String,
    /** 远端文件的 mtime（秒）；拿不到是 0 */
    val modifiedEpochSeconds: Long,
    val bytes: Long,
    val label: String,
)

/**
 * 存档区的触发命令。服务端把它和 `SSH_ORIGINAL_COMMAND` **逐字符**比对，
 * 多一个空格/引号/换行都会被拒 —— 所以全工程只认这一个常量，谁都不要就地拼字符串。
 */
internal const val ARCHIVE_TRIGGER_COMMAND = "archive-trigger"

/**
 * SSH/SFTP 数据层：**上传存档 + 触发归档 + 读待办索引**。
 *
 * 设计要点（都是从传输助手那套实测结论里继承的，不是重新猜的）：
 *  1. 密钥用 RSA-4096：ed25519 在 Android 上不可用（jsch 的 Multi-Release jar 里
 *     JCE EdDSA 在 versions/15，基础包直接抛 "requires Java15+"）。
 *  2. 连接顺序：手填地址 / 上次成功地址 → 隧道（顺便读 /proc/net/if_inet6 发现地址）→
 *     用发现的地址直连 → 都失败才退回隧道。
 *  3. **连接是被缓存的，所以必须会被作废**：一条静默死掉的连接如果继续用，
 *     下一个动作会立刻报 `Pipe closed`（看起来像目录问题）。
 *  4. **上传是可续传、可重试的**：手机移动网这条链路会中途断。断了就重连、
 *     从远端已有字节接着传，最多 [MAX_ATTEMPTS] 轮；远端那半截**绝不删除**。
 */
class SshRepository(private val context: Context) {

    private companion object {
        /** 经隧道 / 正式建会话的超时 */
        const val CONNECT_TIMEOUT_MS = 20_000

        /** 直连探测用短超时：避免 IPv6 黑洞化时退回 HTTP 前干等太久（6s 太长，3s 够判断） */
        const val PROBE_CONNECT_TIMEOUT_MS = 3_000

        /** 会话读超时（session.timeout）。传输中长时间没有数据才算死 */
        const val READ_TIMEOUT_MS = 60_000

        /** 一个文件最多传几轮（含第一轮） */
        const val MAX_ATTEMPTS = 4
    }

    private val keyFile: File get() = File(context.filesDir, "ssh/id_rsa")

    // ---------------------------------------------------------------- 密钥

    fun hasKey(): Boolean = keyFile.isFile && keyFile.length() > 0

    /** 首次使用时生成 RSA-4096；已存在则直接用。 */
    fun ensureKey(): File {
        if (hasKey()) return keyFile
        val jsch = JSch()
        val generated = SshKeyManager.generateRsa4096(
            jsch = jsch,
            privateKeyFile = keyFile,
            passphrase = null,
            comment = "journal-app",
            options = null, // 公钥行在 authorizedKeysLine() 里带选项拼
        )
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
        keyFile.setWritable(false, false)
        keyFile.setWritable(true, true)
        return generated.privateKeyFile
    }

    /** 从私钥推导出要粘到服务器 `~/.ssh/authorized_keys` 的那一行（带收窄选项）。 */
    fun authorizedKeysLine(config: SshConfig): String {
        val f = ensureKey()
        val jsch = JSch()
        val kp = KeyPair.load(jsch, f.absolutePath)
        val blob: ByteArray = kp.publicKeyBlob
        val options = SshKeyManager.restrictedGatewayOptions(config.gatewayPath)
        // 复用 SshKeyManager 的拼装（单一实现，避免两处漂移；那版已有单元测试）
        return SshKeyManager.authorizedKeysLine(blob, "journal-app", options)
    }

    // ---------------------------------------------------------------- 连接

    private fun options(
        config: SshConfig,
        host: String,
        wsUrl: String?,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    ): SftpClient.Options =
        SftpClient.Options(
            user = config.user,
            host = host,
            port = config.port,
            privateKeyPath = ensureKey().absolutePath,
            passphrase = null,
            wsUrl = wsUrl,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = READ_TIMEOUT_MS,
            windowSizeMax = config.effectiveWindowSizeMax,
            packetSize = config.effectivePacketSize,
            bulkRequests = config.effectiveBulkRequests,
        )

    /** 记录一下失败原因，便于界面上说清楚为什么退回隧道。 */
    private val directFailures = mutableListOf<String>()

    // ---- 中途换路："直连差就走隧道" --------------------------------------------------

    /** 本次会话的路线结论。null = 还没被实测推翻，按 [SshConfig.route] + 网络类型挑。 */
    @Volatile
    private var routeOverride: SshRoute? = null

    /** 被实测判为太慢的路，避免两条路来回弹。 */
    private val slowRoutes: MutableSet<SshRoute> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 看门狗决定换路时置上，交给重试循环执行。 */
    @Volatile
    private var pendingRouteSwitch: SshRoute? = null

    private fun currentRoute(conn: SshConnection): SshRoute = conn.route

    /** 重试循环里调用：把看门狗的决定落到 [routeOverride]。 */
    private fun applyPendingRouteSwitch() {
        val next = pendingRouteSwitch ?: return
        pendingRouteSwitch = null
        routeOverride = next
        directFailures += "实测太慢，已切到${next.label}"
        while (directFailures.size > 6) directFailures.removeAt(0)
    }

    /**
     * 把进度回调包一层：**传输过程中**判断这条路是不是烂的。
     * 坏路上 SSH 会话照样能建起来（实测 20% 丢包下握手都成功），只有真搬数据时才暴露成"每秒几十 KB"。
     */
    private fun watchRoute(
        route: SshRoute,
        onProgress: (Long, Long) -> Unit,
    ): (Long, Long) -> Unit {
        val start = System.currentTimeMillis()
        return { done, total ->
            onProgress(done, total)
            if (pendingRouteSwitch == null) {
                val elapsed = System.currentTimeMillis() - start
                val next = RouteWatchdog.decide(route, elapsed, done, slowRoutes)
                if (next != null) {
                    slowRoutes += route
                    pendingRouteSwitch = next
                    val kb = RouteWatchdog.kbPerSec(done, elapsed)
                    // 抛出去是为了立刻掐断这次传输；换路由重试循环执行，靠断点续传接着搬
                    throw IllegalStateException(
                        "${route.label}实测只有 ${"%.0f".format(kb)} KB/s" +
                            "（低于 ${RouteWatchdog.SLOW_FLOOR_BPS.toInt() / 1024} KB/s），下一轮改走${next.label}",
                    )
                }
            }
        }
    }

    /** 当前缓存的连接。可能已经死了 —— 用 [ensureConnection] 取，不要直接读。 */
    @Volatile
    private var cached: SshConnection? = null

    fun currentConnection(): SshConnection? = cached

    /**
     * 丢掉缓存连接。失败后**必须**调用：真机上出现过"界面拿的还是这条死连接，
     * 下一个动作立刻抛 `Pipe closed`，并被错报成目录问题"。
     */
    fun invalidateConnection(reason: String? = null) {
        cached?.let { conn -> runCatching { conn.client.close() } }
        cached = null
        if (reason != null) {
            directFailures += "旧连接已作废（$reason）"
            while (directFailures.size > 6) directFailures.removeAt(0)
        }
    }

    /** 取一条**确认活着**的连接：缓存可用就直接复用，否则重连。 */
    suspend fun ensureConnection(config: SshConfig, force: Boolean = false): SshConnection {
        val cur = cached
        if (!force && cur != null && cur.client.isAlive) return cur
        if (cur != null) invalidateConnection(if (force) "强制重连" else "连接已失效")
        val fresh = connect(config)
        cached = fresh
        return fresh
    }

    /**
     * 建立会话。选路顺序（2026-11-13 加 HTTP 后）：
     *  1. 明确选「隧道」→ 直接 SSH-over-WS。
     *  2. 移动数据/VPN/未知，或本机没有公网 IPv6 → 先 HTTP。
     *  3. 试已知直连地址（手填 / 上次成功）；没有就经 HTTP 问服务器的公网 IPv6。
     *  4. 直连不通 → HTTP。
     *  5. 还不行 → SSH-over-WS 兜底。
     *  6. 明确选「直连」而不通 → 报错，不悄悄换路。
     */
    suspend fun connect(config: SshConfig): SshConnection = withContext(Dispatchers.IO) {
        directFailures.clear()
        val started = System.currentTimeMillis()
        val configured = SshRoute.of(config.route)

        if (configured == SshRoute.TUNNEL) return@withContext openTunnel(config, started)

        val preferHttp = when {
            routeOverride == SshRoute.HTTP -> true
            routeOverride == SshRoute.DIRECT -> false
            else -> RouteChooser.preferHttp(context, configured)
        }
        if (preferHttp) tryHttp(config, started)?.let { return@withContext it }

        var candidates = config.candidatesForDirect()
        if (candidates.isEmpty()) {
            candidates = try {
                HttpTransferClient.probeIpv6(config.httpBaseUrl, config.httpDeviceId, config.httpToken)
            } catch (e: Exception) {
                directFailures += "查服务器 IPv6 失败：${SshErrors.describe(e)}"
                emptyList()
            }
        }
        for (host in candidates) {
            tryDirect(config, host)?.let { return@withContext it.copy(elapsedMs = System.currentTimeMillis() - started) }
        }

        if (configured == SshRoute.DIRECT) {
            throw IllegalStateException(
                "直连不通：" + (directFailures.joinToString("；").ifBlank { "没有可用的 IPv6 地址" }) +
                    "\n（想要「没有 IPv6 也能存档」，把路线改成「自动」或「HTTP」）",
            )
        }

        if (!preferHttp) tryHttp(config, started)?.let { return@withContext it }

        openTunnel(config, started)
    }

    /** 用 HTTP 通道连（没有 IPv6 时的正路）。失败返回 null，由调用方继续往下试。 */
    private fun tryHttp(config: SshConfig, started: Long): SshConnection? =
        try {
            val c = HttpTransferClient.open(config.httpBaseUrl, config.httpDeviceId, config.httpToken, CONNECT_TIMEOUT_MS)
            SshConnection(c, SshRoute.HTTP, config.httpBaseUrl, emptyList(), System.currentTimeMillis() - started)
        } catch (e: Exception) {
            directFailures += "HTTP 通道失败：${SshErrors.describe(e)}"
            null
        }

    /** SSH over WebSocket：任何网络都能建会话，但大文件会被 QUIC 那一跳弄坏。 */
    private fun openTunnel(config: SshConfig, started: Long): SshConnection {
        val tunnel = try {
            SftpClient.open(options(config, config.tunnelHostName, config.tunnelWsUrl))
        } catch (e: Exception) {
            directFailures += "隧道失败：${SshErrors.describe(e)}"
            throw IllegalStateException(
                "连不上服务器：" + directFailures.joinToString("；").ifBlank { SshErrors.describe(e) },
                e,
            )
        }
        var discovered: List<String> = emptyList()
        try {
            discovered = ServerAddressDiscovery.globalIpv6(tunnel)
        } catch (e: Exception) {
            directFailures += "经隧道读 /proc/net/if_inet6 失败：${SshErrors.describe(e)}"
        }
        return SshConnection(tunnel, SshRoute.TUNNEL, config.tunnelHostName, discovered, System.currentTimeMillis() - started)
    }

    private fun tryDirect(config: SshConfig, host: String): SshConnection? =
        try {
            // 探测用**短**超时：直连一旦建好会话就不再受这个值限制（它只管 TCP 连接 + 握手）
            val c = SftpClient.open(options(config, host, null, connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS))
            SshConnection(c, SshRoute.DIRECT, host, emptyList(), 0)
        } catch (e: Exception) {
            directFailures += "直连 $host 失败：${SshErrors.describe(e)}"
            null
        }

    fun directFailureSummary(): String = directFailures.joinToString("；")

    /** 用户在界面上改了"走哪条路" → 清掉本次会话的实测结论，重新按配置 + 网络类型判。 */
    fun resetRouteDecision() {
        routeOverride = null
        pendingRouteSwitch = null
        slowRoutes.clear()
    }

    // ---------------------------------------------------------------- 上传

    fun describe(uri: Uri): UploadItem {
        var name = "upload.bin"
        var size = -1L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
        // 有的提供方查 MIME 会抛（URI 授权过期等）；它只是给白名单做类型判断，不值得让整个选择失败
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return UploadItem(uri, sanitize(name), size, mime)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "upload.bin" }

    /**
     * 上传一个 SAF 文件：**断了自动重连、从断点续传**，最多 [MAX_ATTEMPTS] 轮。
     *
     * 与传输助手同一套实现（那边已是实测过的形状）：
     *  - 先落本地暂存再按路径传 —— SAF 流经常每次只给几 KB，JSch 会变成一个 WRITE 一个往返，
     *    慢到 35–75 KB/s；本地文件走 JSch 最成熟的按路径分支，快一个数量级，且天然支持 RESUME。
     *  - 续传是**安全**的：SSH 每个报文都过 MAC 校验，校验不过服务端直接掐连接、不会落盘，
     *    所以"连接断掉时远端已有的那部分"一定是正确的本地前缀。
     */
    suspend fun uploadResilient(
        config: SshConfig,
        item: UploadItem,
        targetDir: String,
        onPhase: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val staged = stageLocally(item, onPhase)
        try {
            uploadStaged(config, staged.file, item.displayName, staged.sha256, targetDir, onPhase, onProgress)
        } finally {
            // 无论成败都要清掉暂存文件，别把手机缓存撑爆（远端那半截**不删**，它是续传的起点）
            staged.file.delete()
        }
    }

    /**
     * 文本存档：把一段文本写成文件，再走**同一条**上传路径。
     *
     * 与传输助手的差别：那边靠 FileProvider 把文本变成内容 URI 再复用上传逻辑，
     * 这里不需要 —— 文本本来就是本地文件，直接进"已暂存文件"那条路，
     * 于是这个 App 连 FileProvider 都不用声明。
     */
    suspend fun uploadText(
        config: SshConfig,
        text: String,
        fileName: String,
        targetDir: String,
        onPhase: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val safeName = sanitize(fileName).ifBlank { "text-${System.currentTimeMillis()}.txt" }
        val dir = File(context.cacheDir, "text-upload").apply { mkdirs() }
        val f = File(dir, safeName)
        val md = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(f.outputStream().buffered(), md).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        try {
            uploadStaged(config, f, safeName, sha, targetDir, onPhase, onProgress)
        } finally {
            f.delete()
        }
    }

    /** 已经把内容落到本地文件的统一上传路径（文件、文本都进这里）。 */
    private suspend fun uploadStaged(
        config: SshConfig,
        stagedFile: File,
        displayName: String,
        sha256: String,
        targetDir: String,
        onPhase: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): UploadOutcome {
        val dir = targetDir.trimEnd('/')
        val remotePath = "$dir/$displayName"
        val localLen = stagedFile.length()
        var lastError: Exception? = null

        try {
            for (attempt in 1..MAX_ATTEMPTS) {
                if (attempt > 1) {
                    // 退避：给移动网/地址切换一点时间，别立刻扑上去又断
                    val backoffMs = when (attempt) {
                        2 -> 1_500L
                        3 -> 4_000L
                        else -> 8_000L
                    }
                    onPhase("等 ${backoffMs / 1000}s 后第 $attempt 次尝试（断点续传）")
                    delay(backoffMs)
                }
                try {
                    val conn = ensureConnection(config, force = attempt > 1)
                    val client = conn.client
                    val route = currentRoute(conn)

                    // 目录不存在就创建（自愈）；查目录失败会明确说是"查失败"而不是"不存在"
                    if (!client.ensureDir(dir)) onPhase("已创建目录")

                    val existing = client.remoteSize(remotePath) // 确定不存在才是 -1，其他错误会抛
                    when {
                        existing == localLen && localLen > 0 -> {
                            // 上一轮其实已经传完了（或远端本来就有同长度文件）：不重传，
                            // 用返回的 sha256 在服务器上核对即可
                            return UploadOutcome(
                                remotePath = remotePath, bytes = existing, sha256 = sha256,
                                attempts = attempt, resumedFrom = existing, skippedAlreadyComplete = true,
                                label = conn.label, directHost = conn.host.takeIf { conn.viaDirect },
                                viaTunnel = !conn.viaDirect,
                            )
                        }

                        existing > localLen -> {
                            // 远端比本地还长 ⇒ 它不是本地文件的前缀，不能续传，只能重来
                            onPhase("远端比本地长（${Formatters.humanSize(existing)} > ${Formatters.humanSize(localLen)}），从头重传")
                            client.upload(stagedFile, remotePath, watchRoute(route, onProgress))
                        }

                        existing > 0 -> {
                            onPhase("续传（从 ${Formatters.humanSize(existing)} 处接着传）")
                            client.resumeUpload(stagedFile, remotePath, existing, watchRoute(route, onProgress))
                        }

                        else -> {
                            onPhase("上传中")
                            client.upload(stagedFile, remotePath, watchRoute(route, onProgress))
                        }
                    }

                    val total = client.remoteSize(remotePath)
                    if (total != localLen) {
                        throw IllegalStateException("传完后远端大小 $total ≠ 本地 $localLen（对不上，重来）")
                    }
                    return UploadOutcome(
                        remotePath = remotePath, bytes = total, sha256 = sha256,
                        attempts = attempt, resumedFrom = existing.coerceAtLeast(0L),
                        skippedAlreadyComplete = false,
                        label = conn.label, directHost = conn.host.takeIf { conn.viaDirect },
                        viaTunnel = !conn.viaDirect,
                    )
                } catch (e: Exception) {
                    lastError = e
                    // 看门狗说"这条路太慢"→ 先把路线钉到另一条上，再作废连接，
                    // 这样下一轮（断点续传）就走新路，而不是在烂路上原地重试。
                    applyPendingRouteSwitch()
                    invalidateConnection(SshErrors.describe(e))
                    if (attempt < MAX_ATTEMPTS) {
                        onPhase("第 $attempt 轮中断：${SshErrors.hint(e)}")
                    }
                }
            }

            throw IllegalStateException(
                "重试 $MAX_ATTEMPTS 轮仍失败：${SshErrors.describe(lastError)}\n" +
                    "（远端已传的部分保留着，再点一次上传会从断点继续，不会从头重传）",
                lastError,
            )
        } catch (e: Exception) {
            // 目标路径一定要在消息里露出来：之前 SSH_FX_NO_SUCH_FILE 排查时，
            // 界面上只有文件名、看不到目录，只能靠猜配置哪里写错了。
            throw IllegalStateException("目标 $remotePath 失败：${e.message}", e)
        }
    }

    // ---------------------------------------------------------------- 触发归档

    /**
     * 两条通道都能触发：SFTP 用同一条会话的 exec 通道发 [ARCHIVE_TRIGGER_COMMAND]，
     * HTTP 用 `POST /api/archive/trigger`（服务端跑同一个 `inbox trigger`）。
     *
     * 服务端收到就立刻回一行 `QUEUED ...`，归档是它自己在后台做的，App 不等结果 ——
     * 所以这里只需要把那一行读回来显示给用户。归档按"批"处理，所以调用方要等**整批都传成功**再发。
     */
    suspend fun triggerArchive(config: SshConfig): String = withContext(Dispatchers.IO) {
        val conn = try {
            ensureConnection(config)
        } catch (e: Exception) {
            throw IllegalStateException("连接失败：${SshErrors.describe(e)}", e)
        }
        try {
            // SFTP 走 exec（服务端 forced command 只认这个字面量），HTTP 走 /api/archive/trigger
            conn.client.triggerArchive()
        } catch (e: Exception) {
            invalidateConnection("触发归档失败：${SshErrors.describe(e)}")
            throw IllegalStateException(SshErrors.describe(e), e)
        }
    }

    // ---------------------------------------------------------------- 读待办索引

    /**
     * 读回一个远端文本文件（本 App 只用它读 `todos/index.jsonl`）。
     *
     * 先 `stat` 再读：**文件不存在**要和"会话死了/权限不对"分开说 ——
     * 后者按 [SshErrors] 的老规矩要作废连接；前者是"服务端还没归档过任何东西"，
     * 是一句正常的状态，不该让用户去查网络。
     */
    suspend fun readRemoteText(config: SshConfig, remotePath: String): RemoteText = withContext(Dispatchers.IO) {
        val path = remotePath.trim()
        if (path.isEmpty()) throw IllegalArgumentException("远端路径不能为空")
        val conn = try {
            ensureConnection(config)
        } catch (e: Exception) {
            throw IllegalStateException("连接失败：${SshErrors.describe(e)}", e)
        }
        val size = try {
            conn.client.remoteSize(path)
        } catch (e: Exception) {
            invalidateConnection(SshErrors.describe(e))
            throw IllegalStateException("查远端文件失败（$path）：${SshErrors.describe(e)}", e)
        }
        if (size < 0) throw IllegalStateException("服务器上还没有 $path（服务端还没归档过任何东西？）")
        val mtime = runCatching { conn.client.remoteMtime(path) }.getOrDefault(0)
        val text = try {
            conn.client.readText(path)
        } catch (e: Exception) {
            invalidateConnection(SshErrors.describe(e))
            throw IllegalStateException("读远端文件失败（$path）：${SshErrors.describe(e)}", e)
        }
        RemoteText(text, mtime, size, conn.label)
    }

    // ---------------------------------------------------------------- 暂存与工具

    private class Staged(val file: File, val sha256: String)

    /**
     * 把 SAF 流快速落到 cacheDir 下的暂存文件；这一步是本地读写，很快。
     * 顺手算 sha256（靠 DigestInputStream，**不额外读一遍**）：
     * 手机上看到这个哈希，再去服务器上 `sha256sum` 同一个文件，就能确认"续传/重试之后字节到底对不对"。
     */
    private fun stageLocally(item: UploadItem, onPhase: (String) -> Unit): Staged {
        val dir = File(context.cacheDir, "upload-staging").apply { mkdirs() }
        val need = if (item.size > 0) item.size else 0L
        val free = allocatableBytes(dir)
        if (need > 0 && free < need + (32L shl 20)) {
            throw IllegalStateException(
                "缓存空间不足：需要 ${need / 1048576}MB，可用 ${free / 1048576}MB（上传前要先暂存一份）"
            )
        }
        onPhase("准备中（先落到手机缓存）")
        val target = File(dir, "${System.nanoTime()}-${item.displayName}")
        val md = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(item.uri)
            ?: throw IllegalStateException("打不开文件：${item.displayName}（URI 授权可能已过期，请重新选择）")
        try {
            input.use { raw ->
                DigestInputStream(BufferedInputStream(raw, 256 * 1024), md).use { src ->
                    target.outputStream().buffered(256 * 1024).use { dst ->
                        src.copyTo(dst, 256 * 1024)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw IllegalStateException("读取文件失败（${item.displayName}）：${SshErrors.describe(e)}", e)
        }
        return Staged(target, md.digest().joinToString("") { "%02x".format(it) })
    }

    /**
     * 可分配空间。用 StorageManager 而不是 File.usableSpace：后者不算"可清理的缓存"，
     * 会把本来能传的情况误判成空间不足（lint 也提了这条）。
     */
    private fun allocatableBytes(dir: File): Long {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        if (sm != null) {
            runCatching {
                return sm.getAllocatableBytes(sm.getUuidForPath(dir))
            }
        }
        return dir.usableSpace
    }
}
