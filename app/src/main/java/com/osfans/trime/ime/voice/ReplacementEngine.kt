/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 终稿替换引擎，逐条对齐桌面版 `hotwords.rs` 的 `apply_replacements`。
 *
 * 语义：
 *   - 英文字母和数字只在整词边界上替换（`Cloud` 不会钻进 `iCloud`）；
 *   - 中文按连续文字匹配；
 *   - 单次从左到右扫描，同一位置取最长的规则；
 *   - 已经产出的结果不会在后续位置被短规则二次改写；
 *   - `from == to` 是短语锁，只消耗匹配、不改写。
 *
 * 注意：这里不做空格容忍——桌面版要求空格、标点和拼写一致。
 */
object ReplacementEngine {
    data class Rule(val from: String, val to: String)

    data class Result(val text: String, val appliedCount: Int, val applied: List<Rule>)

    private data class Pattern(val chars: CharArray, val rule: Rule)

    /**
     * 清洗用户规则：去首尾空白、丢弃空项、忽略大小写合并同一来源、长来源优先。
     * 同一来源后写的目标覆盖先写的目标，展示时保留第一次录入的 from 写法。
     */
    fun sanitize(rules: List<Rule>): List<Rule> {
        val out = ArrayList<Rule>()
        val indexes = HashMap<String, Int>()
        for (rule in rules) {
            val from = rule.from.trim()
            val to = rule.to.trim()
            if (from.isEmpty() || to.isEmpty()) continue
            val key = from.lowercase()
            val index = indexes[key]
            if (index != null) {
                out[index] = out[index].copy(to = to)
                continue
            }
            indexes[key] = out.size
            out += Rule(from, to)
        }
        return out.sortedByDescending { it.from.length }
    }

    fun apply(
        text: String,
        rules: List<Rule>,
    ): Result {
        if (text.isEmpty() || rules.isEmpty()) return Result(text, 0, emptyList())

        val patterns =
            sanitize(rules).mapNotNull { rule ->
                val lower = rule.from.lowercase()
                if (lower.isEmpty()) null else Pattern(lower.toCharArray(), rule)
            }
        if (patterns.isEmpty()) return Result(text, 0, emptyList())

        val chars = text.toCharArray()
        val out = StringBuilder(text.length)
        val applied = ArrayList<Rule>()
        var i = 0
        while (i < chars.size) {
            var bestLen = 0
            var best: Rule? = null
            for (pattern in patterns) {
                val length = pattern.chars.size
                if (length <= bestLen || i + length > chars.size) continue
                var matched = true
                for (offset in 0 until length) {
                    if (chars[i + offset].lowercaseChar() != pattern.chars[offset]) {
                        matched = false
                        break
                    }
                }
                if (matched && latinMatchIsBounded(chars, i, i + length)) {
                    bestLen = length
                    best = pattern.rule
                }
            }
            val chosen = best
            if (chosen == null) {
                out.append(chars[i])
                i += 1
            } else {
                out.append(chosen.to)
                i += bestLen
                if (applied.none { it.from == chosen.from && it.to == chosen.to }) {
                    applied += chosen
                }
            }
        }
        return Result(out.toString(), applied.size, applied)
    }

    /**
     * 拉丁字母/数字按整词边界；规则两端是中文等非 ASCII 字符时不加边界。
     * 下划线、连字符、空格、标点都算边界，和桌面版一致。
     */
    private fun latinMatchIsBounded(
        chars: CharArray,
        start: Int,
        end: Int,
    ): Boolean {
        if (start >= end || end > chars.size) return false
        val startsWithWord = chars[start].isAsciiWord()
        val endsWithWord = chars[end - 1].isAsciiWord()
        if (startsWithWord && start > 0 && chars[start - 1].isAsciiWord()) return false
        if (endsWithWord && end < chars.size && chars[end].isAsciiWord()) return false
        return true
    }

    private fun Char.isAsciiWord(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
