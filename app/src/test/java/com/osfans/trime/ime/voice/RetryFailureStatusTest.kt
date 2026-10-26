/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 「重新识别失败之后这条记录该记成什么」——这个判断决定了重试按钮还在不在。
 *
 * 踩过的坑：以前不管什么原因都写 `Empty`（没听到人声），而 `Empty` 会让
 * `canRetry` 变 false。于是断一次网，这条录音就被永久判死——历史页写着
 * 「这段里没听到人声」，重试按钮再也不出现，可音频里明明有 57% 的块是人声。
 */
class RetryFailureStatusTest :
    BehaviorSpec({
        fun status(
            message: String,
            retryable: Boolean = true,
        ) = VoiceHistory.statusAfterRetryFailure(message, retryable)

        given("网络类失败") {
            then("记 NoNetwork，按钮留着") {
                status("connection closed") shouldBe VoiceHistory.Status.NoNetwork
                status("重新识别超时，请稍后再试") shouldBe VoiceHistory.Status.NoNetwork
                status("连接被对端关闭") shouldBe VoiceHistory.Status.NoNetwork
                status("SocketTimeoutException") shouldBe VoiceHistory.Status.NoNetwork
            }

            then("这些状态下 canRetry 必须为 true") {
                val e = entry(status("connection closed"))
                e.canRetry shouldBe true
            }
        }

        given("服务端说这段没人说话") {
            then("记 Empty——这是唯一该收掉按钮的理由") {
                status("这段录音里没听到人声", retryable = false) shouldBe VoiceHistory.Status.Empty
                status("没有人说话", retryable = false) shouldBe VoiceHistory.Status.Empty
            }

            then("Empty 的条目不再提供重试") {
                entry(VoiceHistory.Status.Empty).canRetry shouldBe false
            }
        }

        given("既不是网络也不是没人说话") {
            then("记 Failed，按钮留着——声音还在，换个时间还能试") {
                status("服务端返回错误 500", retryable = false) shouldBe VoiceHistory.Status.Failed
                entry(VoiceHistory.Status.Failed).canRetry shouldBe true
            }
        }

        given("retryable 这个布尔不能把有声的条目判成 Empty") {
            // 这是老代码的病根：if (retryable) NoNetwork else Empty
            // 一个布尔同时表达「网络问题」和「这段没人说话」，两件事被混成一件。
            then("retryable=false 但错误是网络类，仍然是 NoNetwork") {
                status("connection closed", retryable = false) shouldBe VoiceHistory.Status.NoNetwork
            }
        }

        given("已经有文字或正在处理的条目") {
            then("Completed / Transcribing / Recording 都不提供重试") {
                entry(VoiceHistory.Status.Completed).canRetry shouldBe false
                entry(VoiceHistory.Status.Transcribing).canRetry shouldBe false
                entry(VoiceHistory.Status.Recording).canRetry shouldBe false
            }

            then("没有音频的条目不提供重试") {
                entry(VoiceHistory.Status.Failed, hasAudio = false).canRetry shouldBe false
            }
        }
    })

private fun entry(
    status: VoiceHistory.Status,
    hasAudio: Boolean = true,
): VoiceHistory.Entry =
    VoiceHistory.Entry(
        id = "test",
        text = "",
        atMillis = 0L,
        durationMs = 1000L,
        fileName = if (hasAudio) "x.wav" else null,
        status = status,
    )
