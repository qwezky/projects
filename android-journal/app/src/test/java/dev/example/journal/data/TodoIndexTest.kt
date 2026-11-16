package dev.example.journal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待办解析与"某日到某日"筛选的回归测试。
 *
 * 这是这个 App 唯一有"业务规则"的地方，规则错了用户会看到错的日程 ——
 * 所以边界都写成用例：跨日重叠、无日期条目、空区间、只看未办结、坏行跳过。
 */
class TodoIndexTest {

    private fun item(
        task: String,
        start: String = "2026-10-15",
        end: String = start,
        state: String = TodoItem.STATE_ACTIVE,
        votes: Int = 6,
        passes: Int = 6,
        variants: List<String> = emptyList(),
    ) = TodoItem(
        task = task, startDate = start, endDate = end, state = state,
        votes = votes, passes = passes, variants = variants,
    )

    private val cross = item("报名FPV课程", start = "2026-10-14", end = "2026-10-15")
    private val day15 = item("参加MATLAB培训", start = "2026-10-15")
    private val day20 = item("参加院系杯", start = "2026-10-20")
    private val done = item("提交课表", start = "2026-10-16", state = TodoItem.STATE_DONE)
    private val cancelled = item("参加福昕培训", start = "2026-10-14", state = TodoItem.STATE_CANCELLED)
    private val noDate = item("没有日期的通知", start = "", end = "")

    private val all = listOf(cross, day15, day20, done, cancelled, noDate)

    @Test
    fun `区间重叠 —— 跨日事项在结束那天也要出现`() {
        val got = TodoIndex.inRange(all, "2026-10-15", "2026-10-15").map { it.task }
        // 10-14 → 10-15 的报名事项，15 号还在做，必须出现
        assertTrue("跨日事项必须命中它的结束日", "报名FPV课程" in got)
        assertTrue("当天事项当然出现", "参加MATLAB培训" in got)
        assertFalse("区间外的不能出现", "参加院系杯" in got)
    }

    @Test
    fun `两头都可以不设限`() {
        assertEquals(all.size - 1, TodoIndex.inRange(all, "", "2026-10-31").size) // 无日期那条被排除
        assertEquals(all.size - 1, TodoIndex.inRange(all, "2026-10-01", "").size)
        assertEquals(all.size, TodoIndex.inRange(all, "", "").size) // 不设区间 = 全部（含无日期）
    }

    @Test
    fun `设了区间就要排除没有日期的条目`() {
        val got = TodoIndex.inRange(all, "2026-10-01", "2026-10-31").map { it.task }
        assertFalse("没日期的条目不该被塞进某个区间里", "没有日期的通知" in got)
    }

    @Test
    fun `只看未办结`() {
        val got = TodoIndex.inRange(all, "2026-10-01", "2026-10-31", activeOnly = true).map { it.task }
        assertFalse("已完成的不算", "提交课表" in got)
        assertFalse("已取消的不算", "参加福昕培训" in got)
        assertTrue("未办结的还在", "参加MATLAB培训" in got)
    }

    @Test
    fun `按开始日期排序、跨日的排在当天前面`() {
        val got = TodoIndex.inRange(all, "2026-10-14", "2026-10-20").map { it.startDate }
        assertEquals(got.sorted(), got)
        assertEquals("2026-10-14", got.first())
    }

    @Test
    fun `分组保持时间顺序、同一天归一组`() {
        val groups = TodoIndex.groupByDate(TodoIndex.inRange(all, "2026-10-14", "2026-10-20"))
        assertEquals(listOf("2026-10-14", "2026-10-15", "2026-10-16", "2026-10-20"), groups.map { it.first })
        assertEquals(2, groups.first { it.first == "2026-10-14" }.second.size)
    }

