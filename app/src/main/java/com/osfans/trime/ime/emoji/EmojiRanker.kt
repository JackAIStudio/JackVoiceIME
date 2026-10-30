/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

/**
 * emoji 索引的搜索排序——**纯逻辑，不碰 Android**，好单测。
 *
 * 打分是三层，从硬到软：
 *
 * 1. **钉住（pin）**：`emoji-pins.ts` 里人工指定的「搜这个词就想要它」。
 *    CLDR 的主名和用户预期常对不上（😢 的主名叫「哭」，可大家搜「哭」要的是 😭），
 *    这一层直接压过其余所有信号。
 * 2. **档位**：命中主名（中/英/拼音）的排前面，只有关键词命中的排后面。
 *    主名是「一句话说这是什么」，关键词是「它的某个属性」，前者更准。
 * 3. **词条相关度**：完全相等 > 前缀 > 包含。
 *
 * 同档同词条时按索引下标排——下标就是 CLDR 的排列顺序，本来就是从常用到冷门。
 *
 * 最近使用另算：它不参与打分，而是直接把这几个 emoji 提到最前面（[EmojiSearch.recents]）。
 */
internal object EmojiRanker {
    /** 主名（中文名 / 英文名 / 拼音）命中。 */
    const val TIER_PRIMARY = 0

    /** 只有关键词命中。 */
    const val TIER_KEYWORD = 1

    /**
     * 一条命的分数。越小越靠前。
     *
     * @param pinned 这个词被人工纠偏指定给了 [index]，直接给最高优先。
     * @param isPrimary 该 emoji 的主名（任一语言、含拼音）与查询相关。
     * @param match 词条匹配档次（[MATCH_EXACT] / [MATCH_PREFIX] / [MATCH_CONTAINS]）。
     */
    fun score(
        pinned: Boolean,
        isPrimary: Boolean,
        match: Int,
    ): Int {
        if (pinned) return PINNED
        val tier = if (isPrimary) TIER_PRIMARY else TIER_KEYWORD
        return tier * 10_000 + match * 100
    }

    /** 人工纠偏钉住的，永远第一档。比任何自动分都小。 */
    const val PINNED = -1

    const val MATCH_EXACT = 0
    const val MATCH_PREFIX = 1
    const val MATCH_CONTAINS = 2

    /**
     * 词条 `term` 对查询 `query` 的匹配档次，不匹配返回 null。
     *
     * 查询已经归一化成小写了，调用方负责。
     */
    fun match(term: String, query: String): Int? = when {
        term == query -> MATCH_EXACT
        term.startsWith(query) -> MATCH_PREFIX
        term.contains(query) -> MATCH_CONTAINS
        else -> null
    }

    /** 主名是否与查询相关：相等、前缀、或包含。 */
    fun primaryMatches(names: List<String>?, query: String): Boolean {
        if (names.isNullOrEmpty()) return false
        return names.any { it == query || it.startsWith(query) || it.contains(query) }
    }
}
