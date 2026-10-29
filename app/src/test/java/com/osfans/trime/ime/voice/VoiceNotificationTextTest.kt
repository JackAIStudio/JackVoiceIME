/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 通知栏只显示**最新的一句**。
 *
 * 流式结果是整段累积的，原样摆进通知会越变越长，两行很快被占满，
 * 用户真正想知道的「我刚说的这句出来没」反而被挤掉。
 */
class VoiceNotificationTextTest :
    BehaviorSpec({
        fun latest(raw: String, max: Int = VoiceNotificationText.MAX_CHARS) = VoiceNotificationText.latestSentence(raw, max)

        Given("只说了半句，还没有标点") {
            Then("原样显示这一句") {
                latest("今天下午我们") shouldBe "今天下午我们"
            }
        }

        Given("已经说完一句又接了下一句") {
            Then("只取最后那一句") {
                latest("今天下午我们开会。然后") shouldBe "然后"
            }
        }

        Given("刚说完、末尾停在标点上") {
            Then("显示的仍是刚说完的那一句，而不是空") {
                latest("今天下午我们开会。") shouldBe "今天下午我们开会"
            }

            Then("问号、感叹号、分号都算句子边界") {
                latest("真的吗？那好吧！") shouldBe "那好吧"
                latest("第一点；第二点") shouldBe "第二点"
            }
        }

        Given("连续的句末标点") {
            Then("一起摘掉，不留下空句") {
                latest("好……然后呢") shouldBe "然后呢"
                latest("走吧。。。") shouldBe "走吧"
            }
        }

        Given("一整句一口气说了很长") {
            Then("只留末尾，前面用省略号提示被截") {
                val long = "一".repeat(100)
                val shown = latest(long, max = 20)
                shown.length shouldBe 20
                shown shouldBe "…" + "一".repeat(19)
            }
        }

        Given("空输入") {
            Then("返回空串，通知栏据此回到静态提示") {
                latest("") shouldBe ""
                latest("   ") shouldBe ""
                latest("。") shouldBe ""
            }
        }

        Given("上限不是正数") {
            Then("直接当没有预览") {
                latest("随便说点什么", max = 0) shouldBe ""
            }
        }
    })
