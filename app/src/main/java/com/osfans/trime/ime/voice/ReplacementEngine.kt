package com.osfans.trime.ime.voice

/**
 * 替换规则引擎。
 *
 * 逐字对齐 `plugin/src/libs/replace.ts`（已在 Xime 插件里实测 25/25 通过），
 * 语义与 JackVoice 一致：
 *   - 英文/数字按「整词边界」匹配（`cloud` 不命中 `cloudy`）
 *   - 中文按连续文字匹配
 *   - 最长匹配优先（`Agent Board` 先于 `Board`）
 *   - 已产出的结果**不会被后续规则二次误伤**（`Cloud → Claude` 之后 Claude 不再被改写）
 *   - `from == to` 视为短语锁，只保护不改写
 *
 * 实现用私有区占位符把「已产出的结果」锚住，全部规则跑完再还原。
 */
object ReplacementEngine {

    private const val PH_OPEN = '\uE000'
    private const val PH_CLOSE = '\uE001'

    data class Rule(val from: String, val to: String)

    data class Result(val text: String, val appliedCount: Int, val applied: List<Rule>)

    private val wholeWordRegex = Regex("^[0-9A-Za-z_][0-9A-Za-z_ ]*[0-9A-Za-z_]$|^[0-9A-Za-z_]$")

    private fun isWholeWordPattern(from: String): Boolean = wholeWordRegex.matches(from)

    /** 逐字符转义正则元字符，避免在 Kotlin 字符串里写 `$` 模板 */
    private val regexSpecials = charArrayOf(
        '.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\'
    )

    private fun escapeRegExp(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (ch in s) {
            if (regexSpecials.contains(ch)) sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    fun apply(text: String, rules: List<Rule>): Result {
        if (text.isEmpty() || rules.isEmpty()) return Result(text, 0, emptyList())

        val usable = rules
            .filter { it.from.isNotEmpty() }
            .sortedByDescending { it.from.length }

        var working = text
        val applied = mutableListOf<Rule>()

        for (rule in usable) {
            if (rule.from == rule.to) {
                // 短语锁：锚住正确写法，让后续规则无法命中
                val locked = replaceOnce(working, rule.from) { anchor(it) }
                if (locked != null) working = locked
                continue
            }
            val res = replaceOnce(working, rule.from) { anchor(rule.to) }
            if (res != null) {
                working = res
                applied += rule
            }
        }

        return Result(unanchor(working), applied.size, applied)
    }

    /** 只替换「未被锚定」的部分；无匹配返回 null */
    private fun replaceOnce(
        haystack: String,
        needle: String,
        makeReplacement: (String) -> String,
    ): String? {
        val wholeWord = isWholeWordPattern(needle)
        // 空格容忍：识别结果里空格数不稳定
        val body = escapeRegExp(needle).replace(" ", "\\s+")
        val pattern = if (wholeWord) "(?<![0-9A-Za-z_])$body(?![0-9A-Za-z_])" else body
        val re = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            return null
        }

        var matched = false
        val parts = splitByAnchors(haystack)
        val out = parts.joinToString("") { part ->
            if (part.anchored) {
                part.text
            } else {
                re.replace(part.text) { m ->
                    matched = true
                    makeReplacement(m.value)
                }
            }
        }
        return if (matched) out else null
    }

    private data class Part(val anchored: Boolean, val text: String)

    private fun splitByAnchors(s: String): List<Part> {
        val parts = mutableListOf<Part>()
        var i = 0
        while (i < s.length) {
            val open = s.indexOf(PH_OPEN, i)
            if (open == -1) {
                parts += Part(false, s.substring(i))
                break
            }
            if (open > i) parts += Part(false, s.substring(i, open))
            val close = s.indexOf(PH_CLOSE, open + 1)
            if (close == -1) {
                parts += Part(false, s.substring(open))
                break
            }
            parts += Part(true, s.substring(open, close + 1))
            i = close + 1
        }
        return parts
    }

    private fun anchor(s: String): String =
        PH_OPEN + s.replace(PH_OPEN.toString(), "").replace(PH_CLOSE.toString(), "") + PH_CLOSE

    private fun unanchor(s: String): String =
        s.replace(PH_OPEN.toString(), "").replace(PH_CLOSE.toString(), "")

    /**
     * 解析多行替换规则。每行一条，支持：
     *   from => to / from -> to / from=to / from（单独一行 = 短语锁）
     * `#` 或 `//` 开头的行忽略。
     */
    fun parseRules(raw: String): List<Rule> {
        val out = mutableListOf<Rule>()
        for (lineRaw in raw.split(Regex("\\r?\\n"))) {
            val line = lineRaw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val m = Regex("^(.*?)\\s*=>\\s*(.*)$").find(line)
                ?: Regex("^(.*?)\\s*->\\s*(.*)$").find(line)
                ?: Regex("^(.*?)\\s*=\\s*(.*)$").find(line)
            if (m != null) {
                val from = m.groupValues[1].trim()
                val to = m.groupValues[2].trim()
                if (from.isNotEmpty()) out += Rule(from, to.ifEmpty { from })
            } else {
                out += Rule(line, line)
            }
        }
        return out
    }

    /** 解析热词：每行一条，去空行与注释，保序去重 */
    fun parseHotwords(raw: String): List<String> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for (lineRaw in raw.split(Regex("\\r?\\n"))) {
            val t = lineRaw.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
            if (seen.add(t)) out += t
        }
        return out
    }
}
