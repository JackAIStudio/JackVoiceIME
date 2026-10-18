/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReplacementEngineTest :
    FunSpec({
        fun rule(
            from: String,
            to: String,
        ) = ReplacementEngine.Rule(from, to)

        fun apply(
            text: String,
            vararg pairs: Pair<String, String>,
        ) = ReplacementEngine.apply(text, pairs.map { rule(it.first, it.second) }).text

        test("按整词忽略大小写替换") {
            apply(
                "导出 Broll 到 ChromeDevTools",
                "ChromeDevTools" to "Chrome DevTools",
                "Broll" to "B roll",
            ) shouldBe "导出 B roll 到 Chrome DevTools"
            apply(
                "导出 bROLL 到 chromedevtools",
                "ChromeDevTools" to "Chrome DevTools",
                "Broll" to "B roll",
            ) shouldBe "导出 B roll 到 Chrome DevTools"
        }

        test("同一位置最长规则优先，且不会二次误伤") {
            apply(
                "打开 DaVinciPlayground 和 DaVinci",
                "DaVinci" to "达芬奇",
                "DaVinciPlayground" to "DaVinci Playground",
                "DaVinci Playground" to "DaVinci Playground",
            ) shouldBe "打开 DaVinci Playground 和 达芬奇"
            apply(
                "继续用 DaVinci Playground",
                "DaVinci" to "达芬奇",
                "DaVinciPlayground" to "DaVinci Playground",
                "DaVinci Playground" to "DaVinci Playground",
            ) shouldBe "继续用 DaVinci Playground"
        }

        test("拉丁词不会钻进更长单词") {
            val pairs =
                arrayOf(
                    "Cloud" to "Claude",
                    "Claude Code" to "Claude Code",
                    "ClaudeCode" to "Claude Code",
                    "i Cloud" to "iCloud",
                    "Tree" to "Trae",
                    "绘画" to "会话",
                )
            apply("有这个 iCloud 中的文件，还有 icloud", *pairs) shouldBe "有这个 iCloud 中的文件，还有 icloud"
            apply("Cloud Code 和 cloud code，以及 ClaudeCode", *pairs) shouldBe "Claude Code 和 Claude code，以及 Claude Code"
            apply("还有 Cloudflare，不要改成 Claudeflare", *pairs) shouldBe "还有 Cloudflare，不要改成 Claudeflare"
            apply("street 上的 Tree，以及 Cloud。", *pairs) shouldBe "street 上的 Trae，以及 Claude。"
            apply("这个绘画", *pairs) shouldBe "这个会话"
            apply("i Cloud 的文件在说Cloud", *pairs) shouldBe "iCloud 的文件在说Claude"
            apply("Cloud2 保持原样", *pairs) shouldBe "Cloud2 保持原样"
        }

        test("from == to 的短语锁保护长写法") {
            apply(
                "打开 DaVinci Playground",
                "DaVinci" to "达芬奇",
                "DaVinci Playground" to "DaVinci Playground",
            ) shouldBe "打开 DaVinci Playground"
        }

        test("清洗规则忽略大小写去重，后写的目标覆盖先写的") {
            val cleaned =
                ReplacementEngine.sanitize(
                    listOf(
                        rule("Chrome DevTools", "旧写法"),
                        rule("chrome devtools", "Chrome DevTools"),
                        rule("ChromeDevTools", "Chrome DevTools"),
                    ),
                )
            cleaned.map { it.from } shouldBe listOf("Chrome DevTools", "ChromeDevTools")
            cleaned.map { it.to } shouldBe listOf("Chrome DevTools", "Chrome DevTools")
        }

        test("英文和数字的边界与桌面版一致") {
            apply(
                "iCloud、i Cloud、Cloud、Cloud2 和 Cloudflare",
                "Cloud" to "Claude",
                "i Cloud" to "iCloud",
            ) shouldBe "iCloud、iCloud、Claude、Cloud2 和 Cloudflare"
        }
    })
