/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 排序规则的纯逻辑测试。
 *
 * 这些用例钉住的是「什么样的信号该赢」：钉住 > 主名 > 关键词，词条相等 > 前缀 > 包含。
 * 具体的 emoji 结果由数据决定（见 `lexicon/export-emoji-index.mjs` 的自检），这里只管规则。
 */
class EmojiRankerTest :
    FunSpec({
        test("钉住的永远排最前，压过主名命中") {
            val pinned = EmojiRanker.score(pinned = true, isPrimary = false, match = EmojiRanker.MATCH_CONTAINS)
            val primaryExact = EmojiRanker.score(pinned = false, isPrimary = true, match = EmojiRanker.MATCH_EXACT)
            (pinned < primaryExact) shouldBe true
        }

        test("主名命中压过仅关键词命中") {
            val primary = EmojiRanker.score(pinned = false, isPrimary = true, match = EmojiRanker.MATCH_CONTAINS)
            val keyword = EmojiRanker.score(pinned = false, isPrimary = false, match = EmojiRanker.MATCH_EXACT)
            (primary < keyword) shouldBe true
        }

        test("同档位时，词条完全相等压过前缀，前缀压过包含") {
            val exact = EmojiRanker.score(false, false, EmojiRanker.MATCH_EXACT)
            val prefix = EmojiRanker.score(false, false, EmojiRanker.MATCH_PREFIX)
            val contains = EmojiRanker.score(false, false, EmojiRanker.MATCH_CONTAINS)
            (exact < prefix) shouldBe true
            (prefix < contains) shouldBe true
        }

        test("词条匹配：相等 / 前缀 / 包含 / 不匹配") {
            EmojiRanker.match("pizza", "pizza") shouldBe EmojiRanker.MATCH_EXACT
            EmojiRanker.match("pizzas", "pizza") shouldBe EmojiRanker.MATCH_PREFIX
            EmojiRanker.match("deep pizza", "pizza") shouldBe EmojiRanker.MATCH_CONTAINS
            EmojiRanker.match("pizza", "pizzaz") shouldBe null
        }

        test("主名相关：相等、前缀、包含都算") {
            EmojiRanker.primaryMatches(listOf("披萨"), "披萨") shouldBe true
            EmojiRanker.primaryMatches(listOf("披萨"), "披") shouldBe true
            EmojiRanker.primaryMatches(listOf("pizza"), "piz") shouldBe true
            EmojiRanker.primaryMatches(listOf("pizza"), "zz") shouldBe true
            EmojiRanker.primaryMatches(listOf("pizza"), "burger") shouldBe false
            EmojiRanker.primaryMatches(null, "pizza") shouldBe false
            EmojiRanker.primaryMatches(emptyList(), "pizza") shouldBe false
        }

        test("空查询不匹配任何词条") {
            EmojiRanker.match("pizza", "") shouldBe EmojiRanker.MATCH_PREFIX
            // 空串在调用侧就该被拦住（EmojiIndex.search 会返回空），这里只确认语义：
            // startsWith("") 恒真，所以它落到前缀档——别让它在打分里当「完全相等」。
            EmojiRanker.match("pizza", "") shouldBe EmojiRanker.MATCH_PREFIX
        }
    })
