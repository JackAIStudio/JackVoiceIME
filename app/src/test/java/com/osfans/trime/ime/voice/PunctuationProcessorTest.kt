/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PunctuationProcessorTest :
    FunSpec({
        fun apply(
            text: String,
            mode: PunctuationMode,
        ) = PunctuationProcessor.apply(text, mode)

        test("全标点原样") {
            val text = "今天下午三点开会，记得带电脑。"
            apply(text, PunctuationMode.Full) shouldBe text
        }

        test("去句号只动末尾那一个句号") {
            apply("今天下午三点开会，记得带电脑。", PunctuationMode.NoPeriod) shouldBe
                "今天下午三点开会，记得带电脑"
            apply("真的吗？", PunctuationMode.NoPeriod) shouldBe "真的吗？"
            apply("Hello world.", PunctuationMode.NoPeriod) shouldBe "Hello world"
            apply("3.5.", PunctuationMode.NoPeriod) shouldBe "3.5"
            apply("等等...", PunctuationMode.NoPeriod) shouldBe "等等..."
            apply("他说“好的。”", PunctuationMode.NoPeriod) shouldBe "他说“好的。”"
        }

        test("无标点把标点换成空格") {
            apply("今天下午三点开会，记得带电脑。", PunctuationMode.NoPunctuation) shouldBe
                "今天下午三点开会 记得带电脑"
            apply("Hello, world.", PunctuationMode.NoPunctuation) shouldBe "Hello world"
            apply("他说“好的”，然后走了。", PunctuationMode.NoPunctuation) shouldBe
                "他说好的 然后走了"
        }

        test("无标点不动数字、时间、邮箱、网址里的符号") {
            apply("价格是3.5元，10:30见。", PunctuationMode.NoPunctuation) shouldBe
                "价格是3.5元 10:30见"
            apply("发到 a@b.com，版本 v1.2。", PunctuationMode.NoPunctuation) shouldBe
                "发到 a@b.com 版本 v1.2"
            apply("1,000 和 3.5 和 2026.10.19", PunctuationMode.NoPunctuation) shouldBe
                "1,000 和 3.5 和 2026.10.19"
            apply("打开 https://example.com/a?b=1。", PunctuationMode.NoPunctuation) shouldBe
                "打开 https://example.com/a?b=1"
            apply("don't stop", PunctuationMode.NoPunctuation) shouldBe "don't stop"
        }

        test("三档循环") {
            PunctuationMode.Full.next() shouldBe PunctuationMode.NoPeriod
            PunctuationMode.NoPeriod.next() shouldBe PunctuationMode.NoPunctuation
            PunctuationMode.NoPunctuation.next() shouldBe PunctuationMode.Full
            PunctuationMode.fromId("unknown") shouldBe PunctuationMode.Full
        }
    })
