package dev.example.journal.data

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 一条待办事项 —— 对应服务端 `todos/index.jsonl` 里的一行。
 *
 * 字段名与服务端 `archiver/library.py::_row_json()` 一一对应（那边是权威定义）。
 * 这里刻意全部给默认值：索引是老版本服务端写的也不该崩，缺字段就退化显示。
 */
data class TodoItem(
    val id: Int = -1,
    val task: String = "",
    val startDate: String = "",
    val endDate: String = "",
    /** `active` / `done` / `cancelled`（服务端还可能给空串，一律当 active 处理） */
    val state: String = STATE_ACTIVE,
    /** 抽取依据：来自材料原文的那一段，能人工核对 */
    val evidence: String = "",
    val sourceName: String = "",
    val recordPath: String = "",
    /** 6 路抽取里有几路给出了这一条 / 一共跑了几路 */
    val votes: Int = 0,
    val passes: Int = 0,
    /** 同一件事的其它说法（多路不一致时才有） */
    val variants: List<String> = emptyList(),
    val evidenceGrounded: Boolean = true,
    val dateLiteral: Boolean = true,
    /** 非空 = 日期是从「本周日」这类相对说法换算来的，换算过程记在这里 */
    val redated: String = "",
    val closedBy: String = "",
    val closedAt: String = "",
    val reopenedBy: String = "",
    val reopenedAt: String = "",
    val itemFile: String? = null,
) {

    val isDone: Boolean get() = state == STATE_DONE
    val isCancelled: Boolean get() = state == STATE_CANCELLED

    /** 已办结（完成或取消）——对应服务端 `inbox todos` 里"其中已办结 N 条"那一档。 */
    val isSettled: Boolean get() = isDone || isCancelled

    /** 列表前的标记：✅ 已完成 / ❌ 已取消 / ☐ 还没办结。 */
    val stateMark: String
        get() = when {
            isDone -> "✅"
            isCancelled -> "❌"
            else -> "☐"
        }

    /** 事后又被「恢复举办」之类材料改回未办结的，标一下（服务端记在 reopened_by）。 */
    val wasReopened: Boolean get() = reopenedBy.isNotBlank()

    /** 跨日事项才显示区间，当天的不重复写一遍日期。 */
    val dateSpanText: String
        get() = if (startDate.isNotEmpty() && endDate.isNotEmpty() && startDate != endDate) {
            "$startDate → $endDate"
        } else {
            ""
        }

    /**
     * 这条"不稳"的信号（与服务端事项文件里那几行警告同源）：
     * 票数不满、依据没和材料对上、多路说法不一致、日期是换算来的。
     * 界面上把这些如实摆出来，而不是悄悄替用户消掉分歧。
     */
    val warnings: List<String>
        get() = buildList {
            if (passes > 0 && votes < passes) add("多路抽取只有 $votes/$passes 路给出这条")
            if (!evidenceGrounded) add("依据与材料没完全对上")
            if (variants.size > 1) add("多路说法不一致：" + variants.joinToString("、"))
            if (redated.isNotBlank()) add("日期是换算来的：$redated")
        }

    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_DONE = "done"
        const val STATE_CANCELLED = "cancelled"
    }
}

/**
 * `todos/index.jsonl` 的解析与"某日到某日"的筛选 —— **纯函数，没有 Android 依赖**，
 * 所以这些边界都能在电脑上用单元测试钉住（见 TodoIndexTest）。
 *
 * 服务端那个文件由 `archiver/library.py::rebuild_todos()` 生成：一行一个 JSON 对象，
 * 全量重写。所以 App 这边不需要增量、不需要写，只读一次、在本地筛。
 */
object TodoIndex {

