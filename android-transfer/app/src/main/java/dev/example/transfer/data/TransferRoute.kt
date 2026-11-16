package dev.example.transfer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 走哪条路。两条都能到同一台服务器、同一把 key，区别只在**怎么到那儿**。
 *
 * 实测依据：
 *  - 校园网直连 IPv6：丢包 **0%**、RTT 30 ms、cwnd 38 → 最快（调参后 67 MB/s）；
 *  - 广电移动数据直连：丢包 **10–35%**、RTT 67–371 ms、cwnd 1、吞吐 0.1–0.5 Mbit/s → 不可用；
 *  - Cloudflare 隧道里的 SSH-over-WebSocket（2026-10-13）：1.06 MB/s，且 ≥64KB 的流会被
 *    QUIC 那一跳弄坏（sshd 报 MAC 校验失败）→ 只适合小操作；
 *  - Cloudflare 隧道里的 HTTP（2026-11-12，trans.example.com，隧道切到 http2 后）：
 *    上传 2.4–2.6 MB/s、下载 1.6–3.1 MB/s，sha256 双向一致 → **没 IPv6 时的正路**。
 */
enum class TransferRoute(val id: String, val label: String) {
    /** 按网络类型自动：WiFi/有线 → 先直连；直连不通 → HTTP；移动数据/VPN/未知 → 直接 HTTP */
    AUTO("auto", "自动"),

    /** 始终直连 IPv6（校园网下最快、最短，不经 Cloudflare） */
    DIRECT("direct", "直连"),

    /** 始终走 https://transfer.example.com 的 HTTP 通道（没有 IPv6 也能用） */
    HTTP("http", "HTTP"),

    /** 始终走 Cloudflare 隧道里的 SSH-over-WebSocket（只适合小操作；大文件会被 QUIC 弄坏） */
    TUNNEL("tunnel", "隧道");

    companion object {
        fun of(id: String?): TransferRoute = values().firstOrNull { it.id == id } ?: AUTO
    }
}

object RouteChooser {

    /**
     * 开局怎么挑：看**网络类型**，不看"连一次快不快"。
     *
     * 为什么不能靠计时探测：坏路上直连**照样能连上**（实测 20% 丢包下 SSH 会话照样建立），
     * 只是慢。按成败/时长探测会把最差的那条路当成"能用"而选中，
     * 于是出现"连上了然后慢死"这种最难查的状态。类型判据简单、可预测。
     */
    /**
     * 要不要**先**走 HTTP。
     *
     * 判据两条，任一条成立就先走 HTTP：
     *  - 不是 WiFi/有线（移动数据、VPN、未知）——这种网络直连实测 12–62 KB/s；
     *  - 本机根本没有公网 IPv6（家里 WiFi 常见）——那就别去撞直连白等 6 秒。
     */
    fun preferHttp(context: Context, route: TransferRoute): Boolean = when (route) {
        TransferRoute.HTTP -> true
        TransferRoute.DIRECT -> false
        TransferRoute.TUNNEL -> false
        TransferRoute.AUTO -> !onWifiOrEthernet(context) || !LocalNetwork.hasGlobalIpv6()
    }

    /** WiFi 或有线 → true；移动数据、VPN、拿不到信息 → false（保守选隧道）。 */
    fun onWifiOrEthernet(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps != null &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
    } catch (e: Exception) {
        false
    }
}
