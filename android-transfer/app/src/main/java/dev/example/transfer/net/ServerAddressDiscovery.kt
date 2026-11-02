package dev.example.transfer.net

import java.net.InetAddress

/**
 * 公网 IPv6 发现 —— 用这个替代 DDNS。
 *
 * 理由（都实测过）：
 *  - `~/.cloudflared/cert.pem` 是 ARGO TUNNEL TOKEN，**不能**当 Cloudflare API Token
 *    用（只读试了一下：HTTP 400 / errors 6003 "Invalid format for Authorization header"），
 *    所以走 CF API 更新 AAAA 记录得**新落一个能改 DNS 的密钥**。
 *  - `/proc/net/if_inet6` 是 0444 全局可读，收窄到只能 SFTP 的 key 也能读（本项目 e2e 测试验证）。
 *
 * 于是：App 先经隧道连上（反正这条路任何网络都通），读一次这个文件拿到当前公网 IPv6，
 * 再直连 IPv6 做真正的传输。地址变了，下次自动读到新值。
 */
object ServerAddressDiscovery {

    private const val DEFAULT_PROC_PATH = "/proc/net/if_inet6"

    fun globalIpv6(client: SftpClient, procPath: String = DEFAULT_PROC_PATH): List<String> =
        parseGlobalIpv6(client.readText(procPath))

    /**
     * 每行格式：32 位十六进制地址 + ifindex + prefixlen + scope + flags + 网卡名
     * scope: `00` = global，`20` = link-local，`10` = loopback，`40` = site-local
     */
    internal fun parseGlobalIpv6(text: String): List<String> =
        text.lineSequence()
            .mapNotNull { line ->
                val f = line.trim().split(WHITESPACE)
                if (f.size < 6 || f[0].length != 32) return@mapNotNull null
                if (f[3] != "00") return@mapNotNull null
                val bytes = ByteArray(16) { i ->
                    f[0].substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
            }
            .filterNotNull()
            .distinct()
            .toList()

    private val WHITESPACE = Regex("\\s+")
}
