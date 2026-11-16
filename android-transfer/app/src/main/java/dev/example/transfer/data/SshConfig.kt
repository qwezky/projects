package dev.example.transfer.data

import android.content.Context
import androidx.core.content.edit

/**
 * SSH/SFTP 上传的配置。持久化在 SharedPreferences，键名前缀 `ssh_`。
 *
 * 传输策略（实测决定）：
 *  - 直连 IPv6 优先：路径最短、**不经 Cloudflare**，实测能跑满且字节不会坏
 *  - Cloudflare WebSocket 隧道兜底：任何网络都能建立会话，**但实测批量传输不可靠**
 *    （≥64KB 就断，sshd 报 MAC 校验失败），所以只用来做小操作和地址发现
 */
data class SshConfig(
    val user: String = "user",
    val port: Int = 22,
    val remoteDir: String = "/home/user/phone-upload",
    /** 经隧道时的 SSH 主机名（只用于 JSch 记账，实际连的是隧道） */
    val tunnelHostName: String = "ssh.example.com",
    val tunnelWsUrl: String = "wss://ssh.example.com/",
    /**
     * HTTP 通道（2026-11-12 新增）—— 没有 IPv6 时走这条。
     *
     * 服务端：~/dsh-work/trans-http（cloudflared ingress trans.example.com → 127.0.0.1:8810）。
     * 隧道 2026-11-12 从 QUIC 切到 http2 之后实测：上传 2.4–2.6 MB/s、下载 1.6–3.1 MB/s，
     * 比"SSH 装进 WebSocket"快一个档，而且不会出现 MAC 校验失败。
     */
    val httpBaseUrl: String = "https://transfer.example.com",
    /**
     * 设备登记（2026-11-12）：服务器只放行登记过的设备。
     *  - [httpDeviceId] 由服务器的 `POST /api/admin/devices` 生成；
     *  - [httpToken] 是这台设备自己的密钥（服务器只存 sha256），不是管理密钥；
     *  - 想吊销：服务器上 `POST /api/admin/devices/<id>/disable`，或直接删设备。
     */
    val httpDeviceId: String = "",
    /** 这台设备的 HTTP 密钥。 */
    val httpToken: String = "",
    /** 手动填的直连地址（IPv6 字面量，如 2001:db8::1...）；留空则自动发现 */
    val directHostOverride: String = "",
    /** 上次成功直连的地址，用于下次快速直连 */
    val lastGoodDirectHost: String = "",
    /** 「从服务器下载」浏览的起始远端目录（和上传目标分开：上传钉在 phone-upload，浏览通常从家目录开始） */
    val remoteBrowseDir: String = "/home/user",
    /**
     * DSH 客户端的会话 token：`dsh web` 启动时打印的那串（进程级，重启 dsh web 会变）。
     * 浏览器 cookie 30 天有效，之后就不需要它了；没有它会看到 401。
     */
    val dshToken: String = "",
    /** DSH 客户端在本机监听的端口（WebView 打开的就是 http://127.0.0.1:这个端口） */
    val dshLocalPort: Int = 3080,
    /** SFTP 调参：窗口/包大小/流水线深度。0 = JSch 默认。
     *  **刻意不持久化** —— 界面上没有入口，存起来只会让改了默认值的老安装继续用旧值。 */
    val windowSizeMax: Int = 1 shl 20,
    val packetSize: Int = 64 * 1024,
    val bulkRequests: Int = 64,
    /**
     * 保守传输模式（要持久化，因为它是用户能看见的开关）。
     *
     * 打开后 window/packet/bulk 全部交回 JSch 默认值（因为上面三项恒为 0）。
     * 存在的理由：这条链路（手机移动网 → 校园网 IPv6）上反复出现
     * `message authentication code incorrect` —— sshd 收到的 SSH 报文校验不过，
     * 说明**客户端发出去的字节流在协议层就被弄坏了**。是运营商中间设备、还是
     * JSch 在大窗口+深流水线下的缓冲区复用问题，手上证据还不够分辨。
     * 所以给一个一键 A/B 的开关：出事时切保守模式，两条路各跑一次大文件，
     * 用"是否还报 MAC 错 + sha256 是否一致"来判断。
     */
    val conservative: Boolean = false,
    /**
     * 走哪条路：`auto` / `direct` / `tunnel`（取值见 [TransferRoute]）。
     *
     * 为什么需要它：2026-10-13 实测到"**直连能连上、但路径本身烂**"这种状态 ——
     * 广电移动数据下丢包 10–35%、cwnd 被压到 1、吞吐 0.1–0.5 Mbit/s，
     * 而同一台设备换到校园网立刻 0% 丢包、cwnd 38。这时"连不上才退隧道"的逻辑永远不触发，
     * 结果就是"连上了然后慢死"。所以既要能按网络类型先挑路，也要能在传的过程中发现太慢就换路。
     */
    val route: String = TransferRoute.AUTO.id,
) {
    val remoteDirForDisplay: String get() = remoteDir.trimEnd('/')

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

        /** 打包时替换成服务器上的真实 token（package-for-phone.sh 会检查是否还是这个占位值）。 */
        const val HTTP_TOKEN_PLACEHOLDER = "REPLACE_ME_TRANS_HTTP_TOKEN"

        /**
         * 内置默认值的版本号。改一次默认值（比如换设备密钥）+1，
         * 老安装里被 SharedPreferences 存下来的旧值就会被覆盖掉 ——
         * 否则 v2.7 存进去的旧 token 会一直压着 v2.8+ 的新默认值，表现为"怎么改都没用"。
         */
        const val DEFAULTS_VERSION = "2026-11-12-device-whitelist"

        fun load(context: Context): SshConfig {
            val sp = context.getSharedPreferences("transfer_demo", Context.MODE_PRIVATE)
            val d = SshConfig()
            // 内置默认值变更过（换设备密钥/换域名）→ 覆盖掉旧安装里存下来的那几项，别的用户设置不动
            val storedDefaults = sp.getString("defaults_version", "") ?: ""
            if (storedDefaults != DEFAULTS_VERSION) {
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
                remoteDir = sp.getString(P + "remote_dir", d.remoteDir) ?: d.remoteDir,
                tunnelHostName = sp.getString(P + "tunnel_host", d.tunnelHostName) ?: d.tunnelHostName,
                tunnelWsUrl = sp.getString(P + "tunnel_ws", d.tunnelWsUrl) ?: d.tunnelWsUrl,
                httpBaseUrl = sp.getString(P + "http_base", d.httpBaseUrl) ?: d.httpBaseUrl,
                httpDeviceId = sp.getString(P + "http_device", d.httpDeviceId) ?: d.httpDeviceId,
                httpToken = sp.getString(P + "http_token", d.httpToken) ?: d.httpToken,
                directHostOverride = sp.getString(P + "direct_override", "") ?: "",
                lastGoodDirectHost = sp.getString(P + "last_good_direct", "") ?: "",
                remoteBrowseDir = sp.getString(P + "browse_dir", d.remoteBrowseDir) ?: d.remoteBrowseDir,
                dshToken = sp.getString(P + "dsh_token", d.dshToken) ?: d.dshToken,
                dshLocalPort = sp.getInt(P + "dsh_local_port", d.dshLocalPort),
                conservative = sp.getBoolean(P + "conservative", d.conservative),
                route = TransferRoute.of(sp.getString(P + "route", d.route)).id,
                // 调参三项**不从 prefs 读**：见上面字段注释（避免老安装卡在旧值）
            )
        }

        fun save(context: Context, config: SshConfig) {
            context.getSharedPreferences("transfer_demo", Context.MODE_PRIVATE).edit {
                putString(P + "user", config.user)
                putInt(P + "port", config.port)
                putString(P + "remote_dir", config.remoteDir)
                putString(P + "tunnel_host", config.tunnelHostName)
                putString(P + "tunnel_ws", config.tunnelWsUrl)
                putString(P + "http_base", config.httpBaseUrl)
                putString(P + "http_device", config.httpDeviceId)
                putString(P + "http_token", config.httpToken)
                putString(P + "direct_override", config.directHostOverride)
                putString(P + "last_good_direct", config.lastGoodDirectHost)
                putString(P + "browse_dir", config.remoteBrowseDir)
                putString(P + "dsh_token", config.dshToken)
                putInt(P + "dsh_local_port", config.dshLocalPort)
                putBoolean(P + "conservative", config.conservative)
                putString(P + "route", config.route)
                // 调参三项不写盘（见字段注释），避免与代码里的默认值漂移
            }
        }
    }
}
