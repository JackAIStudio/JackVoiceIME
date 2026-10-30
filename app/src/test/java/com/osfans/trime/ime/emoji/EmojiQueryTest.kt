/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * emoji 搜索词的输入规则。
 *
 * 这些用例钉的是「哪些键该吞、哪些该放行」。规则写错不会崩，只会让键盘「有键失灵」，
 * 所以必须靠测试守。
 */
class EmojiQueryTest :
    FunSpec({
        test("字母和数字进搜索词，统一小写") {
            val q = EmojiQuery()
            q.accept('P') shouldBe true
            q.accept('i') shouldBe true
            q.accept('2') shouldBe true
            q.text shouldBe "pi2"
        }

        test("空格不吞：emoji 模式下它就是个空格") {
            val q = EmojiQuery()
            q.accept(' ') shouldBe false
            q.text shouldBe ""
        }

        test("标点不吞，交给原路径") {
            val q = EmojiQuery()
            q.accept('。') shouldBe false
            q.accept('/') shouldBe false
            q.accept('\'') shouldBe false
            q.accept('-') shouldBe false
            q.text shouldBe ""
        }

        test("非 ASCII 字母也不吞（中文、带音调的字）") {
            val q = EmojiQuery()
            q.accept('拼') shouldBe false
            q.accept('é') shouldBe false
            q.text shouldBe ""
        }

        test("退格先删搜索词") {
            val q = EmojiQuery()
            q.accept('p')
            q.accept('i')
            q.backspace() shouldBe true
            q.text shouldBe "p"
        }

        test("搜索词空了以后退格放行，去删正文") {
            val q = EmojiQuery()
            q.accept('p')
            q.backspace() shouldBe true
            q.backspace() shouldBe false
            q.isEmpty shouldBe true
        }

        test("清空后可以重新开始") {
            val q = EmojiQuery()
            q.accept('p')
            q.accept('i')
            q.clear()
            q.isEmpty shouldBe true
            q.accept('k') shouldBe true
            q.text shouldBe "k"
        }

        test("拼音串按字母累积") {
            val q = EmojiQuery()
            "pisa".forEach { q.accept(it) }
            q.text shouldBe "pisa"
        }
    })
