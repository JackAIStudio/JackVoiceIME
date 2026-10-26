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
 *
 * ## 同一块 PCM 走两条路
 *
 * 每一块音频既喂给流式识别，也**同时写进本地 WAV**（[VoiceAudio]）。
 * 网络断了、终稿没等到、进程被回收，声音都还在，用户能重来。
 * 写盘失败不会拖累识别——最多这一轮没存上，日志里有记录。
 */
object VoiceEngine {
    interface Listener {
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onError(message: String)

        /** 实时音量（0..1）。静音必须是 0，波形靠它驱动，没声音就不该动。 */
        fun onLevel(level: Float)

        /**
         * 这一轮没转出文字，但**录音已经留在本地**了。
         *
         * 与 [onError] 分开：超时兜底那句一个错都没有，只是没拿到结果，
         * 走 onError 会让界面报一个并不存在的「失败原因」，而且拿不到重试入口。
         *
         * @param entryId 历史里那条记录，界面可以直接拿它去重识别
         */
        fun onRetryable(
            entryId: String,
            message: String,
        ) {}
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: Listener? = null

    private var recorder: VoiceRecorder? = null
    private var asr: VolcAsrClient? = null

    /** 本轮音频的落盘器。null 表示这一轮存不下来（目录不可写等），识别照常。 */
    private var audio: VoiceAudio.Writer? = null

    private val result = StringBuilder()
    private val stopped = AtomicBoolean(true)

    /** 本次录音开始时间，用来算听写时长（统计页要用）。 */
    private var startedAt = 0L

    /** 点停止的时刻，用来区分「等到了终稿」和「超时拿一遍结果兜底」。 */
    private var stopAt = 0L

    /** 本轮的听写历史条目。录音一开始就建，失败也留着。 */
    private var entryId: String = ""

    /** 停止录音时收尾出来的 WAV，等终稿到了再挂到记录上。 */
    private var stoppedArtifact: VoiceAudio.Artifact? = null

    @Volatile
    var isRecording = false
        private set

    /** 已经停止录音、正在等服务端终稿。切模块 / 重建输入法时靠它恢复「处理中」。 */
    @Volatile
    var isWaitingFinal = false
        private set

    fun setListener(value: Listener?) {
        listener = value
    }

    /** 当前这轮记录编号（面板重试按钮要知道重试哪一条）。 */
    val currentEntryId: String get() = entryId

    /** 刚刚失败、音频还在的那条记录。界面据此亮出「重试」。 */
    @Volatile
    var failedEntryId: String = ""
        private set

    /** 这一轮有没有存到音频。事件收尾后可能已经没了，只适合在录音/处理中问。 */
    val hasAudio: Boolean get() = audio != null || entryId.isNotEmpty()

