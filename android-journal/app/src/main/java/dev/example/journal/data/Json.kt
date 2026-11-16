package dev.example.journal.data

/**
 * 极小的 JSON 解析器（只为本项目那一个文件服务）。
 *
 * 为什么不用 org.json / kotlinx.serialization：
 *  - `org.json` 是 Android 的桩实现，**JVM 单元测试里所有方法都返回默认值**（isReturnDefaultValues
 *    一开就静默变成 null），那样"日期区间筛选"这类逻辑就没法在电脑上验证；
 *  - `kotlinx-serialization-json` 是新增依赖（本机 Gradle 缓存里只有 core，没有 json）。
 *
 * 要解析的文件是服务端 `archiver/library.py` 用 `json.dumps(row, ensure_ascii=False)` 写的
 * 一行一个对象（JSONL）。字段是扁平的，但**字符串里可能有引号、换行、反斜杠**，
 * 所以不能拿正则去劈 —— 老老实实做一个递归下降解析器，转义按 RFC 8259 处理
 * （含 `\uXXXX`，以及成对的代理项）。
 *
 * 解析失败一律抛 [JsonException]，调用方（[TodoIndex]）按"跳过这一行"处理。
 */
internal object Json {

    class JsonException(message: String) : Exception(message)

    /** 解析一个完整的 JSON 值；结尾还有多余内容会报错（一行必须是完整的一个值）。 */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("第 ${p.pos} 个字符之后还有多余内容")
        return v
    }

    /** 解析成一个对象；不是对象就返回 null。 */
    fun parseObject(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    // ---------------------------------------------------------------- 解析器

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun value(): Any? {
            skipWs()
            if (atEnd()) throw JsonException("内容意外结束")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("位置 $pos 出现意外字符 '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("位置 $pos 不是合法字面量（期望 $word）")
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                out[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw JsonException("位置 $pos 期望 ',' 或 '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                out += value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw JsonException("位置 $pos 期望 ',' 或 ']'")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("字符串没有收尾的引号")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(escape())
                    else -> sb.append(c)
                }
            }
        }

        private fun escape(): String {
            if (atEnd()) throw JsonException("反斜杠之后内容结束")
            return when (val e = s[pos++]) {
                '"' -> "\""
                '\\' -> "\\"
                '/' -> "/"
                'b' -> "\b"
                'f' -> "\u000C"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> unicode()
                else -> throw JsonException("不认识的转义 \\$e")
            }
        }

        /** `\uXXXX`；成对的代理项要拼成一个码点（emoji 之类）。 */
        private fun unicode(): String {
            val hi = hex4()
            if (Character.isHighSurrogate(hi) && pos + 1 < s.length && s[pos] == '\\' && s[pos + 1] == 'u') {
                val save = pos
                pos += 2
                val lo = hex4()
                if (Character.isLowSurrogate(lo)) {
                    return String(Character.toChars(Character.toCodePoint(hi, lo)))
                }
                pos = save // 不是成对的低代理项：退回去，只当它是一个独立的高代理项
            }
            return hi.toString()
        }

        private fun hex4(): Char {
            if (pos + 4 > s.length) throw JsonException("\\u 之后不足 4 位十六进制")
            var v = 0
            repeat(4) {
                val d = Character.digit(s[pos++], 16)
                if (d < 0) throw JsonException("\\u 之后不是十六进制")
                v = v * 16 + d
            }
            return v.toChar()
        }

        private fun num(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (s[pos] in '0'..'9' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            val text = s.substring(start, pos)
            return text.toDoubleOrNull() ?: throw JsonException("不是合法数字：$text")
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]

        private fun expect(c: Char) {
            if (atEnd() || s[pos] != c) throw JsonException("位置 $pos 期望 '$c'")
            pos++
        }
    }
}

// ---------------------------------------------------------------- 取值助手
//
// 刻意放在**顶层**而不是 object Json 里面：声明在 object 内部的扩展函数属于该 object 的成员，
// 出了那个作用域就调不到（写 `obj["x"].asText()` 会直接"Unresolved reference"）。
// 顶层 internal 扩展在同一个包内的调用点都可用，也不需要 import。

/** 字符串；数字/布尔按文本给出，null 给空串。 */
internal fun Any?.asText(): String = when (this) {
    null -> ""
    is String -> this
    is Double -> if (this == Math.floor(this) && !isInfinite()) toLong().toString() else toString()
    else -> toString()
}

internal fun Any?.asInt(default: Int = 0): Int = (this as? Double)?.toInt() ?: default

internal fun Any?.asBool(default: Boolean = true): Boolean = this as? Boolean ?: default

internal fun Any?.asTextList(): List<String> =
    (this as? List<*>)?.mapNotNull { it?.asText()?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
