/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import timber.log.Timber
import java.util.Locale

/**
 * 英文补全：英文跟中文不一样，不需要「确认」，所以输入框里不留组合下划线。
 * 字母直接上屏，候选由这份词表按前缀匹配给出，点中就把刚打的词干换成整词。
 *
 * 词表 `assets/en_words.txt` 是按词频排好的（google-10000-english，去掉了 1–2 字母缩写），
 * 所以「打 te 先出 test 再出 team」这种顺序由文件本身决定，不依赖 Rime 词典的权重。
 */
object EnglishCompleter {
    private const val ASSET = "en_words.txt"
    private const val MAX_LIMIT = 8

    @Volatile
    private var sorted: Array<String> = emptyArray()

    /** 词 → 词频名次（越小越常用）。 */
    @Volatile
    private var ranks: Map<String, Int> = emptyMap()

    @Volatile
    private var loading = false

    fun ensureLoaded(context: Context) {
        if (sorted.isNotEmpty() || loading) return
        loading = true
        runCatching {
            val list =
                context.assets.open(ASSET).bufferedReader().use { reader ->
                    reader.readLines().map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
                }
            ranks = list.withIndex().associate { (index, word) -> word to index }
            sorted = list.sorted().toTypedArray()
        }.onFailure { Timber.w(it, "英文词表加载失败") }
        loading = false
    }

    /** 输入 "te" → 词频序的 test / team / tech …（最多 [limit] 个）。 */
    fun complete(
        prefix: String,
        limit: Int = MAX_LIMIT,
    ): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val s = sorted
        val r = ranks
        if (s.isEmpty()) return emptyList()
        val p = prefix.lowercase(Locale.ROOT)

        var lo = 0
        var hi = s.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (s[mid] < p) lo = mid + 1 else hi = mid
        }

        val matches = ArrayList<String>()
        var i = lo
        while (i < s.size && s[i].startsWith(p)) {
            matches.add(s[i])
            i++
        }
        if (matches.isEmpty()) return emptyList()
        return matches
            .asSequence()
            .sortedBy { r[it] ?: Int.MAX_VALUE }
            .take(limit)
            .toList()
    }
}