    /**
     * 调用前必须已经 `startForeground(...)`，否则麦克风仍会被系统拒绝。
     *
     * @return 麦克风真的起来了才为 true；起不来时错误已经通过 listener 报出去。
     */
    fun startRecording(ctx: Context): Boolean {
        if (isRecording) return false
        val key = VoicePrefs.apiKey(ctx)
        if (key.isBlank()) {
            post { listener?.onError("请先在设置里填语音 API Key") }
            return false
        }

        result.setLength(0)
        stopped.set(false)
        isWaitingFinal = false
        stoppedArtifact = null
        failedEntryId = ""

        // 先落一条空记录：这样即使下面建连就崩了，历史里也有这条「说过的话」。
        entryId =
            runCatching { VoiceHistory.begin(ctx) }
                .onFailure { Timber.w(it, "建听写记录失败") }
                .getOrDefault("")
        audio =
            entryId
                .takeIf { it.isNotEmpty() }
                ?.let { VoiceAudio.create(ctx, it) }

        val client =
            VolcAsrClient(
                VolcAsrClient.Config(
                    apiKey = key,
                    resourceId = VoicePrefs.resourceId(ctx),
                    hotwords = if (VoicePrefs.hotwordsEnabled(ctx)) VoiceDictionary.hotwords(ctx) else emptyList(),
                    boostingTableId = if (VoicePrefs.hotwordsEnabled(ctx)) VoicePrefs.boostingTableId(ctx) else "",
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
                onChunk = {
                    audio?.write(it)
                    client.feed(it)
                },
                onError = { finishWithError(it) },
                onLevel = { level -> post { listener?.onLevel(level) } },
            )
        if (!rec.start()) {
            client.cancel()
            asr = null
            // 麦克风没起来，这条等于什么都没录到：别在历史里留空壳。
            discardCurrentEntry(ctx)
            post { listener?.onError("麦克风启动失败") }
            return false
        }
        recorder = rec
        isRecording = true
        startedAt = System.currentTimeMillis()
        return true
    }

    /** 停止录音、发结束包，等服务端二遍终稿。等不到就用一遍结果兜底。 */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        isWaitingFinal = true
        stopAt = System.currentTimeMillis()
        Log.i(TAG, "停止录音：发末包，等终稿（上限 ${FINAL_TIMEOUT_MS}ms），当前一遍结果 ${result.length} 字")

        // 兜底必须先挂上，再做收尾。
        //
        // 原来这一行在函数最后：只要 recorder.stop() / asr.stop() 中途抛出，
        // 定时器就永远挂不上，而 isWaitingFinal 已经为 true、面板已经是 Thinking，
        // 于是卡死且没有任何恢复路径（Thinking 状态下麦克风键是空操作）。
        // 兜底排在可能失败的动作之后，就不是兜底了。
        main.postDelayed({ finish(fromTimeout = true) }, FINAL_TIMEOUT_MS)

        // 收尾动作各自隔离：任何一个失败都不该连累其余步骤和上面的定时器。
        runCatching { recorder?.stop() }
        recorder = null
        // 先让音频落定，再等服务端终稿。这里就是这一轮 WAV 的收尾点：
        // 之后不论是终稿、超时兜底还是出错，用的都是这一份 [stoppedArtifact]。
        stoppedArtifact =
            runCatching { audio?.finish() }
                .onFailure { Timber.w(it, "停止录音时写盘失败") }
                .getOrNull()
        audio = null
        stoppedArtifact?.let { artifact ->
            runCatching { VoiceHistory.attachAudio(appContext, entryId, artifact) }
            Log.i(TAG, "录音已存：${artifact.fileName}（${artifact.durationMs}ms）")
        }
        runCatching { asr?.stop() }
        // 停录这一刻波形就该落地，别等终稿或超时。
        post { listener?.onLevel(0f) }
    }

    /** 直接丢弃，不上屏。**这是唯一会连音频一起删掉的路径**：用户明确说不要了。 */
    fun cancel() {
        isRecording = false
        isWaitingFinal = false
        stopped.set(true)
        recorder?.stop()
        recorder = null
        runCatching { audio?.abort() }
        audio = null
        stoppedArtifact = null
        asr?.cancel()
        asr = null
        result.setLength(0)
        main.removeCallbacksAndMessages(null)
        discardCurrentEntry(appContext)
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
        isRecording = false
        isWaitingFinal = false
        recorder?.stop()
        recorder = null
        asr?.cancel()
        asr = null
        result.setLength(0)
        val artifact = closedArtifact()
        val text = raw.trim()
        val mode = VoicePrefs.punctuationMode(appContext)
        val processed = runCatching { PostProcessor.process(text, appContext) }.getOrDefault(text)
        val finalText = runCatching { PunctuationProcessor.apply(processed, mode) }.getOrDefault(processed)
        val waited = elapsedSinceStop()
        stopAt = 0L
        val duration = if (startedAt > 0) System.currentTimeMillis() - startedAt else artifact?.durationMs ?: 0L
        startedAt = 0L
        if (fromTimeout) {
            Log.w(TAG, "没等到终稿，用一遍流式结果兜底：${text.length} 字，停止后 ${waited}ms")
        } else {
            Log.i(TAG, "终稿上屏：${text.length} 字，停止后 ${waited}ms")
        }
        if (finalText != text) {
            Log.i(TAG, "标点处理（${mode.label}）：${text.length} → ${finalText.length} 字")
        }
        archive(artifact, finalText, duration, fromTimeout)
        post { listener?.onLevel(0f) }
        if (finalText.isEmpty() && failedEntryId.isNotEmpty()) {
            // 没转出文字但音频在：别当成功收场，把重试入口递出去。
            val id = failedEntryId
            val message = if (fromTimeout) "没等到识别结果" else "没有识别出文字"
            post { listener?.onRetryable(id, message) }
        } else {
            post { listener?.onFinal(finalText) }
        }
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
        isWaitingFinal = false
        Log.e(TAG, "识别失败：$message（停止后 ${elapsedSinceStop()}ms）")
        stopAt = 0L
        // 失败也要留住：音频收尾、记录标成失败，用户能点重新识别。
        val artifact = closedArtifact()
        archive(artifact, "", 0L, fromTimeout = false, error = message)
        post { listener?.onLevel(0f) }
        post { listener?.onError(message) }
    }

