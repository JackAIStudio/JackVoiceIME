/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HotwordSanitizerTest :
    FunSpec({
        test("归一化成火山识别形") {
            HotwordSanitizer.normalize("B roll") shouldBe "Broll"
            HotwordSanitizer.normalize("fun-asr") shouldBe "funasr"
            HotwordSanitizer.normalize("OpenClaw") shouldBe "OpenClaw"
            HotwordSanitizer.normalize("  ") shouldBe ""
        }

        test("清洗时丢空、忽略大小写去重、丢掉超长词") {
            val tooLong = "a".repeat(HotwordSanitizer.MAX_HOTWORD_CHARS + 1)
            HotwordSanitizer.sanitize(
                listOf("B roll", " bROLL ", "", tooLong, "达芬奇"),
            ) shouldBe listOf("Broll", "达芬奇")
        }

        test("请求级直传按累计 100 字符截断") {
            val first = "a".repeat(30)
            val second = "b".repeat(30)
            val third = "c".repeat(30)
            val fourth = "d".repeat(30)
            HotwordSanitizer.inline(listOf(first, second, third, fourth)) shouldBe
                listOf(first, second, third)
        }

        test("刚好 100 字符时全部保留") {
            val first = "a".repeat(30)
            val second = "b".repeat(30)
            val third = "c".repeat(30)
            val fourth = "d".repeat(10)
            HotwordSanitizer.inline(listOf(first, second, third, fourth)) shouldBe
                listOf(first, second, third, fourth)
        }
    })
