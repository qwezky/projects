package dev.example.journal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 解析器的回归测试。
 *
 * 它是为服务端 `todos/index.jsonl` 专门写的（`json.dumps(..., ensure_ascii=False)` 一行一个对象），
 * 所以用例直接贴近真实数据形状：中文字段、数组、布尔、以及**依据里带引号和换行**的情况。
 */
class JsonTest {

    @Test
    fun `解析真实一行索引`() {
        // 这一行是从服务器 todos/index.jsonl 里逐字拷来的（数据本身已入 server-archive）
        val line = """{"id": 159, "task": "参加从读文献到收作业——福昕PDF应用实操培训", "start_date": "2026-10-14", """ +
            """"end_date": "2026-10-14", "source_name": "Screenshot_2026-10-14-21-33-56-26.jpg", """ +
            """"evidence": "10/14 从读文献到收作业——福昕PDF应用实操 15:00-16:00 腾讯会议-会议号：966-729-170", """ +
            """"votes": 6, "passes": 6, "variants": [], "evidence_grounded": true, "date_literal": true, """ +
            """"redated": "", "state": "cancelled", "closed_by": "text-1792051667093.txt（材料日期 2026-10-15）", """ +
            """"closed_at": "2026-10-15 16:48:57", "reopened_by": "", "reopened_at": "", """ +
            """"item_file": "items/20261014-参加从读文献到收作业——福昕PDF应用实操培训.md"}"""
        val obj = Json.parseObject(line)!!
        assertEquals(159, obj["id"].asInt())
        assertEquals("参加从读文献到收作业——福昕PDF应用实操培训", obj["task"].asText())
        assertEquals("cancelled", obj["state"].asText())
        assertEquals(6, obj["votes"].asInt())
        assertTrue(obj["variants"].asTextList().isEmpty())
        assertTrue(obj["evidence_grounded"].asBool(true))
        assertEquals(
            "items/20261014-参加从读文献到收作业——福昕PDF应用实操培训.md",
            obj["item_file"].asText(),
        )
    }

    @Test
    fun `字符串里的转义与代理项`() {
        val obj = Json.parseObject(
            """{"a": "引号 \" 反斜杠 \\ 换行 \n 制表 \t", "b": ["x", "y"], "emoji": "\ud83d\ude00", "empty": ""}""",
        )!!
        assertEquals("引号 \" 反斜杠 \\ 换行 \n 制表 \t", obj["a"].asText())
        assertEquals(listOf("x", "y"), obj["b"].asTextList())
        assertEquals("😀", obj["emoji"].asText())
        assertEquals("", obj["empty"].asText())
    }

    @Test
    fun `数字与布尔`() {
        val obj = Json.parseObject("""{"i": 6, "f": 1.5, "neg": -3, "t": true, "n": false, "nil": null}""")!!
        assertEquals(6, obj["i"].asInt())
        assertEquals(1, obj["f"].asInt())
        assertEquals(-3, obj["neg"].asInt())
        assertTrue(obj["t"].asBool())
        assertEquals(false, obj["n"].asBool(true))
        assertEquals("", obj["nil"].asText())
    }

    @Test
    fun `坏输入抛异常而不是返回半个对象`() {
        val bad = listOf(
            """{"a": }""",
            """{"a": 1""",
            """{"a": "没结尾}""",
            """{"a": 1} 后面还有""",
            """{不合法}""",
            """""",
        )
        for (text in bad) {
            assertNull("这份该解析失败：$text", runCatching { Json.parseObject(text) }.getOrNull())
        }
    }

    @Test
    fun `不是对象时 parseObject 返回 null`() {
        assertNull(Json.parseObject("[1,2,3]"))
        assertNull(Json.parseObject("\"就是个字符串\""))
    }
}
