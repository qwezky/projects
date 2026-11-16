package dev.example.transfer.data

import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * 本机到底有没有可用的公网 IPv6。
 *
 * 为什么需要它：`AUTO` 以前只看"是不是 WiFi"，于是家里的 WiFi（没有 IPv6）也会先去撞直连——
 * 一次探测 6 秒、失败了再回 HTTP，用户看到的就是"点了没反应，十几秒后才通"。
 * 有 global IPv6 才值得去试直连；没有就一步到位走 HTTP。
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
