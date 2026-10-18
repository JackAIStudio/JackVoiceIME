/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 热词归一化与请求级直传预算。
 *
 * 对齐桌面版 `jackvoice-agent-bridge/src/hotwords.rs`：
 *   - 火山识别形只保留文字和数字（`B roll` → `Broll`、`fun-asr` → `funasr`）；
 *   - 单个热词最多 32 个字符，最多 5000 条；
 *   - 没有配置云端热词表时，请求级直传按累计 100 字符截断。
 */
object HotwordSanitizer {
    const val MAX_HOTWORD_CHARS = 32
    const val MAX_HOTWORDS = 5000
    const val INLINE_MAX_CHARS = 100

    /** 去空格/标点，只留下识别形。 */
    fun normalize(text: String): String = text.trim().filter { Character.isLetterOrDigit(it) }

    fun isValid(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.isNotEmpty() &&
            normalized.codePointCount(0, normalized.length) <= MAX_HOTWORD_CHARS &&
            normalized.all { Character.isLetterOrDigit(it) }
    }

    /** 保序去重（忽略大小写），丢掉空词、超长词和非法字符。 */
    fun sanitize(words: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (word in words) {
            val cleaned = normalize(word)
            if (cleaned.isEmpty() || !isValid(cleaned)) continue
            if (!seen.add(cleaned.lowercase())) continue
            out += cleaned
            if (out.size >= MAX_HOTWORDS) break
        }
        return out
    }

    /** 请求级直传：按累计字符数截断，超预算就停（不是跳过继续塞）。 */
    fun inline(words: List<String>): List<String> {
        var total = 0
        val out = ArrayList<String>()
        for (word in sanitize(words)) {
            val chars = word.codePointCount(0, word.length)
            if (total + chars > INLINE_MAX_CHARS) break
            total += chars
            out += word
        }
        return out
    }
}