    /**
     * 把这一轮落进历史。
     *
     * 成功：有文字，记 Completed。
     * 失败：哪怕一个字都没有，只要录到了声音就记 Failed —— 万物留痕。
     */
    private fun archive(
        artifact: VoiceAudio.Artifact?,
        finalText: String,
        durationMs: Long,
        fromTimeout: Boolean,
        error: String? = null,
    ) {
        val id = entryId
        entryId = ""
        if (id.isEmpty()) return
        runCatching {
            when {
                finalText.isNotEmpty() -> {
                    if (artifact != null) VoiceHistory.attachAudio(appContext, id, artifact)
                    VoiceHistory.markCompleted(appContext, id, finalText, durationMs)
                }

                artifact != null -> {
                    // 有声音、没文字：留着，标失败。超时兜底也只有音频，
                    // 说明服务端没给结果，重识别比丢掉强。
                    VoiceHistory.attachAudio(appContext, id, artifact)
                    val message = error ?: if (fromTimeout) "没等到识别结果" else "没有识别出文字"
                    val status =
                        if (error != null && isNetworkError(error)) {
                            VoiceHistory.Status.NoNetwork
                        } else {
                            VoiceHistory.Status.Failed
                        }
                    VoiceHistory.markFailed(appContext, id, status, message)
                    // 递一个「重试」入口给界面：这段录音还在，随时能再识别一次。
                    failedEntryId = id
                }

                else -> {
                    // 连一个字节的音频都没存下，也没识别出东西：这条没有留存价值。
                    VoiceHistory.discard(appContext, id)
                }
            }
        }.onFailure { Timber.w(it, "写入听写记录失败") }
    }

    /** 丢掉的条目：取消录音、麦克风起不来。 */
    private fun discardCurrentEntry(ctx: Context) {
        val id = entryId
        entryId = ""
        if (id.isEmpty()) return
        runCatching { VoiceHistory.discard(ctx, id) }
    }

    /**
     * 网络类错误 vs 鉴权/配置错误。决定重试按钮的分量：
     * 断网那句值得再试，Key 填错了再试一百次也没用。
     */
    private fun isNetworkError(message: String): Boolean {
        val m = message.lowercase()
        if (m.contains("401") || m.contains("unauthorized") || m.contains("鉴权")) return false
        return m.contains("timeout") ||
            m.contains("timed out") ||
            m.contains("connect") ||
            m.contains("network") ||
            m.contains("unable to resolve host") ||
            m.contains("socket") ||
            m.contains("closed") ||
            m.contains("broken pipe") ||
            m.contains("no route") ||
            m.contains("dns")
    }

    /**
     * 取这一轮的音频产物。
     *
     * 正常停止时 [stopRecording] 已经收尾并放进 [stoppedArtifact]；
     * 出错时 [finishWithError] 可能先到，那就地收尾。两条路径都只收一次。
     */
    private fun closedArtifact(): VoiceAudio.Artifact? {
        val pending = audio
        audio = null
        if (pending != null) {
            stoppedArtifact = runCatching { pending.finish() }
                .onFailure { Timber.w(it, "录音收尾失败") }
                .getOrNull()
        }
        val artifact = stoppedArtifact
        stoppedArtifact = null
        return artifact
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun elapsedSinceStop(): Long = if (stopAt > 0) System.currentTimeMillis() - stopAt else -1L

    private const val TAG = "JackVoice"

    /** 停止录音后等二遍终稿的上限。 */
    private const val FINAL_TIMEOUT_MS = 3000L
}
