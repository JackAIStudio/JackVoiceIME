/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 一段识别好的文字最后落到哪儿。
 *
 * 这套规则以前是不存在的——只有「有光标就插、没有就排队等下次光标」。
 * 于是从通知栏按停的人（他只是想记一下）会在下次随便打开某个输入框时
 * 被自动插入，实测会落进另一个 App 的搜索框。这里把四种组合钉死，
 * 免得以后又有人把 `Copy` 改回 `pending`。
 */
class VoiceDeliveryTest :
    BehaviorSpec({
        fun resolve(
            source: VoiceStopSource,
            targetReady: Boolean,
        ) = VoiceDelivery.resolve(source, targetReady)

        Given("在语音面板上按停，键盘还开着、光标也在") {
            Then("直接插进光标") {
                resolve(VoiceStopSource.Panel, targetReady = true) shouldBe VoiceDelivery.Insert
            }
        }

        Given("在语音面板上按停，但键盘已经收了") {
            Then("退到剪贴板，不排队等下次光标") {
                // 排队会在下次打开任意输入框时插进去，那时候用户早就忘了这段话。
                resolve(VoiceStopSource.Panel, targetReady = false) shouldBe VoiceDelivery.Copy
            }
        }

        Given("从通知栏按停") {
            Then("光标在也复制，不插") {
                // 用户没在看键盘，说明他要的是「记下来」，不是「打进这个框」。
                resolve(VoiceStopSource.Notification, targetReady = true) shouldBe VoiceDelivery.Copy
            }

            Then("光标不在也复制") {
                resolve(VoiceStopSource.Notification, targetReady = false) shouldBe VoiceDelivery.Copy
            }
        }
    })
