/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 面板中央同一时刻只能有一句话。
 *
 * 真机截图里「点击说话」「正在重新识别…」和临时提示叠在同一坐标，
 * 就是因为三个 View 各写各的。现在所有输入都走 [VoiceCenterStatus.resolve]，
 * 这里把优先级钉死，避免以后又在面板里加第二个 TextView。
 */
class VoiceCenterStatusTest :
    BehaviorSpec({
        fun resolve(
            state: VoiceState = VoiceState.Idle,
            previewVisible: Boolean = false,
            retrying: Boolean = false,
            retryProgress: Float = 0f,
            trackpadActive: Boolean = false,
            message: String? = null,
        ) = VoiceCenterStatus.resolve(
            state = state,
            previewVisible = previewVisible,
            retrying = retrying,
            retryProgress = retryProgress,
            trackpadActive = trackpadActive,
            message = message,
        )

        Given("空闲且没有任何任务") {
            Then("只显示「点击说话」") {
                resolve() shouldBe "点击说话"
            }
        }

        Given("正在重新识别，同时来了一条临时提示") {
            Then("只显示进度，不叠提示，也不显示「点击说话」") {
                resolve(retrying = true, message = "正在重新识别 01:26 那段…") shouldBe
                    "正在重新识别…"
            }

            Then("有进度时显示百分比") {
                resolve(retrying = true, retryProgress = 0.42f) shouldBe "正在重新识别 42%"
            }
        }

        Given("重新识别结束后提示还在") {
            Then("显示提示，而不是立刻回到「点击说话」") {
                resolve(message = "重新识别失败：网络没连上") shouldBe
                    "重新识别失败：网络没连上"
            }
        }

        Given("预览卡片开着") {
            Then("中央不显示任何文字，避免压住卡片") {
                resolve(previewVisible = true, message = "识别好了") shouldBe null
            }

            Then("即使重识别标记还在，也以卡片为准") {
                resolve(previewVisible = true, retrying = true) shouldBe null
            }
        }

        Given("长按空格进入触控板") {
            Then("中央让给触控板提示") {
                resolve(trackpadActive = true, message = "已切到本地识别") shouldBe null
            }
        }

        Given("录音中或等终稿") {
            Then("中央状态文字一律不显示") {
                resolve(state = VoiceState.Recording) shouldBe null
                resolve(state = VoiceState.Thinking, message = "处理中") shouldBe null
            }
        }

        Given("空白提示") {
            Then("当作没有提示，回到空闲态文案") {
                resolve(message = "   ") shouldBe "点击说话"
            }
        }
    })