    @Test
    fun `汇总口径与服务端一致`() {
        val text = TodoIndex.summary(TodoIndex.inRange(all, "2026-10-01", "2026-10-31"))
        assertTrue(text.startsWith("共 5 条"))
        assertTrue(text.contains("已办结 2 条"))
        assertTrue(text.contains("✅ 1"))
        assertTrue(text.contains("❌ 1"))
    }

    @Test
    fun `状态标记与不稳信号`() {
        assertEquals("✅", done.stateMark)
        assertEquals("❌", cancelled.stateMark)
        assertEquals("☐", day15.stateMark)

        val shaky = item("参加新生杯", votes = 3, passes = 6, variants = listOf("参加新生杯", "参加新生杯（5米/10米）"))
            .copy(evidenceGrounded = false, redated = "参加课程：2026-10-15→2026-10-18（依据「本周日」换算）")
        val w = shaky.warnings.joinToString("；")
        assertTrue(w.contains("3/6"))
        assertTrue(w.contains("依据与材料没完全对上"))
        assertTrue(w.contains("多路说法不一致"))
        assertTrue(w.contains("日期是换算来的"))
        assertEquals(emptyList<String>(), day15.copy(passes = 0).warnings)
    }

    @Test
    fun `坏行跳过、好行保留`() {
        val text = """
            {"id": 1, "task": "好的", "start_date": "2026-10-15", "end_date": "2026-10-15"}
            这一行不是 JSON
            {"id": 2, "start_date": "2026-10-16", "end_date": "2026-10-16"}
            {"id": 3, "task": "也好的", "start_date": "2026-10-17", "end_date": "2026-10-17"}

        """.trimIndent()
        val parsed = TodoIndex.parse(text)
        assertEquals(listOf("好的", "也好的"), parsed.items.map { it.task })
        assertEquals("非 JSON 行 + 没 task 的行都算跳过", 2, parsed.skippedLines)
    }

    @Test
    fun `缺字段的行按默认值兜底`() {
        val parsed = TodoIndex.parse("""{"task": "只有任务名"}""")
        val it = parsed.items.single()
        assertEquals("只有任务名", it.task)
        assertEquals(TodoItem.STATE_ACTIVE, it.state)
        assertEquals(0, it.votes)
        assertEquals("", it.evidence)
        assertEquals(null, it.itemFile)
        assertFalse(it.isSettled)
    }

    @Test
    fun `空 state 当作未办结`() {
        val parsed = TodoIndex.parse("""{"task": "x", "start_date": "2026-10-15", "end_date": "2026-10-15", "state": ""}""")
        assertEquals(TodoItem.STATE_ACTIVE, parsed.items.single().state)
        assertEquals("☐", parsed.items.single().stateMark)
    }

    @Test
    fun `复制出来的文本与服务端 CLI 同形`() {
        val items = TodoIndex.inRange(all, "2026-10-14", "2026-10-16")
        val text = TodoIndex.renderText(items, "2026-10-14", "2026-10-16")
        assertTrue(text.startsWith("待办事项（2026-10-14 ~ 2026-10-16）："))
        assertTrue(text.contains("── 2026-10-14 ──"))
        assertTrue(text.contains("❌ 参加福昕培训"))
        assertTrue(text.contains("（已取消）"))
        assertTrue(text.contains("✅ 提交课表"))
        assertTrue(text.contains("✅ 提交课表（已完成）"))
        assertTrue(text.contains("☐ 报名FPV课程（2026-10-14 → 2026-10-15）"))
    }

    @Test
    fun `没有条目时 renderText 说没有`() {
        val text = TodoIndex.renderText(emptyList(), "2026-01-01", "2026-01-02")
        assertTrue(text.contains("共 0 条"))
        assertTrue(text.contains("（没有）"))
    }

    @Test
    fun `日期校验`() {
        assertEquals(2026, TodoIndex.validDate("2026-10-15")!!.year)
        assertEquals(null, TodoIndex.validDate(""))
        assertEquals(null, TodoIndex.validDate("10/15"))
        assertEquals(null, TodoIndex.validDate("2026-13-45"))
    }
}
