package dev.example.journal.data

import android.content.Context
import androidx.core.content.edit

/**
 * SSH/SFTP 配置。持久化在 SharedPreferences，键名前缀 `ssh_`。
 *
 * 这个 App 只干两件事，所以配置面比传输助手窄得多：
 *  1. **上传存档** —— 文件/文本落进固定的 [archiveDir]，传完整批触发一次归档；
 *  2. **看待办** —— 只读地把 [todosIndexPath] 拉回本地，在自己手机上按日期区间筛。
 *
 * 两个路径都**钉死**（界面上没有输入框）：它们是服务端那条归档流水线的约定 ——
 * 目标一旦可以手改，"存档到哪里"就不再是约定，服务端也就对不上了。
 */
data class SshConfig(
    val user: String = "user",
    val port: Int = 22,
    /** 存档收件箱：服务端 `inbox run` 扫的就是这个目录。 */
    val archiveDir: String = "/home/user/phone-archive/raw",
    /**
     * 待办索引（机读）：服务端 `todos/index.jsonl`，一行一个 JSON 对象。
     *
     * 为什么读它而不是给服务器加一条命令：网关（forced command）放行的第一条本来就是 SFTP，
     * 而这个文件里 task/日期/状态/依据/来源/票数全都有 —— 服务端一个字都不用改，
     * App 这边本地筛完就完事。**只读**：App 不会写回服务器上的待办。
     */
    val todosIndexPath: String = "/home/user/phone-archive/todos/index.jsonl",
    /**
     * 服务端那个**受限期 SSH 网关**的绝对路径 —— 也就是本 App 这把 key 在服务器
     * `authorized_keys` 里 `command="..."` 该填的东西。
     *
     * 网关把这条 key 的能力收窄成两件事：照旧走 SFTP（读索引、传存档），
     * 或执行**唯一一个**固定字符串 `archive-trigger`（让服务器开始一轮归档）。
     * 「我的公钥」页面照这个值拼授权行，粘过去才和服务器现状一致。
     */
    val gatewayPath: String = "/home/user/dsh-work/inbox-archiver/bin/transfer-gateway",
    /** 经隧道时的 SSH 主机名（只用于 JSch 记账，实际连的是隧道） */
    val tunnelHostName: String = "ssh.example.com",
    val tunnelWsUrl: String = "wss://ssh.example.com/",
    /**
     * HTTP 通道（2026-11-13 加）—— 没有 IPv6 时走这条。
     * 服务端：~/dsh-work/trans-http（cloudflared ingress trans.example.com → 127.0.0.1:8810）。
     */
    val httpBaseUrl: String = "https://transfer.example.com",
    /** 这台设备在服务器上的登记 id（`trans-http-device list` 能看到）。 */
    val httpDeviceId: String = HTTP_DEVICE_ID,
    /** 这台设备自己的 HTTP 密钥（服务器只存 sha256，可单独吊销）。 */
    val httpToken: String = HTTP_TOKEN,
    /** 手动填的直连地址（IPv6 字面量，如 2001:db8::1...）；留空则自动发现 */
    val directHostOverride: String = "",
    /** 上次成功直连的地址，用于下次快速直连 */
    val lastGoodDirectHost: String = "",
    /** SFTP 调参：窗口/包大小/流水线深度。0 = JSch 默认。
     *  **刻意不持久化** —— 界面上没有入口，存起来只会让改了默认值的老安装继续用旧值。 */
    val windowSizeMax: Int = 1 shl 20,
    val packetSize: Int = 64 * 1024,
    val bulkRequests: Int = 64,
    /**
     * 保守传输模式（要持久化，因为它是用户能看见的开关）。
     * 打开后上面三项全部交回 JSch 默认值 —— 这条链路（手机移动网 → 校园网 IPv6）
     * 上出现过 `message authentication code incorrect`，需要一个一键 A/B 的开关。
     */
    val conservative: Boolean = false,
    /** 走哪条路：`auto` / `direct` / `tunnel`（取值见 [SshRoute]）。 */
    val route: String = SshRoute.AUTO.id,
    /** 待办卡片默认查询的天数（从今天起算），只是界面初值，不改服务端 */
    val defaultRangeDays: Int = 14,
) {
    val archiveDirForDisplay: String get() = archiveDir.trimEnd('/')

    val todosIndexForDisplay: String get() = todosIndexPath.trim()

    // 保守模式下把三项调参交回 JSch 默认值。集中在这里，免得别处漏改一项。
    val effectiveWindowSizeMax: Int get() = if (conservative) 0 else windowSizeMax
    val effectivePacketSize: Int get() = if (conservative) 0 else packetSize
    val effectiveBulkRequests: Int get() = if (conservative) 0 else bulkRequests

    fun candidatesForDirect(): List<String> =
        listOf(directHostOverride, lastGoodDirectHost)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        private const val P = "ssh_"

        /** 填你自己的：服务器设备白名单里给这台设备生成的 id 与密钥（留空 = 只用 SFTP）。 */
        const val HTTP_DEVICE_ID = ""
        const val HTTP_TOKEN = ""

        /** 内置默认值版本：改过默认值就 +1，老安装里存下来的旧值会被覆盖一次。 */
        const val DEFAULTS_VERSION = "2026-11-13-http-channel"

        fun load(context: Context): SshConfig {
            val sp = context.getSharedPreferences("journal", Context.MODE_PRIVATE)
            val d = SshConfig()
            // 内置默认值变更过（新增 HTTP 通道）→ 覆盖一次，别的用户设置不动
            if ((sp.getString("defaults_version", "") ?: "") != DEFAULTS_VERSION) {
                sp.edit {
                    putString(P + "http_base", d.httpBaseUrl)
                    putString(P + "http_device", d.httpDeviceId)
                    putString(P + "http_token", d.httpToken)
                    putString("defaults_version", DEFAULTS_VERSION)
                }
            }
            return SshConfig(
                user = sp.getString(P + "user", d.user) ?: d.user,
                port = sp.getInt(P + "port", d.port),
                tunnelHostName = sp.getString(P + "tunnel_host", d.tunnelHostName) ?: d.tunnelHostName,
                tunnelWsUrl = sp.getString(P + "tunnel_ws", d.tunnelWsUrl) ?: d.tunnelWsUrl,
                httpBaseUrl = sp.getString(P + "http_base", d.httpBaseUrl) ?: d.httpBaseUrl,
                httpDeviceId = sp.getString(P + "http_device", d.httpDeviceId) ?: d.httpDeviceId,
                httpToken = sp.getString(P + "http_token", d.httpToken) ?: d.httpToken,
                directHostOverride = sp.getString(P + "direct_override", "") ?: "",
                lastGoodDirectHost = sp.getString(P + "last_good_direct", "") ?: "",
                conservative = sp.getBoolean(P + "conservative", d.conservative),
                route = SshRoute.of(sp.getString(P + "route", d.route)).id,
                defaultRangeDays = sp.getInt(P + "default_range_days", d.defaultRangeDays),
                // archiveDir / todosIndexPath / gatewayPath 不从 prefs 读：
                // 它们是服务端那条流水线的约定，存盘只会让老安装卡在旧值（见字段注释）。
                // 调参三项同理。
            )
        }

        fun save(context: Context, config: SshConfig) {
            context.getSharedPreferences("journal", Context.MODE_PRIVATE).edit {
                putString(P + "user", config.user)
                putInt(P + "port", config.port)
                putString(P + "tunnel_host", config.tunnelHostName)
                putString(P + "tunnel_ws", config.tunnelWsUrl)
                putString(P + "http_base", config.httpBaseUrl)
                putString(P + "http_device", config.httpDeviceId)
                putString(P + "http_token", config.httpToken)
                putString(P + "direct_override", config.directHostOverride)
                putString(P + "last_good_direct", config.lastGoodDirectHost)
                putBoolean(P + "conservative", config.conservative)
                putString(P + "route", config.route)
                putInt(P + "default_range_days", config.defaultRangeDays)
            }
        }
    }
}
