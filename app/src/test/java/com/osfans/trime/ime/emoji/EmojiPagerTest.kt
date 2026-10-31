/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EmojiPagerTest :
    FunSpec({
        fun emojis(n: Int) = (0 until n).map { "e$it" }

        test("reset 只放第一页，剩下留在后面") {
            val pager = EmojiPager(pageSize = 54)
            pager.reset(emojis(1906))
            pager.loaded shouldBe 54
            pager.total shouldBe 1906
            pager.hasMore shouldBe true
        }

        test("loadMore 按页往前推，下标接着排") {
            val pager = EmojiPager(pageSize = 54)
            pager.reset(emojis(1906))
            pager.loadMore() shouldBe emojis(1906).subList(54, 108)
            pager.loaded shouldBe 108
            pager.loadMore().first() shouldBe "e108"
        }

        test("最后一页不满也照给，给完就没有了") {
            val pager = EmojiPager(pageSize = 54)
            pager.reset(emojis(100))
            pager.loadMore() shouldBe emojis(100).subList(54, 100)
            pager.loaded shouldBe 100
            pager.hasMore shouldBe false
            pager.loadMore() shouldBe emptyList()
        }

        test("总数不到一页时第一页就是全部") {
            val pager = EmojiPager(pageSize = 54)
            pager.reset(emojis(10))
            pager.loaded shouldBe 10
            pager.hasMore shouldBe false
            pager.loadMore() shouldBe emptyList()
        }

        test("空序列不会放出任何东西") {
            val pager = EmojiPager(pageSize = 54)
            pager.reset(emptyList())
            pager.loaded shouldBe 0
            pager.hasMore shouldBe false
            pager.loadMore() shouldBe emptyList()
        }

        test("换一份序列时游标回到第一页，不会接着上一份往下走") {
            val pager = EmojiPager(pageSize = 54)
            pager.reset(emojis(500))
            pager.loadMore()
            pager.loaded shouldBe 108
            // 新查询只有 20 条：游标必须退回 20，否则会跳过前 108 条直接空
            pager.reset(emojis(20))
            pager.loaded shouldBe 20
            pager.hasMore shouldBe false
        }

        test("clear 之后什么都要不到") {
            val pager = EmojiPager(pageSize = 54)
            pager.reset(emojis(500))
            pager.clear()
            pager.loaded shouldBe 0
            pager.total shouldBe 0
            pager.loadMore() shouldBe emptyList()
        }

        test("一次一页地翻到底，条目数正好等于总数且不重不漏") {
            val all = emojis(300)
            val pager = EmojiPager(pageSize = 54)
            pager.reset(all)
            val seen = mutableListOf<String>()
            seen += all.take(pager.loaded)
            while (pager.hasMore) {
                seen += pager.loadMore()
            }
            seen shouldBe all
        }
    })
