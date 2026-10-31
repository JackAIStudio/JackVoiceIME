/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EmojiComposingTest :
    FunSpec({
        test("没打过字时清空什么都不做，绝不碰用户正文") {
            val c = EmojiComposing()
            c.next("") shouldBe EmojiComposing.Action.None
        }

        test("打第一个字母：整段设为它") {
            val c = EmojiComposing()
            c.next("p") shouldBe EmojiComposing.Action.Set("p")
            c.length shouldBe 1
        }

        test("接着打：整段重设，长度跟着变") {
            val c = EmojiComposing()
            c.next("p")
            c.next("pi")
            c.next("pisa") shouldBe EmojiComposing.Action.Set("pisa")
            c.length shouldBe 4
        }

        test("退格：按**新**长度重设，不是按旧长度删") {
            val c = EmojiComposing()
            c.next("pisa")
            c.next("pis") shouldBe EmojiComposing.Action.Set("pis")
            c.length shouldBe 3
        }

        test("清空：按**上一次**的长度删，不是按现在的 0 删") {
            val c = EmojiComposing()
            c.next("pisa")
            // 清空时 query 已经是空串了，但该删的是之前写进去的 4 个字符
            c.next("") shouldBe EmojiComposing.Action.Clear(4)
            c.length shouldBe 0
        }

        test("清空后长度归零，再清一次什么都不做——不会重复删用户正文") {
            val c = EmojiComposing()
            c.next("pisa")
            c.next("")
            c.next("") shouldBe EmojiComposing.Action.None
        }

        test("提交 emoji 后 reset：下一次清空不会按旧长度删到正文") {
            val c = EmojiComposing()
            c.next("pisa")
            // 点了 emoji：commitText 把那段组合就地替换掉了
            c.reset()
            c.length shouldBe 0
            // 再点 ✕ 时绝不能删——否则删掉的是 emoji 前面用户自己的字
            c.next("") shouldBe EmojiComposing.Action.None
        }

        test("换输入框后长度作废") {
            val c = EmojiComposing()
            c.next("pisa")
            c.onEditorChanged()
            c.length shouldBe 0
            c.next("") shouldBe EmojiComposing.Action.None
        }

        test("一整串完整生命周期：打字 → 退格 → 清空 → 再打") {
            val c = EmojiComposing()
            c.next("p") shouldBe EmojiComposing.Action.Set("p")
            c.next("pi") shouldBe EmojiComposing.Action.Set("pi")
            c.next("") shouldBe EmojiComposing.Action.Clear(2)
            c.next("p") shouldBe EmojiComposing.Action.Set("p")
            c.length shouldBe 1
        }
    })
