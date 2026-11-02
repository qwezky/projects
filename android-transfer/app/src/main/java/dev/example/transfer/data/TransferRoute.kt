package dev.example.transfer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 走哪条路。两条都能到同一台服务器、同一把 key，区别只在**怎么到那儿**。
 *
 * 实测依据（2026-10-13）：
 *  - 校园网直连：丢包 **0%**、RTT 30 ms、cwnd 38 → 最快；
 *  - 广电移动数据直连：丢包 **10–35%**、RTT 67–371 ms、cwnd 1、吞吐 0.1–0.5 Mbit/s → 不可用；
 *  - Cloudflare 隧道（SSH 装进 WebSocket）：服务器侧 1.06 MB/s，任何网络都能用 → 兜底。
 */
enum class TransferRoute(val id: String, val label: String) {
    /** 按网络类型自动：WiFi/有线 → 直连；移动数据/VPN/未知 → 隧道 */
    AUTO("auto", "自动"),

    /** 始终直连 IPv6（校园网下最快、最短，不经 Cloudflare） */
    DIRECT("direct", "直连"),

    /** 始终走 Cloudflare 隧道（SSH 端到端加密，任何网络都能用） */
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
    fun preferTunnel(context: Context, route: TransferRoute): Boolean = when (route) {
        TransferRoute.TUNNEL -> true
        TransferRoute.DIRECT -> false
        TransferRoute.AUTO -> !onWifiOrEthernet(context)
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
