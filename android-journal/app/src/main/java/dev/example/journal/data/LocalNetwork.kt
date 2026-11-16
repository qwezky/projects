package dev.example.journal.data

import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * 本机到底有没有可用的公网 IPv6。
 *
 * 为什么需要它：`AUTO` 只看"是不是 WiFi"不够 —— 家里 WiFi 没有 IPv6 时也会先去撞直连，
 * 一次探测 3–6 秒、失败了再回 HTTP，用户看到的就是"点了没反应"。
 * 有 global IPv6 才值得试直连；没有就一步到位走 HTTP。
 */
internal object LocalNetwork {

    fun hasGlobalIpv6(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loop = runCatching { nif.isLoopback }.getOrDefault(true)
            up && !loop && nif.inetAddresses.toList().any { a ->
                a is Inet6Address &&
                    !a.isLoopbackAddress &&
                    !a.isLinkLocalAddress &&
                    !a.isAnyLocalAddress &&
                    !a.isMulticastAddress
            }
        }
    } catch (e: Exception) {
        false
    }
}
