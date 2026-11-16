package dev.example.journal.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 界面/日志用的小格式化工具（纯函数，方便单测与预览）。
 */
object Formatters {

    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 远端文件的 mtime（秒）→ 本机时区的 `MM-dd HH:mm`；拿不到（0）返回空串。 */
    fun epochSecondsToLocalText(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val zone = ZoneId.systemDefault()
        return TIME.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))
    }

    fun today(): LocalDate = LocalDate.now()

    /** 今天 + n 天（n 可以是负数），ISO 文本。 */
    fun todayPlus(days: Long): String = today().plusDays(days).toString()

    /** 把 ISO 日期转成 `M月d日` 这种短标签；解析不了就原样返回。 */
    fun shortDate(iso: String): String {
        val d = TodoIndex.validDate(iso) ?: return iso
        return "${d.monthValue}月${d.dayOfMonth}日"
    }

    /** 日期区间的一句人话：`10月15日 ~ 10月21日`（两头留空时用"不限"）。 */
    fun rangeText(from: String, to: String, activeOnly: Boolean): String {
        val left = if (from.isBlank()) "不限" else shortDate(from)
        val right = if (to.isBlank()) "不限" else shortDate(to)
        val tail = if (activeOnly) " · 只看未办结" else ""
        return "$left ~ $right$tail"
    }
}
