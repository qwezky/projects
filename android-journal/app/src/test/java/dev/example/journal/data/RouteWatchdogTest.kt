package dev.example.journal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "直连差就走隧道"判据的单元测试。
 *
 * 为什么值得钉住：这个判据一旦判错，后果是**静默**的 ——
 * 判太松（阈值偏高/窗口偏短）会把好链路误切到隧道，用户只觉得"怎么绕远路了"；
 * 判太紧则永远不触发，于是又回到"连上了然后慢死"的老状态，现象和网络差一模一样，查不出来。
 */
class RouteWatchdogTest {

    private val floor = RouteWatchdog.SLOW_FLOOR_BPS

    @Test
    fun 速率正常时不换路() {
        // 6 秒搬 3 MB ≈ 500 KB/s（校园网直连的量级）
        assertNull(RouteWatchdog.decide(SshRoute.DIRECT, 6_000, 3L * 1024 * 1024, emptySet()))
    }

    @Test
    fun 观察窗内再慢也不换() {
        // 只跑了 3 秒、速率极低 —— 可能只是启动抖动
        assertNull(RouteWatchdog.decide(SshRoute.DIRECT, 3_000, 1024, emptySet()))
    }

    @Test
    fun 样本太小不换() {
        // 过了 6 秒但只搬了 100 KB（大文件还没开始搬）
        assertNull(RouteWatchdog.decide(SshRoute.DIRECT, 10_000, 100L * 1024, emptySet()))
    }

    @Test
    fun 直连实测太慢时切HTTP() {
        // 10 秒搬 600 KB = 60 KB/s（广电移动数据直连的实测区间）
        assertEquals(
            SshRoute.HTTP,
            RouteWatchdog.decide(SshRoute.DIRECT, 10_000, 600L * 1024, emptySet()),
        )
    }

    @Test
    fun HTTP实测太慢时切回直连() {
        assertEquals(
            SshRoute.DIRECT,
            RouteWatchdog.decide(SshRoute.HTTP, 10_000, 600L * 1024, emptySet()),
        )
    }

    @Test
    fun 两条都实测过太慢就不再换() {
        // 直连已判慢、隧道也判慢 → 不来回弹
        assertNull(
            RouteWatchdog.decide(
                SshRoute.DIRECT, 10_000, 600L * 1024,
                setOf(SshRoute.DIRECT, SshRoute.HTTP),
            ),
        )
    }

    @Test
    fun 刚好压线不换_严格小于才换() {
        // 6 秒搬 720 KB = 正好 120 KB/s = 下限 → 不换（判据是严格小于）
        val bytesAtFloor = (floor / 1024.0 * 6.0).toLong() * 1024
        assertNull(RouteWatchdog.decide(SshRoute.DIRECT, 6_000, bytesAtFloor, emptySet()))
    }

    @Test
    fun 另一条路就是另一条() {
        assertEquals(SshRoute.HTTP, RouteWatchdog.other(SshRoute.DIRECT))
        assertEquals(SshRoute.DIRECT, RouteWatchdog.other(SshRoute.HTTP))
        assertEquals(SshRoute.DIRECT, RouteWatchdog.other(SshRoute.TUNNEL))
    }

    @Test
    fun 解析_未知值回落到自动() {
        assertEquals(SshRoute.AUTO, SshRoute.of(null))
        assertEquals(SshRoute.AUTO, SshRoute.of("随便"))
        assertEquals(SshRoute.DIRECT, SshRoute.of("direct"))
        assertEquals(SshRoute.TUNNEL, SshRoute.of("tunnel"))
        assertEquals(SshRoute.HTTP, SshRoute.of("http"))
    }
}
