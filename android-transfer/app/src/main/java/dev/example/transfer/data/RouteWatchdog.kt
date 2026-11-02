package dev.example.transfer.data

/**
 * "直连差就走隧道"的判据 —— **纯函数**，所以能单测，也能在传输过程中高频调用。
 *
 * 为什么判据是这三条（观察窗 / 最小样本 / 速率下限）：
 *  - 坏路上 SSH 会话照样能建立（实测 20% 丢包下握手都成功），所以**不能靠"连得上吗"判**；
 *    只有真搬数据才会暴露成"每秒几十 KB"。
 *  - 观察窗 6 秒 + 至少搬过 512 KB，才排除掉启动抖动（JSch 建通道、目录自查这些开销）。
 *  - 速率下限 120 KB/s：广电移动数据直连实测 12–62 KB/s、校园网直连 ≈350 KB/s、
 *    Cloudflare 隧道服务器侧 1.06 MB/s；120 落在"坏路之上、好路之下"。
 *
 * 两条都慢时返回 null（不换）—— 来回弹比慢更糟，交给原有的退避重试。
 */
internal object RouteWatchdog {

    const val SLOW_WINDOW_MS = 6_000L
    const val SLOW_MIN_BYTES = 512L * 1024
    const val SLOW_FLOOR_BPS = 120.0 * 1024

    /** 另一条路。 */
    fun other(route: TransferRoute): TransferRoute =
        if (route == TransferRoute.DIRECT) TransferRoute.TUNNEL else TransferRoute.DIRECT

    /**
     * 返回**该切到哪条路**；null = 继续用当前这条。
     *
     * @param slowRoutes 本次会话里已经被实测判为"太慢"的路，避免两条路来回弹
     */
    fun decide(
        route: TransferRoute,
        elapsedMs: Long,
        doneBytes: Long,
        slowRoutes: Set<TransferRoute>,
    ): TransferRoute? {
        if (elapsedMs < SLOW_WINDOW_MS) return null
        if (doneBytes < SLOW_MIN_BYTES) return null
        val rate = doneBytes * 1000.0 / elapsedMs
        if (rate >= SLOW_FLOOR_BPS) return null
        val next = other(route)
        return if (next in slowRoutes) null else next
    }

    /** 给界面/日志用的人类可读速率。 */
    fun kbPerSec(doneBytes: Long, elapsedMs: Long): Double =
        if (elapsedMs <= 0) 0.0 else doneBytes / 1024.0 * 1000.0 / elapsedMs
}