    /** 解析一行一条的 JSONL：坏行跳过并计数（不因为一行坏了就整份读不出来）。 */
    fun parse(text: String): Parsed {
        val items = ArrayList<TodoItem>()
        var skipped = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val obj = runCatching { Json.parseObject(line) }.getOrNull()
            if (obj == null) {
                skipped++
                continue
            }
            val item = toItem(obj)
            if (item.task.isBlank()) {
                skipped++
                continue
            }
            items += item
        }
        return Parsed(items, skipped)
    }

    data class Parsed(val items: List<TodoItem>, val skippedLines: Int)

    private fun toItem(o: Map<String, Any?>): TodoItem {
        val start = o["start_date"].asText()
        val end = o["end_date"].asText()
        return TodoItem(
            id = o["id"].asInt(-1),
            task = o["task"].asText(),
            startDate = start,
            endDate = end,
            state = o["state"].asText().ifBlank { TodoItem.STATE_ACTIVE },
            evidence = o["evidence"].asText(),
            sourceName = o["source_name"].asText(),
            recordPath = o["record_path"].asText(),
            votes = o["votes"].asInt(0),
            passes = o["passes"].asInt(0),
            variants = o["variants"].asTextList(),
            evidenceGrounded = o["evidence_grounded"].asBool(true),
            dateLiteral = o["date_literal"].asBool(true),
            redated = o["redated"].asText(),
            closedBy = o["closed_by"].asText(),
            closedAt = o["closed_at"].asText(),
            reopenedBy = o["reopened_by"].asText(),
            reopenedAt = o["reopened_at"].asText(),
            itemFile = o["item_file"].asText().takeIf { it.isNotBlank() },
        )
    }

    /**
     * 取某日到某日的待办（**区间重叠**，不是只取区间内"开始"的那些）：
     * 一条 `10-14 → 10-15` 的事项，在查 `10-15 ~ 10-18` 时必须出现 —— 它 15 号还要做。
     *
     * [from] / [to] 允许空串 = 那一头不设限；两个都空 = 全部。
     * 日期无效（服务端没抽出日期、或格式不认识）的条目**在设了区间时会被排除**，
     * 这是有意的：宁可少显示，也不要把没日期的条目塞进"某一天"里误导人。
     */
    fun inRange(
        items: List<TodoItem>,
        from: String = "",
        to: String = "",
        activeOnly: Boolean = false,
    ): List<TodoItem> {
        val lo = validDate(from)
        val hi = validDate(to)
        val filtered = items.filter { item ->
            if (activeOnly && item.isSettled) return@filter false
            if (lo == null && hi == null) return@filter true
            val s = validDate(item.startDate) ?: validDate(item.endDate) ?: return@filter false
            val e = validDate(item.endDate) ?: s
            (lo == null || !e.isBefore(lo)) && (hi == null || !s.isAfter(hi))
        }
        return filtered.sortedWith(
            compareBy({ validDate(it.startDate) ?: validDate(it.endDate) ?: LocalDate.MAX }, { it.task }),
        )
    }

    /** 按开始日期分组（同一件事跨日时挂在开始那天），保持时间顺序。 */
    fun groupByDate(items: List<TodoItem>): List<Pair<String, List<TodoItem>>> {
        val groups = LinkedHashMap<String, MutableList<TodoItem>>()
        for (item in items) {
            val key = item.startDate.ifBlank { item.endDate }.ifBlank { "（没日期）" }
            groups.getOrPut(key) { ArrayList() }.add(item)
        }
        return groups.map { (k, v) -> k to v.toList() }
    }

    /** 一句话汇总，口径对齐服务端 `inbox todos`（共 N 条，其中已办结 M 条（✅x / ❌y））。 */
    fun summary(items: List<TodoItem>): String {
        val done = items.count { it.isDone }
        val cancelled = items.count { it.isCancelled }
        val settled = done + cancelled
        val tail = if (settled > 0) "，其中已办结 $settled 条（✅ $done / ❌ $cancelled）" else ""
        return "共 ${items.size} 条$tail"
    }

    /** ISO 日期是否可用；不可用返回 null。 */
    fun validDate(text: String): LocalDate? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return try {
            LocalDate.parse(t)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /**
     * 把一批待办渲染成纯文本（"复制"按钮用）。
     *
     * 口径对齐服务端 `bin/inbox todos`：先一行汇总，再按开始日期分组、
     * 每条带 ✅/❌ 标记，跨日的写区间。这样手机上复制出来的东西，
     * 和服务端终端里看到的是同一套形状，不用二次翻译。
     */
    fun renderText(
        items: List<TodoItem>,
        from: String = "",
        to: String = "",
        activeOnly: Boolean = false,
    ): String = buildString {
        val title = when {
            from.isBlank() && to.isBlank() -> "全部"
            else -> "${from.ifBlank { "不限" }} ~ ${to.ifBlank { "不限" }}"
        }
        append("待办事项（$title")
        if (activeOnly) append("，只看未办结")
        append("）：")
        append(summary(items))
        append('\n')
        if (items.isEmpty()) {
            append("（没有）")
            return@buildString
        }
        for ((date, group) in groupByDate(items)) {
            append("\n── $date ──\n")
            for (item in group) {
                append("  ").append(item.stateMark).append(' ').append(item.task)
                if (item.dateSpanText.isNotEmpty()) append("（${item.dateSpanText}）")
                if (item.isDone) append("（已完成）")
                if (item.isCancelled) append("（已取消）")
                if (item.wasReopened) append("（后来又被恢复）")
                append('\n')
            }
        }
    }
}
