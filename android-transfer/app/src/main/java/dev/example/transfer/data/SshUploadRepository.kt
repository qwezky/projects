package dev.example.transfer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.example.transfer.net.ServerAddressDiscovery
import dev.example.transfer.net.SftpClient
import dev.example.transfer.net.SshErrors
import java.io.BufferedInputStream
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/** 一次上传的文件（来自 SAF 内容 URI）。 */
data class UploadItem(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    /** SAF 给的 MIME（可能就是 null/octet-stream；⑥ 上传不挑类型，只记下来备查） */
    val mime: String? = null,
)

/** 连接结果：走了哪条路、发现了什么地址、耗时多少。 */
data class SshConnection(
    val client: SftpClient,
    val viaDirect: Boolean,
    val host: String,
    val discoveredIpv6: List<String>,
    val elapsedMs: Long,
) {
    val label: String
        get() = if (viaDirect) "直连 $host" else "经隧道（$host）"
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

/**
 * SSH/SFTP 上传的数据层。
 *
 * 设计要点（都是实测结论，不是猜的）：
 *  1. 密钥用 RSA-4096：ed25519 在 Android 上不可用（jsch 的 Multi-Release jar 里
 *     JCE EdDSA 在 versions/15，基础包直接抛 "requires Java15+"），补 BC 要多 6.9MB。
 *  2. 密钥在 App 私有目录生成并落盘；服务器上那一行用
 *     `restrict,command="internal-sftp -d %d/phone-upload"` 把能力收窄到只能 SFTP。
 *  3. 连接顺序：手填地址 / 上次成功地址 → 隧道（顺便读 /proc/net/if_inet6 发现地址）→
 *     用发现的地址直连 → 都失败才退回隧道。
 *  4. **连接是被缓存的，所以必须会被作废**：一条静默死掉的连接如果继续用，
 *     下一个文件会立刻报 `Pipe closed`（看起来像目录问题）。见 [invalidateConnection]。
 *  5. **上传是可续传、可重试的**：手机移动网这条链路会中途断（实测 93MB 的录屏
 *     传到一半连接静默消失）。断了就重连、从远端已有字节接着传，最多 [MAX_ATTEMPTS] 轮。
 *     远端那半截**绝不删除** —— 它就是续传的起点。
 */
class SshUploadRepository(private val context: Context) {

    private companion object {
        /** 经隧道 / 正式建会话的超时 */
        const val CONNECT_TIMEOUT_MS = 20_000

        /** 直连探测用短超时：避免 IPv6 黑洞化时退回隧道前干等太久 */
        const val PROBE_CONNECT_TIMEOUT_MS = 6_000

        /** 会话读超时（session.timeout）。传输中长时间没有数据才算死 */
        const val READ_TIMEOUT_MS = 60_000

        /** 一个文件最多传几轮（含第一轮） */
        const val MAX_ATTEMPTS = 4
        // 中途换路的判据（观察窗/最小样本/速率下限）在 [RouteWatchdog] 里 —— 那边是纯函数，有单测
    }

    private val keyFile: File get() = File(context.filesDir, "ssh/id_rsa")

    // ---------------------------------------------------------------- 密钥

    fun hasKey(): Boolean = keyFile.isFile && keyFile.length() > 0

    /** 首次使用时生成 RSA-4096；已存在则直接用。 */
    fun ensureKey(): File {
        if (hasKey()) return keyFile
        val jsch = JSch()
        val generated = dev.example.transfer.net.SshKeyManager.generateRsa4096(
            jsch = jsch,
            privateKeyFile = keyFile,
            passphrase = null,
            comment = "transfer-demo-app",
            options = null, // 公钥行在 authorizedKeysLine() 里带选项拼，便于改目录
        )
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
        keyFile.setWritable(false, false)
        keyFile.setWritable(true, true)
        return generated.privateKeyFile
    }

    /**
     * 从私钥推导出要粘到服务器 `~/.ssh/authorized_keys` 的那一行。
     *
     * v2.5 起本 App 只剩上传/下载（全是 SFTP），所以授权行也收回到**纯 SFTP** 形态：
     * `restrict,command="internal-sftp -d %d/phone-upload"` —— 与服务器第 3 行逐字一致。
     * （v2.0–v2.4 那行指向归档网关，是为了"传完触发归档"；那个功能已经搬到日志 App。）
     */
    fun authorizedKeysLine(config: SshConfig): String {
        val f = ensureKey()
        val jsch = JSch()
        val kp = KeyPair.load(jsch, f.absolutePath)
        val blob: ByteArray = kp.publicKeyBlob
        val options = dev.example.transfer.net.SshKeyManager.restrictedSftpOptions("%d/phone-upload")
        // 复用 SshKeyManager 的拼装（单一实现，避免两处漂移；那版已有单元测试）
        return dev.example.transfer.net.SshKeyManager.authorizedKeysLine(blob, "transfer-demo-app", options)
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

    /**
     * 本次会话的路线结论。null = 还没被实测推翻，按 [SshConfig.route] + 网络类型挑。
     * 一旦某条路被实测判为"太慢"，这里就钉到另一条上，后续重试/续传都走它。
     */
    @Volatile
    private var routeOverride: TransferRoute? = null

    /** 被实测判为太慢的路，避免两条路来回弹。 */
    private val slowRoutes: MutableSet<TransferRoute> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 看门狗决定换路时置上，交给重试循环执行（不靠异常类型识别，免得被 JSch 包一层就认不出）。 */
    @Volatile
    private var pendingRouteSwitch: TransferRoute? = null

    private fun currentRoute(conn: SshConnection): TransferRoute =
        if (conn.viaDirect) TransferRoute.DIRECT else TransferRoute.TUNNEL

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
     *
     * 为什么必须"过程中"判、不能"连之前"判：坏路上 SSH 会话照样能建起来
     * （实测 20% 丢包下握手都成功），只有真搬数据时才暴露成"每秒几十 KB"。
     * 窗口 6 秒 + 至少搬过 512 KB 才判，免得把启动抖动误判成慢速。
     */
    private fun watchRoute(
        route: TransferRoute,
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
     * 丢掉缓存连接。
     *
     * 失败后**必须**调用：真机上那次 93MB 中断之后，界面拿的还是这条死连接，
     * 下一个动作立刻抛 `Pipe closed`，并被错报成"远程目录不存在且创建失败"。
     */
    fun invalidateConnection(reason: String? = null) {
        cached?.let { conn -> runCatching { conn.client.close() } }
        cached = null
        if (reason != null) {
            directFailures += "旧连接已作废（$reason）"
            while (directFailures.size > 6) directFailures.removeAt(0)
        }
    }

    /**
     * 取一条**确认活着**的连接：缓存可用就直接复用，否则重连。
     * [force] = 上一轮刚失败过，别信这条连接了。
     */
    suspend fun ensureConnection(config: SshConfig, force: Boolean = false): SshConnection {
        val cur = cached
        if (!force && cur != null && cur.client.isAlive) return cur
        if (cur != null) invalidateConnection(if (force) "强制重连" else "连接已失效")
        val fresh = connect(config)
        cached = fresh
        return fresh
    }

    /**
     * 建立会话：优先直连（快、批量传输可靠），失败才退回隧道（任何网络都能通，但批量不行）。
     * 整个过程在 IO 线程上跑。
     */
    suspend fun connect(config: SshConfig): SshConnection = withContext(Dispatchers.IO) {
        directFailures.clear()
        val started = System.currentTimeMillis()

        // 0) 先按"用户选的路 / 网络类型 / 上一次的实测结论"决定要不要直接走隧道。
        //    为什么要这一步：坏路上直连能连上只是慢，先拿直连去撞会白等一轮（还在慢速里搬数据）。
        val configured = TransferRoute.of(config.route)
        val preferTunnel = when {
            configured == TransferRoute.DIRECT -> false
            configured == TransferRoute.TUNNEL -> true
            routeOverride == TransferRoute.TUNNEL -> true
            routeOverride == TransferRoute.DIRECT -> false
            else -> RouteChooser.preferTunnel(context, configured)
        }
        if (preferTunnel) {
            try {
                val tunnel = SftpClient.open(options(config, config.tunnelHostName, config.tunnelWsUrl))
                var discovered: List<String> = emptyList()
                try {
                    discovered = ServerAddressDiscovery.globalIpv6(tunnel)
                } catch (e: Exception) {
                    directFailures += "经隧道读 /proc/net/if_inet6 失败：${SshErrors.describe(e)}"
                }
                return@withContext SshConnection(
                    client = tunnel,
                    viaDirect = false,
                    host = config.tunnelWsUrl,
                    discoveredIpv6 = discovered,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            } catch (e: Exception) {
                // 隧道自己开不起来（比如没有到 CF 的网）→ 继续往下试直连，别把路堵死
                directFailures += "隧道失败：${SshErrors.describe(e)}"
            }
        }

        // 1) 先试已知的直连地址（手填的、上次成功的）—— 不需要隧道，最快
        for (host in config.candidatesForDirect()) {
            tryDirect(config, host)?.let { return@withContext it.copy(elapsedMs = System.currentTimeMillis() - started) }
        }

        // 2) 经隧道建立会话（同时用它做 IPv6 发现）
        val tunnel = SftpClient.open(options(config, config.tunnelHostName, config.tunnelWsUrl))
        var discovered: List<String> = emptyList()
        try {
            discovered = ServerAddressDiscovery.globalIpv6(tunnel)
        } catch (e: Exception) {
            directFailures += "经隧道读 /proc/net/if_inet6 失败：${SshErrors.describe(e)}"
        }

        // 3) 用发现的地址试直连
        for (host in discovered) {
            if (host in config.candidatesForDirect()) continue
            val direct = tryDirect(config, host)
            if (direct != null) {
                runCatching { tunnel.close() }
                return@withContext direct.copy(
                    elapsedMs = System.currentTimeMillis() - started,
                    discoveredIpv6 = discovered,
                )
            }
        }

        // 4) 退回隧道
        SshConnection(
            client = tunnel,
            viaDirect = false,
            host = config.tunnelWsUrl,
            discoveredIpv6 = discovered,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    private fun tryDirect(config: SshConfig, host: String): SshConnection? =
        try {
            // 探测用**短**超时：直连一旦建好会话就不再受这个值限制（它只管 TCP 连接 + 握手；
            // 认证成功后 JSch 会把 socket 的读超时改回 session.timeout，这一点字节码核对过），
            // 但 IPv6 黑洞化时能让"退回隧道"快很多，而不是干等 20 秒 × 候选数。
            val c = SftpClient.open(options(config, host, null, connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS))
            SshConnection(c, viaDirect = true, host = host, discoveredIpv6 = emptyList(), elapsedMs = 0)
        } catch (e: Exception) {
            directFailures += "直连 $host 失败：${SshErrors.describe(e)}"
            null
        }

    fun directFailureSummary(): String = directFailures.joinToString("；")

    /**
     * 用户在界面上改了"走哪条路" → 清掉本次会话的实测结论，重新按配置 + 网络类型判。
     * 否则会出现"用户在 WiFi 上选了自动，却还记着上一段移动数据里'直连太慢'的结论"。
     */
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
        // 有的提供方查 MIME 会抛（URI 授权过期等）；它只是随文件带下去的元信息，不值得让整个选择失败
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return UploadItem(uri, sanitize(name), size, mime)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "upload.bin" }

    /**
     * 上传一个 SAF 文件：**断了自动重连、从断点续传**，最多 [MAX_ATTEMPTS] 轮。
     *
     * 为什么要有重试（真机教训）：93MB 的录屏传到 92,994,209 字节时连接静默消失
     * （服务端日志只有 `session closed`，没有 fatal）。当时 App 的做法是"失败就结束"，
     * 已经传上去的 93MB 就此作废；而且下一次点重试时用的还是那条死连接，
     * 报出来的错还指错了方向。
     *
     * 为什么续传是**安全**的：SSH 每个报文都过 MAC 校验，校验不过服务端直接掐连接、
     * 不会落盘。所以"连接断掉时远端已有的那部分"一定是正确的本地前缀，
     * 从那个偏移接着传不会拼出坏文件（最终仍有 sha256 可核对）。
     *
     * 为什么先落本地暂存再按路径传（早期踩坑后改的，原始判断后来修正过）：
     *  - SAF 流经常每次只给几 KB，JSch 就会一个 WRITE 一个往返，慢到 35–75 KB/s；
     *    本地文件让 JSch 走它最成熟的按路径分支（实测快一个数量级）。
     *  - 按路径才天然支持 JSch 的 RESUME 续传（流式只有 APPEND+自己 skip）。
     *  - 顺带躲开"边读边传时系统/提供方在慢速下掐掉流"的问题。
     */
    suspend fun uploadResilient(
        config: SshConfig,
        item: UploadItem,
        /** 目标目录：⑥ 上传用配置里的 remoteDir（存档那条固定目录已经跟着存档功能搬走了）。 */
        targetDir: String = config.remoteDirForDisplay,
        onPhase: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val dir = targetDir.trimEnd('/')
        val staged = stageLocally(item, onPhase)
        val remotePath = "$dir/${item.displayName}"
        val localLen = staged.file.length()
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
                            return@withContext UploadOutcome(
                                remotePath = remotePath, bytes = existing, sha256 = staged.sha256,
                                attempts = attempt, resumedFrom = existing, skippedAlreadyComplete = true,
                                label = conn.label, directHost = conn.host.takeIf { conn.viaDirect },
                                viaTunnel = !conn.viaDirect,
                            )
                        }

                        existing > localLen -> {
                            // 远端比本地还长 ⇒ 它不是本地文件的前缀，不能续传，只能重来
                            onPhase("远端比本地长（${human(existing)} > ${human(localLen)}），从头重传")
                            client.upload(staged.file, remotePath, watchRoute(route, onProgress))
                        }

                        existing > 0 -> {
                            onPhase("续传（从 ${human(existing)} 处接着传）")
                            client.resumeUpload(staged.file, remotePath, existing, watchRoute(route, onProgress))
                        }

                        else -> {
                            onPhase("上传中")
                            client.upload(staged.file, remotePath, watchRoute(route, onProgress))
                        }
                    }

                    val total = client.remoteSize(remotePath)
                    if (total != localLen) {
                        throw IllegalStateException("传完后远端大小 $total ≠ 本地 $localLen（对不上，重来）")
                    }
                    return@withContext UploadOutcome(
                        remotePath = remotePath, bytes = total, sha256 = staged.sha256,
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
                    // 关键：把这条件已不可信的连接扔掉，下一轮强制重连。
                    // 否则下一个动作拿死连接又报 Pipe closed，错误信息继续指错方向。
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
        } finally {
            // 无论成败都要清掉暂存文件，别把手机缓存撑爆
            // （远端那半截文件**不删**，它是续传的起点）
            staged.file.delete()
        }
    }

    /**
     * 文本上传：把一段文本写成文件，再走**同一条** SFTP 通道上传。
     *
     * 用途：把剪贴板内容、或者别处粘来的一段文字，直接变成服务器上的一个 .txt。
     * 实现上刻意复用 [uploadResilient] —— 续传、重连、sha256 上报全部白拿，
     * 只是先把文本落到 cacheDir 里、再用 FileProvider 变成一个内容 URI（上传逻辑本来就吃 URI）。
     */
    suspend fun uploadText(
        config: SshConfig,
        text: String,
        fileName: String,
        /** 目标目录：⑥ 上传卡片用配置里的 remoteDir。 */
        targetDir: String = config.remoteDirForDisplay,
        onPhase: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val safeName = sanitize(fileName).ifBlank { "text-${System.currentTimeMillis()}.txt" }
        val dir = File(context.cacheDir, "text-upload").apply { mkdirs() }
        val f = File(dir, safeName)
        f.writeText(text)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                f,
            )
            uploadResilient(config, describe(uri), targetDir = targetDir, onPhase = onPhase, onProgress = onProgress)
        } finally {
            // 传完就删；远端那半截保留（续传起点）
            f.delete()
        }
    }

    private class Staged(val file: File, val sha256: String)

    /**
     * 把 SAF 流快速落到 cacheDir 下的暂存文件；这一步是本地读写，很快。
     *
     * 顺手算 sha256（靠 DigestInputStream，**不额外读一遍**）：
     * 手机上看到这个哈希，再去服务器上 `sha256sum` 同一个文件，就能确认
     * "续传/重试之后字节到底对不对" —— 这是整条链路唯一能证伪的检查。
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

    suspend fun listRemote(connection: SshConnection, config: SshConfig): List<String> =
        withContext(Dispatchers.IO) {
            connection.client.list(config.remoteDirForDisplay)
                .filter { it.name != "." && it.name != ".." }
                .map { "${if (it.dir) "d" else "-"} ${it.size} ${it.name}" }
        }

    // ---------------------------------------------------------------- 下载（服务器 → 手机）
    //
    // 和上传是**同一条通道、同一把 key、同一套调参**：手机主动连服务器的 22 端口（IPv6 直连优先），
    // 走 SFTP。这样两个方向都不需要 Cloudflare 隧道、不需要额外开监听端口、不需要浏览器 token。

    /** 列远端目录：目录在前、文件在后，各自按名字排序。 */
    suspend fun listDir(config: SshConfig, dir: String): List<SftpClient.RemoteEntry> =
        withContext(Dispatchers.IO) {
            val target = dir.trim()
            if (target.isEmpty()) throw IllegalArgumentException("远端目录不能为空")
            val conn = try {
                ensureConnection(config)
            } catch (e: Exception) {
                throw IllegalStateException("连接失败：${SshErrors.describe(e)}", e)
            }
            try {
                conn.client.list(target)
                    .filter { it.name != "." && it.name != ".." }
                    .sortedWith(compareByDescending<SftpClient.RemoteEntry> { it.dir }.thenBy { it.name.lowercase() })
            } catch (e: Exception) {
                if (SshErrors.isSessionDead(e)) invalidateConnection(SshErrors.describe(e))
                throw IllegalStateException("列目录失败（$target）：${SshErrors.describe(e)}", e)
            }
        }

    /** 一次下载的结果。 */
    data class DownloadResult(
        val file: File,
        val bytes: Long,
        val attempts: Int,
        /** 这一轮开始时暂存里已有多少字节（> 0 = 是接着上次下的） */
        val resumedFrom: Long,
        val label: String,
        val directHost: String?,
    )

    /**
     * 从远端下载一个文件到 [destDir]，**断了自动重连接着下**（最多 [MAX_ATTEMPTS] 轮）。
     *
     * 和上传对称：先在 `cacheDir/sftp-downloads/` 里下（只有本地文件才能用 JSch 的
     * "按路径 + RESUME"续传，SAF 的 OutputStream 没法定位），下完、核对字节数一致之后
     * 才搬进 [destDir]。
     *
     * 中途失败时**故意保留暂存文件** —— 它就是续传起点（下次点同一个文件会从断点接着下）。
     */
    suspend fun downloadResilient(
        config: SshConfig,
        remotePath: String,
        destDir: File,
        onPhase: (String) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val name = sanitize(remotePath.substringAfterLast('/').ifBlank { "download.bin" })
        val stagingDir = File(context.cacheDir, "sftp-downloads").apply { mkdirs() }
        val staged = File(stagingDir, name)
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
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
                val remoteBytes = client.remoteSize(remotePath) // 确定不存在才是 -1，其他错误会抛
                if (remoteBytes < 0) throw IllegalStateException("远端没有这个文件：$remotePath")
                if (remoteBytes == 0L) throw IllegalStateException("远端文件是空的：$remotePath")

                // 空间检查：暂存一份、搬进目标目录后仍是同一份（rename 不额外占），留 32MB 余量
                val free = allocatableBytes(stagingDir)
                if (free < remoteBytes + (32L shl 20)) {
                    throw IllegalStateException(
                        "手机空间不足：需要 ${human(remoteBytes)}，可分配 ${human(free)}"
                    )
                }

                var have = if (staged.isFile) staged.length() else 0L
                if (have > remoteBytes) {
                    // 暂存文件比远端还长 ⇒ 它不是远端文件的有效前缀，只能重下
                    staged.delete()
                    have = 0L
                }
                if (have == remoteBytes) {
                    onPhase("暂存里已有完整一份，直接搬过去")
                } else {
                    onPhase(if (have > 0) "续传下载（从 ${human(have)} 处接着下）" else "下载中")
                    client.downloadTo(remotePath, staged, resumeFrom = have, onProgress = watchRoute(route, onProgress))
                }

                val got = staged.length()
                if (got != remoteBytes) {
                    throw IllegalStateException("下载后本地 $got 字节 ≠ 远端 $remoteBytes 字节（对不上，重来）")
                }
                destDir.mkdirs()
                val dest = File(destDir, name)
                if (dest.exists() && !dest.delete()) throw IllegalStateException("无法覆盖同名文件：${dest.name}")
                if (!staged.renameTo(dest)) {
                    staged.copyTo(dest, overwrite = true)
                    staged.delete()
                }
                return@withContext DownloadResult(
                    file = dest,
                    bytes = dest.length(),
                    attempts = attempt,
                    resumedFrom = have,
                    label = conn.label,
                    directHost = conn.host.takeIf { conn.viaDirect },
                )
            } catch (e: Exception) {
                lastError = e
                applyPendingRouteSwitch()
                invalidateConnection(SshErrors.describe(e))
                if (attempt < MAX_ATTEMPTS) onPhase("第 $attempt 轮中断：${SshErrors.hint(e)}")
            }
        }
        throw IllegalStateException(
            "下载重试 $MAX_ATTEMPTS 轮仍失败：${SshErrors.describe(lastError)}\n" +
                "（已下好的部分留在手机缓存里，再点一次会从断点继续）",
            lastError,
        )
    }

    /** 顺便用一下：给界面/日志一个人类可读的大小。 */
    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
