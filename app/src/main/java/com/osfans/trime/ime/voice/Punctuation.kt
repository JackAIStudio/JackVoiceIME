/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 语音标点三档。用户只关心三件事：
 *
 *  - [Full]：识别结果原样上屏；
 *  - [NoPeriod]：只去掉整段末尾那个句号，逗号 / 问号 / 感叹号都留；
 *  - [NoPunctuation]：标点换成空格（数字、邮箱、网址里的符号不动）。
 *
 * 三档都向服务端要标点（`enable_punc=true`），再由本地剪。只有拿到标点，
 * 才知道逗号原来落在哪里，也才能把它换成空格——所以「无标点」不能关 API 标点。
 */
enum class PunctuationMode(
    val id: String,
    val label: String,
    val hint: String,
) {
    Full("full", "全标点", "逗号、句号、问号都保留"),
    NoPeriod("no_period", "去句号", "只去掉结尾的句号，逗号保留"),
    NoPunctuation("none", "无标点", "标点换成空格"),
    ;

    fun next(): PunctuationMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromId(id: String?): PunctuationMode = entries.firstOrNull { it.id == id } ?: Full
    }
}

object PunctuationProcessor {
    fun apply(
        text: String,
        mode: PunctuationMode,
    ): String = when (mode) {
        PunctuationMode.Full -> text
        PunctuationMode.NoPeriod -> dropTrailingPeriod(text)
        PunctuationMode.NoPunctuation -> plainText(text)
    }

    /**
     * 只动整段最后一个字符，且必须是句号。中间的句号、问号、感叹号都不碰。
     * 结尾是引号/括号时不剪——那说明句号在引号里面。
     */
    private fun dropTrailingPeriod(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.length < 2) return trimmed
        val last = trimmed.last()
        if (last != '。' && last != '.' && last != '．') return trimmed
        // 英文省略号：最后一个点前面还是点，不剪。
        if (last == '.' && trimmed[trimmed.length - 2] == '.') return trimmed
        return trimmed.dropLast(1).trimEnd()
    }

    /**
     * 标点换成空格，引号/括号直接删。
     *
     * 先保护数字、时间、邮箱、网址——`3.5`、`10:30`、`a@b.com`、`v1.2` 里的符号
     * 不是断句标点，换了会把内容弄坏。
     */
    private fun plainText(text: String): String {
        if (text.isEmpty()) return text
        val (masked, tokens) = protect(text)
        var out = masked
        out = CJK_SEPARATOR.replace(out, " ")
        out = BRACKETS.replace(out, "")
        out = ASCII_SEPARATOR.replace(out, " ")
        out = ASCII_DOT.replace(out, " ")
        out = SPACES.replace(out, " ").trim()
        return restore(out, tokens).trim()
    }

    private fun protect(text: String): Pair<String, List<String>> {
        val out = StringBuilder(text.length)
        val tokens = mutableListOf<String>()
        var last = 0
        for (match in PROTECTED.findAll(text)) {
            out.append(text, last, match.range.first)
            out.append(PLACEHOLDER_START).append(tokens.size).append(PLACEHOLDER_END)
            tokens += match.value
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString() to tokens
    }

    private fun restore(
        text: String,
        tokens: List<String>,
    ): String = PLACEHOLDER.replace(text) { match ->
        val index = match.groupValues[1].toIntOrNull()
        if (index == null) match.value else tokens.getOrNull(index) ?: match.value
    }

    /** 网址 / 邮箱 / 小数、时间、千分位这类数字 token。 */
    private val PROTECTED =
        Regex(
            """https?://[^\s，。！？；、）】》」』”’"']+""" +
                """|www\.[^\s，。！？；、）】》」』”’"']+""" +
                """|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""" +
                """|\d+(?:[.,:：，．]\d+)+""",
        )

    private const val PLACEHOLDER_START = '\uE000'
    private const val PLACEHOLDER_END = '\uE001'

    private val PLACEHOLDER = Regex("\uE000(\\d+)\uE001")

    private val CJK_SEPARATOR = Regex("""[，、；：。！？…—–～·｜|]""")

    private val BRACKETS = Regex("""[“”‘’"（）【】《》〈〉「」『』〔〕\[\]{}<>]""")

    private val ASCII_SEPARATOR = Regex("""[,;:!?~]""")

    private val ASCII_DOT = Regex("""\.""")

    private val SPACES = Regex("""[\s\u3000]+""")
}
