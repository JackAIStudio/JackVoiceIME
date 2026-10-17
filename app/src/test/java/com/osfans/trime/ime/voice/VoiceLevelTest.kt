/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin

/**
 * 波形必须由真实说话声驱动：
 *   - 环境底噪（和底噪差不多的稳态声音）→ 0，柱子停在原地；
 *   - 比底噪明显响的说话 → 抬起来；
 *   - 停嘴 → 很快落回去。
 * 这里用合成 PCM 把这条关系钉住，免得以后又把「一直跳」改回来。
 */
class VoiceLevelTest :
    BehaviorSpec({
        fun sinePcm(amplitude: Int, samples: Int = 1600): ByteArray {
            val out = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val value = (amplitude * sin(2.0 * PI * i / 32.0)).toInt()
                out[i * 2] = (value and 0xFF).toByte()
                out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            return out
        }

        /** 模拟连续的 100ms 块，返回最后一块的音量。 */
        fun feed(
            meter: VoiceLevel,
            pcm: ByteArray,
            chunks: Int,
        ): Float {
            var level = 0f
            repeat(chunks) { level = meter.fromPcm(pcm, pcm.size) }
            return level
        }

        Given("环境底噪（幅度 1000，约 -33 dBFS）") {
            When("连续录了 10 块") {
                Then("音量必须是 0：没说话时柱子不能自己跳") {
                    val meter = VoiceLevel()
                    feed(meter, sinePcm(1000), 10) shouldBe 0f
                }
            }

            When("底噪之后开始说话（幅度 8000）") {
                Then("第一块就能明显抬起来") {
                    val meter = VoiceLevel()
                    feed(meter, sinePcm(1000), 10)
                    feed(meter, sinePcm(8000), 1) shouldBeGreaterThan 0.5f
                }
            }

            When("说完再回到环境底噪") {
                Then("两块之内就落回 0") {
                    val meter = VoiceLevel()
                    feed(meter, sinePcm(1000), 5)
                    feed(meter, sinePcm(8000), 5)
                    feed(meter, sinePcm(1000), 2) shouldBe 0f
                }
            }
        }

        Given("点完麦克风立刻开口（第一块就是大幅度的说话声）") {
            When("幅度 8000 直接开始") {
                Then("预热 3 块之后就要抬起来") {
                    val meter = VoiceLevel()
                    meter.fromPcm(sinePcm(8000), 3200) shouldBe 0f
                    feed(meter, sinePcm(8000), 3) shouldBeGreaterThan 0.5f
                }
            }
        }

        Given("完全静音") {
            When("全部是 0") {
                Then("音量保持 0") {
                    val meter = VoiceLevel()
                    feed(meter, ByteArray(3200), 5) shouldBe 0f
                }
            }
        }

        Given("接近满量程的声音") {
            When("底噪之后来一块方波") {
                Then("音量到 1") {
                    val meter = VoiceLevel()
                    feed(meter, sinePcm(1000), 5)
                    val samples = 1600
                    val out = ByteArray(samples * 2)
                    for (i in 0 until samples) {
                        val value = if (i % 2 == 0) 32767 else -32768
                        out[i * 2] = (value and 0xFF).toByte()
                        out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                    }
                    feed(meter, out, 1) shouldBe 1f
                }
            }
        }
    })
