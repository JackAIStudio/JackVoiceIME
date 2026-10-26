/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 断网这件事必须被当成「等会儿再试」，而不是「这轮完了」。
 *
 * 用户截图里那句 `识别失败：connection closed` 就是这条判定没做对的下场：
 * 原始异常字符串被直接甩到面板上，录音还一起被掐了。
 * 这里把措辞和分类都钉住，免得以后又退回去。
 */
class VoiceFailureTest :
    BehaviorSpec({
        given("网络类错误") {
            val messages =
                listOf(
                    "connection closed",
                    "Connection closed",
                    "SocketTimeoutException: timeout",
                    "unable to resolve host api.example.com",
                    "Software caused connection abort: socket write error",
                    "Broken pipe",
                    "Network is unreachable",
                    "ECONNRESET",
                    "unexpected end of stream",
                )
            messages.forEach { raw ->
                `when`("原始错误是「$raw」") {
                    then("判为网络问题，界面只说人话") {
                        VoiceFailure.isNetwork(raw) shouldBe true
                        VoiceFailure.describe(raw) shouldBe "网络没连上"
                    }
                }
            }
        }

        given("鉴权类错误") {
            then("401 不是网络问题——再试一百次也没用") {
                VoiceFailure.isAuth("HTTP 401 Unauthorized") shouldBe true
                VoiceFailure.isNetwork("HTTP 401 Unauthorized") shouldBe false
                VoiceFailure.describe("HTTP 401 Unauthorized") shouldBe "API Key 校验没过，检查一下设置"
            }

            then("401 响应里混进 connect 之类的词也不该被当成断网") {
                VoiceFailure.isNetwork("401 connect rejected") shouldBe false
            }
        }

        given("既不是网络也不是鉴权的错误") {
            then("原样透出，总比吞掉强") {
                VoiceFailure.isNetwork("audio too short") shouldBe false
                VoiceFailure.describe("audio too short") shouldBe "audio too short"
            }

            then("空串给一句兜底") {
                VoiceFailure.describe("   ") shouldBe "识别失败"
            }
        }

        given("中文兜底文案") {
            // OkHttp 的 onFailure 常常拿不到 message（断网那一瞬间 t.message 就是 null），
            // 客户端会自己拼一句中文。这些也得被认成网络问题，否则历史里
            // 会留下一条「连接失败」而不是「当时网络断了」。
            then("客户端自己拼的「连接失败」按网络问题处理") {
                VoiceFailure.isNetwork("连接失败") shouldBe true
                VoiceFailure.describeForHistory("连接失败") shouldBe "当时网络断了，没能转成文字"
            }

            then("历史页要说清是「当时」，不是「现在」") {
                VoiceFailure.describeForHistory("network unreachable") shouldBe "当时网络断了，没能转成文字"
                VoiceFailure.describe("network unreachable") shouldBe "网络没连上"
            }
        }
    })
