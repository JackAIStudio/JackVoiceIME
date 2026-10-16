/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import com.osfans.trime.util.appContext
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录音 + 识别。**必须由 [VoiceCaptureService] 在 `startForeground()` 之后启动。**
 *
 * Android 11 起，拿麦克风要求应用处于前台、或持有 `microphone` 类型的前台服务；
 * 输入法服务本身不算前台（实测会 `getInputForAttr: EX_SECURITY, missing perms for
 * source 6`）。Typeless 的 `AudioRecordingForegroundService` 正是为此存在。
 *
 * 输入法与这个服务同进程，所以直接用单例回传，不走 Binder。
 * 所有回调都切到主线程——调用方拿到就能直接改 UI / 调输入连接。
 */
object VoiceEngine {
    interface Listener {
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: Listener? = null

    private var recorder: VoiceRecorder? = null
    private var asr: VolcAsrClient? = null

    private val result = StringBuilder()
    private val stopped = AtomicBoolean(true)

    /** 本次录音开始时间，用来算听写时长（统计页要用）。 */
    private var startedAt = 0L

    @Volatile
    var isRecording = false
        private set

    fun setListener(value: Listener?) {
        listener = value
    }

    /** 调用前必须已经 `startForeground(...)`，否则麦克风仍会被系统拒绝。 */
    fun startRecording(ctx: Context) {
        if (isRecording) return
        val key = VoicePrefs.apiKey(ctx)
        if (key.isBlank()) {
            post { listener?.onError("请先在设置里填语音 API Key") }
            return
        }

        result.setLength(0)
        stopped.set(false)

        val client =
            VolcAsrClient(
                VolcAsrClient.Config(
                    apiKey = key,
                    resourceId = VoicePrefs.resourceId(ctx),
                    hotwords = if (VoicePrefs.hotwordsEnabled(ctx)) VoiceDictionary.hotwords(ctx) else emptyList(),
                ),
                object : VolcAsrClient.Listener {
                    override fun onPartial(text: String) {
                        result.setLength(0)
                        result.append(text)
                        post { listener?.onPartial(text) }
                    }

                    override fun onFinal(text: String) {
                        if (text.isNotEmpty()) {
                            result.setLength(0)
                            result.append(text)
                        }
                        finish(final = true)
                    }

                    override fun onError(message: String) {
                        Timber.e("语音识别失败: $message")
                        finishWithError(message)
                    }

                    override fun onClosed() = Unit
                },
            )
        asr = client
        client.start()

        val rec =
            VoiceRecorder(
                onChunk = { client.feed(it) },
                onError = { finishWithError(it) },
            )
        if (!rec.start()) {
            client.cancel()
            asr = null
            return
        }
        recorder = rec
        isRecording = true
        startedAt = System.currentTimeMillis()
    }

    /** 停止录音、发结束包，等服务端二遍终稿。等不到就用一遍结果兜底。 */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recorder?.stop()
        recorder = null
        asr?.stop()
        main.postDelayed({ finish(final = true) }, FINAL_TIMEOUT_MS)
    }

    /** 直接丢弃，不上屏。 */
    fun cancel() {
        isRecording = false
        stopped.set(true)
        recorder?.stop()
        recorder = null
        asr?.cancel()
        asr = null
        result.setLength(0)
        main.removeCallbacksAndMessages(null)
    }

    private fun finish(final: Boolean) {
        if (!stopped.compareAndSet(false, true)) return
        main.removeCallbacksAndMessages(null)
        val raw = result.toString()
        recorder?.stop()
        recorder = null
        asr?.cancel()
        asr = null
        result.setLength(0)
        val text = raw.trim()
        val duration = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        startedAt = 0L
        if (text.isNotEmpty()) {
            runCatching { VoiceHistory.append(appContext, text, duration) }
        }
        post { listener?.onFinal(text) }
    }

    private fun finishWithError(message: String) {
        if (!stopped.compareAndSet(false, true)) return
        main.removeCallbacksAndMessages(null)
        recorder?.stop()
        recorder = null
        asr?.cancel()
        asr = null
        result.setLength(0)
        isRecording = false
        post { listener?.onError(message) }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 停止录音后等二遍终稿的上限。 */
    private const val FINAL_TIMEOUT_MS = 3000L
}
