/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * 本地流式识别必须补尾。
 *
 * 真机实测（X-ASR 480ms int8）：
 * - 不补尾：`有时候本地会有识别不出来的情`
 * - 补 0.66s：`有时候本地会有识别不出来的情况`
 *
 * 尾字丢失的原因是最后一个 chunk 缺右上下文，不是录音没收全。
 * 这里把补尾时长和「只补静音、不伪造语音」钉住。
 */
class LocalTailPaddingTest :
    BehaviorSpec({
        Given("本地识别的尾部静音") {
            Then("时长至少覆盖一个 480ms chunk，实测 0.66s 才够") {
                LocalAsrEngine.TAIL_PADDING_SECONDS shouldBeGreaterThanOrEqual 0.66f
            }

            Then("采样点数与 16kHz 对应") {
                val expected = (LocalAsrEngine.TAIL_PADDING_SECONDS * LocalAsrEngine.SAMPLE_RATE).toInt()
                LocalAsrEngine.tailPadding.size shouldBe expected
                LocalAsrEngine.tailPadding.size shouldBeGreaterThan 0
            }

            Then("内容必须是全静音，不能有任何非零样本") {
                LocalAsrEngine.tailPadding.all { it == 0f } shouldBe true
            }

            Then("每次取到的是新数组，调用方改它不会污染下一次识别") {
                val first = LocalAsrEngine.tailPadding
                first[0] = 1f
                LocalAsrEngine.tailPadding[0] shouldBe 0f
            }
        }
    })
