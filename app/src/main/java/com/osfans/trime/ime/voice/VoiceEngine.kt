/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.osfans.trime.util.appContext
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

        /** 实时音量（0..1）。静音必须是 0，波形靠它驱动，没声音就不该动。 */
        fun onLevel(level: Float)
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

    /** 点停止的时刻，用来区分「等到了终稿」和「超时拿一遍结果兜底」。 */
    private var stopAt = 0L

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
                        Log.i(TAG, "服务端终稿到达：${text.length} 字，停止后 ${elapsedSinceStop()}ms")
                        if (text.isNotEmpty()) {
                            result.setLength(0)
                            result.append(text)
                        }
                        finish(fromTimeout = false)
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
                onLevel = { level -> post { listener?.onLevel(level) } },
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
        stopAt = System.currentTimeMillis()
        Log.i(TAG, "停止录音：发末包，等终稿（上限 ${FINAL_TIMEOUT_MS}ms），当前一遍结果 ${result.length} 字")
        recorder?.stop()
        recorder = null
        asr?.stop()
        // 停录这一刻波形就该落地，别等终稿或超时。
        post { listener?.onLevel(0f) }
        main.postDelayed({ finish(fromTimeout = true) }, FINAL_TIMEOUT_MS)
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
        post { listener?.onLevel(0f) }
    }

    /**
     * 唯一出口。上屏文本可能是服务端终稿，也可能是 3 秒超时后用一遍结果兜底——
     * 两者行为完全一样，只有日志能区分，所以 [fromTimeout] 必须记下来。
     */
    private fun finish(fromTimeout: Boolean) {
        if (!stopped.compareAndSet(false, true)) {
            Log.d(TAG, "重复 finish 被忽略（来自${if (fromTimeout) "超时兜底" else "终稿"}）")
            return
        }
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
        val waited = elapsedSinceStop()
        stopAt = 0L
        if (fromTimeout) {
            Log.w(TAG, "没等到终稿，用一遍流式结果兜底：${text.length} 字，停止后 ${waited}ms")
        } else {
            Log.i(TAG, "终稿上屏：${text.length} 字，停止后 ${waited}ms")
        }
        if (text.isNotEmpty()) {
            runCatching { VoiceHistory.append(appContext, text, duration) }
        }
        post { listener?.onLevel(0f) }
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
        Log.e(TAG, "识别失败：$message（停止后 ${elapsedSinceStop()}ms）")
        stopAt = 0L
        post { listener?.onLevel(0f) }
        post { listener?.onError(message) }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun elapsedSinceStop(): Long =
        if (stopAt > 0) System.currentTimeMillis() - stopAt else -1L

    private const val TAG = "JackVoice"

    /** 停止录音后等二遍终稿的上限。 */
    private const val FINAL_TIMEOUT_MS = 3000L
}
