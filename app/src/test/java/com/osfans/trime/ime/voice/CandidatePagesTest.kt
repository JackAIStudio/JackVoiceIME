/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CandidatePagesTest :
    FunSpec({
        test("Rime 给满 16 条时从第 16 条往后要") {
            val pages = CandidatePages(pageSize = 80)
            pages.reset(loaded = 16, exhausted = false)
            pages.beginLoad() shouldBe 16
        }

        test("一批没回来之前不重复要") {
            val pages = CandidatePages(pageSize = 80)
            pages.reset(loaded = 16, exhausted = false)
            pages.beginLoad() shouldBe 16
            pages.beginLoad() shouldBe null
            pages.isLoading shouldBe true
        }

        test("拿到一整页后游标往前推，还能接着要") {
            val pages = CandidatePages(pageSize = 80)
            pages.reset(loaded = 16, exhausted = false)
            pages.beginLoad()
            pages.endLoad(80)
            pages.loaded shouldBe 96
            pages.exhausted shouldBe false
            pages.beginLoad() shouldBe 96
        }

        test("不满一页就是最后一批") {
            val pages = CandidatePages(pageSize = 80)
            pages.reset(loaded = 16, exhausted = false)
            pages.beginLoad()
            pages.endLoad(12)
            pages.loaded shouldBe 28
            pages.exhausted shouldBe true
            pages.beginLoad() shouldBe null
        }

        test("Rime 已经说没有更多时一次都不去要") {
            val pages = CandidatePages(pageSize = 80)
            pages.reset(loaded = 16, exhausted = true)
            pages.beginLoad() shouldBe null
        }

        test("请求失败把占位还回去，下次滚到底还能再试") {
            val pages = CandidatePages(pageSize = 80)
            pages.reset(loaded = 16, exhausted = false)
            pages.beginLoad()
            pages.abortLoad()
            pages.isLoading shouldBe false
            pages.loaded shouldBe 16
            pages.beginLoad() shouldBe 16
        }

        test("重新开一轮时清掉在飞状态，从新的位置要") {
            val pages = CandidatePages(pageSize = 80)
            pages.reset(loaded = 16, exhausted = false)
            pages.beginLoad()
            // 候选换了一批：旧请求回来作废，新一轮从 5 条往后要
            pages.reset(loaded = 5, exhausted = false)
            pages.isLoading shouldBe false
            pages.beginLoad() shouldBe 5
        }
    })
