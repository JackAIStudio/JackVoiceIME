/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 「麦克风收不到声音」必须被数出来。
 *
 * 这条逻辑存在的理由很具体：系统把麦克风静音时 `AudioRecord.read()` **不报错**，
 * 老实返回全零 PCM。不数这个，界面上就是「点开录音、波形不动、毫无提示」，
 * 用户根本不知道是自己没说、还是软件坏了。
 *
 * 这里把三条边界钉住：正常说话不误报、静音够久要报、报过之后不刷屏。
 */
class SilenceGateTest :
    BehaviorSpec({
        /**
         * 一块 100ms 的静音 PCM。
         *
         * 16kHz / 16bit / 单声道 = 每秒 32000 字节，所以 100ms 是 3200 字节
         * （= 1600 个 int16 样本）。
         */
        fun silenceChunk(): ByteArray = ByteArray(3200)

        /** 一块 100ms 的「有声音」PCM：给几个非零样本就够，判定只看有没有。 */
        fun speechChunk(): ByteArray =
            ByteArray(3200).also {
                it[0] = 0x10
                it[1] = 0x02
            }

        fun feed(
            gate: SilenceGate,
            pcm: ByteArray,
        ): Boolean = gate.feed(pcm, pcm.size)

        Given("一直在说话") {
            Then("一次都不该报") {
                val gate = SilenceGate()
                var reports = 0
                repeat(30) { if (feed(gate, speechChunk())) reports++ }
                reports shouldBe 0
                gate.hasReported shouldBe false
            }
        }

        Given("连续静音，但没到门限") {
            Then("不能报——开头那几块本来就是零") {
                val gate = SilenceGate()
                var reports = 0
                // 10 块 = 1 秒，门限是 1.5 秒。
                repeat(10) { if (feed(gate, silenceChunk())) reports++ }
                reports shouldBe 0
                gate.hasReported shouldBe false
            }
        }

        Given("连续静音超过门限") {
            Then("刚好在跨过的那一刻报一次") {
                val gate = SilenceGate()
                var reports = 0
                repeat(16) { if (feed(gate, silenceChunk())) reports++ }
                reports shouldBe 1
                gate.hasReported shouldBe true
            }

            Then("继续静音也不再重复报") {
                val gate = SilenceGate()
                repeat(16) { feed(gate, silenceChunk()) }
                var extra = 0
                repeat(20) { if (feed(gate, silenceChunk())) extra++ }
                extra shouldBe 0
            }
        }

        Given("报过之后声音回来了") {
            Then("标记要撤掉，这样下一次被抢还能再报") {
                val gate = SilenceGate()
                repeat(16) { feed(gate, silenceChunk()) }
                gate.hasReported shouldBe true

                feed(gate, speechChunk()) shouldBe false
                gate.hasReported shouldBe false
                gate.silentSoFarMs shouldBe 0

                // 再静音一轮，应该又能报。
                var again = 0
                repeat(16) { if (feed(gate, silenceChunk())) again++ }
                again shouldBe 1
            }
        }

        Given("开始新一轮录音") {
            Then("reset 之后从零开始数") {
                val gate = SilenceGate()
                repeat(16) { feed(gate, silenceChunk()) }
                gate.reset()
                gate.hasReported shouldBe false
                gate.silentSoFarMs shouldBe 0
            }
        }

        Given("全零判定本身") {
            Then("全零缓冲区算静音") {
                val gate = SilenceGate()
                val buf = ByteArray(64)
                gate.isAllZero(buf, buf.size) shouldBe true
            }

            Then("只要有一个字节非零就不算") {
                val gate = SilenceGate()
                val buf = ByteArray(64)
                buf[63] = 1
                gate.isAllZero(buf, buf.size) shouldBe false
            }

            Then("只看 length 以内的部分，后面残留不算数") {
                val gate = SilenceGate()
                val buf = ByteArray(64)
                buf[40] = 1
                // 只读了前 32 字节，后面那半个 1 是上一块的残留。
                gate.isAllZero(buf, 32) shouldBe true
            }
        }
    })
