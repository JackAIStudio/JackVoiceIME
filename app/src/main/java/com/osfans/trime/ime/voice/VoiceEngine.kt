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
 *
 * ## 识别会断，录音不会
 *
 * 这是这一层最要紧的约定：**麦克风与网络解耦**。
 *
 * 以前识别连接一失败就走 [finishWithError]，顺手把录音也收了——用户正说着话，
 * 地铁进隧道、VPN 抖一下，屏幕直接弹「识别失败：connection closed」，
 * 后面说的话一个字都没录下来，连重试的音频都没有。这完全说不过去：
 * 麦克风是本地的，网络是外部的，外部的坏了不该把本地的也停掉。
 *
 * 现在连接断掉只做一件事：把 [asr] 置空、记下 [asrError]，**录音继续**。
 * 停止时如果一遍结果都没有，就走「先存着，回头再识别」：
 *
 * ```
 * 连接断了 / 一开始就没网 → 录音照常 → 停下 → 音频落盘 → 历史里留一条可重试的记录
 *                                              → 用户点刷新键 / 历史页「重新转写」
 * ```
 *
 * 只有麦克风本身坏了（[VoiceRecorder] 报错）才真的结束这一轮——那种情况下
 * 也没有音频可以留。
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

        /**
         * 识别这条线断了（断网 / 连接被掐），**但录音还在继续**。
         *
         * 这不是失败提示，是状态变化：界面上那句「正在聆听」得换成
         * 「先录着，稍后识别」，否则用户以为字还会出来。
         *
         * @param message 断开的原因，已经是可以直接看的措辞
         */
        fun onAsrDegraded(message: String) {}

        /**
         * 麦克风拿不到（被别的应用占了 / 权限没了 / 系统关了）。
         *
         * 录音**还在继续**，只是收不到声音。界面该把「正在聆听」换成原因，
         * 让用户知道不是自己没说、也不是软件卡了。
         */
        fun onMicBlocked(reason: MicStatus.Reason) {}

        /** 麦克风又能收到声音了，把 [onMicBlocked] 那条提示撤掉。 */
        fun onMicRecovered() {}
    }

    /** 一轮听写的来路。决定界面上该说「正在听」还是「先录着」。 */
    enum class Mode {
        /** 连上了识别服务，边说边出字。 */
        Online,

        /** 没有网，或者连接中途断了：**只录音**，停下之后再识别。 */
        Offline,

        /** 本地模型在跑，边说边出字；跟网络无关。 */
        Local,
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: Listener? = null

    private var recorder: VoiceRecorder? = null
    private var asr: VolcAsrClient? = null
    private var localAsr: LocalAsrClient? = null

    /** 这一轮实际用的引擎。云端/本地只在开始录音时决定，中途不切。 */
    @Volatile
    var engine: AsrEngine = AsrEngine.Cloud
        private set

    /** 本轮音频的落盘器。null 表示这一轮存不下来（目录不可写等），识别照常。 */
    private var audio: VoiceAudio.Writer? = null

    private val result = StringBuilder()
    private val stopped = AtomicBoolean(true)

    /** 本次录音开始时间，用来算听写时长（统计页要用）。 */
    @Volatile
    private var startedAt = 0L

    /**
     * 这一轮录音的起点（墙上时间）；没在录时是 0。
     *
     * 通知栏的计时器直接拿它当 `setWhen()`：系统自己走秒，
     * 不必在这里起一个 Handler 每秒 notify 一次——锁屏、息屏、下拉面板
     * 三种情况都由系统保证对时，而且不耗电。
     */
    val startedAtMillis: Long get() = startedAt

    /** 点停止的时刻，用来区分「等到了终稿」和「超时拿一遍结果兜底」。 */
    private var stopAt = 0L

    /** 本轮的听写历史条目。录音一开始就建，失败也留着。 */
    private var entryId: String = ""

    /** 停止录音时收尾出来的 WAV，等终稿到了再挂到记录上。 */
    private var stoppedArtifact: VoiceAudio.Artifact? = null

    /**
     * 识别连接断掉的原因。非空表示这一轮**只录了音、没有识别**。
     *
     * 连接断掉时录音照旧，这个字段就是「停下来之后该怎么收尾」的依据：
     * 一遍结果都没有的话，音频直接进历史、留成可重试，而不是当成功收场。
     */
    @Volatile
    private var asrError: String? = null

    /** 这一轮的来路：连上了识别服务，还是一开始就没网。界面据此换提示语。 */
    @Volatile
    var mode: Mode = Mode.Online
        private set

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
     * 现在点一下能不能真的录起来。
     *
     * 给「常驻通知该不该挂出来」用：挂一条点了没反应的通知比不挂更糟。
     * 这里**不碰麦克风**（只查权限与配置），所以可以随时调，不会抢录音或占用硬件。
     *
     * 与 [startRecording] 开头的几个守卫一一对应，别只在一边加条件：
     * 那边拦下来会走 `onError`，而通知栏是没有地方显示这句错误的。
     */
    fun canStartCapture(ctx: Context): Boolean {
        if (isRecording || isWaitingFinal) return false

        // 麦克风权限 / AppOps / 系统总开关。注意只拦「确定拿不到」的那两种，
        // 与 VoiceSession.start() 的判定保持一致（别人正在录不等于我们录不到）。
        val probe = MicStatus.probeBeforeStart(ctx).reason
        if (probe == MicStatus.Reason.PermissionDenied || probe == MicStatus.Reason.MicMuted) return false

        return when (VoicePrefs.engine(ctx)) {
            AsrEngine.Cloud -> VoicePrefs.apiKey(ctx).isNotBlank()
            // 本地模型没下载完时 startRecording 会直接失败，通知栏没法解释这件事。
            AsrEngine.Local -> LocalModelManager.isReady(ctx)
        }
    }

    /**
     * 调用前必须已经 `startForeground(...)`，否则麦克风仍会被系统拒绝。
     *
     * @return 麦克风真的起来了才为 true；起不来时错误已经通过 listener 报出去。
     */
    fun startRecording(ctx: Context): Boolean {
        if (isRecording) return false
        val selected = VoicePrefs.engine(ctx)
        val key = VoicePrefs.apiKey(ctx)
        if (selected == AsrEngine.Cloud && key.isBlank()) {
            post { listener?.onError("请先在设置里填语音 API Key") }
            return false
        }
        if (selected == AsrEngine.Local && !LocalModelManager.isReady(ctx)) {
            post { listener?.onError("本地模型还没下载好，先在键盘上点「本地」下载") }
            return false
        }
        // 点「本地」时已经预热过一次；这里再调只是兜底，已加载时几乎无开销。
        if (selected == AsrEngine.Local) LocalAsrEngine.preload(LocalModelManager.modelDir(ctx))

        result.setLength(0)
        stopped.set(false)
        isWaitingFinal = false
        stoppedArtifact = null
        failedEntryId = ""
        asrError = null
        engine = selected

        // 先落一条空记录：这样即使下面建连就崩了，历史里也有这条「说过的话」。
        entryId =
            runCatching { VoiceHistory.begin(ctx) }
                .onFailure { Timber.w(it, "建听写记录失败") }
                .getOrDefault("")
        audio =
            entryId
                .takeIf { it.isNotEmpty() }
                ?.let { VoiceAudio.create(ctx, it) }

        // 云端才看网：本地模型本来就是为了没网时用。
        // 没网就不建连，直接进「先录着」，停下之后再走重新识别那条路。
        val online = VoiceNetwork.isOnline(ctx)
        mode =
            when {
                selected == AsrEngine.Local -> Mode.Local
                online -> Mode.Online
                else -> Mode.Offline
            }
        if (selected == AsrEngine.Cloud && !online) {
            Log.i(TAG, "当前没有网络：先录音，停止后再识别")
            asrError = "网络没连上"
        }

        if (selected == AsrEngine.Local) {
            Log.i(TAG, "本地识别：${LocalModelManager.modelDir(ctx).absolutePath}")
            val local =
                LocalAsrClient(
                    LocalModelManager.modelDir(ctx),
                    object : LocalAsrClient.Listener {
                        override fun onPartial(text: String) = handlePartial(text)

                        override fun onFinal(text: String) {
                            Log.i(TAG, "本地终稿到达：${text.length} 字，停止后 ${elapsedSinceStop()}ms")
                            handleFinal(text)
                        }

                        override fun onError(message: String) {
                            onAsrBroken("本地识别中断：$message")
                        }
                    },
                )
            localAsr = local
            local.start()
        } else if (online) {
            val client =
                VolcAsrClient(
                    VolcAsrClient.Config(
                        apiKey = key,
                        resourceId = VoicePrefs.resourceId(ctx),
                        hotwords = if (VoicePrefs.hotwordsEnabled(ctx)) VoiceDictionary.hotwords(ctx) else emptyList(),
                        boostingTableId = if (VoicePrefs.hotwordsEnabled(ctx)) VoicePrefs.boostingTableId(ctx) else "",
                    ),
                    object : VolcAsrClient.Listener {
                        override fun onPartial(text: String) = handlePartial(text)

                        override fun onFinal(text: String) {
                            Log.i(TAG, "服务端终稿到达：${text.length} 字，停止后 ${elapsedSinceStop()}ms")
                            handleFinal(text)
                        }

                        override fun onError(message: String) {
                            // 识别出错**不等于录音出错**：麦克风照旧，
                            // 这里只记下来，收尾时决定这段音频怎么安置。
                            onAsrBroken(message)
                        }

                        override fun onClosed() = Unit
                    },
                )
            asr = client
            client.start()
        }

        val rec =
            VoiceRecorder(
                appContext = ctx.applicationContext,
                onChunk = {
                    audio?.write(it)
                    asr?.feed(it)
                    localAsr?.feed(it)
                },
                // 麦克风自己坏了才是真的没法继续：这一轮到此为止。
                onError = { finishWithError(it) },
                onLevel = { level -> post { listener?.onLevel(level) } },
                // 拿不到麦克风：**不掐掉这一轮**。用户可能本来就在说话，
                // 也可能对方下一秒就松手。只把原因摆到界面上，让他自己决定继续还是停。
                onMicBlocked = { reason -> post { listener?.onMicBlocked(reason) } },
                onMicRecovered = { post { listener?.onMicRecovered() } },
            )
        if (!rec.start()) {
            asr?.cancel()
            localAsr?.cancel()
            asr = null
            localAsr = null
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

    /** 云端/本地共用的流式结果处理：只保留最后一版，UI 也只认这一版。 */
    private fun handlePartial(text: String) {
        result.setLength(0)
        result.append(text)
        post { listener?.onPartial(text) }
    }

    /** 云端/本地共用的终稿处理。 */
    private fun handleFinal(text: String) {
        if (text.isNotEmpty()) {
            result.setLength(0)
            result.append(text)
        }
        finish(fromTimeout = false)
    }

    /**
     * 识别这条线断了：**只把识别停掉，录音继续**。
     *
     * 断网、VPN 抖一下、服务端掐连接，都走这里。用户可能还在说话，
     * 这段声音仍然要完整地留在本机，停下之后再识别。
     */
    private fun onAsrBroken(message: String) {
        if (asr == null && localAsr == null && asrError != null) return
        Timber.w("识别连接断了，录音继续：$message")
        asrError = message
        mode = Mode.Offline
        val broken = asr
        asr = null
        runCatching { broken?.cancel() }
        val brokenLocal = localAsr
        localAsr = null
        runCatching { brokenLocal?.cancel() }
        // 界面上要看得出来「它还在录，只是暂时不出字」，而不是停在旧预览上。
        post {
            listener?.onPartial("")
            listener?.onAsrDegraded(message)
        }
    }

    /** 停止录音、发结束包，等服务端二遍终稿。等不到就用一遍结果兜底。 */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        isWaitingFinal = true
        stopAt = System.currentTimeMillis()
        val waitMs = if (localAsr != null) LOCAL_FINAL_TIMEOUT_MS else FINAL_TIMEOUT_MS
        Log.i(TAG, "停止录音：等终稿（上限 ${waitMs}ms），当前一遍结果 ${result.length} 字")

        // 兜底必须先挂上，再做收尾。
        //
        // 原来这一行在函数最后：只要 recorder.stop() / asr.stop() 中途抛出，
        // 定时器就永远挂不上，而 isWaitingFinal 已经为 true、面板已经是 Thinking，
        // 于是卡死且没有任何恢复路径（Thinking 状态下麦克风键是空操作）。
        // 兜底排在可能失败的动作之后，就不是兜底了。
        main.postDelayed({ finish(fromTimeout = true) }, waitMs)

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
        runCatching { localAsr?.stop() }
        // 没有识别连接（一开始就没网，或者中途断了）：不必等一个不会来的终稿，
        // 直接把这段录音安置成「可重试」。等下去只会让面板空转。
        if (asr == null && localAsr == null && asrError != null) {
            Log.i(TAG, "本轮没有识别连接，直接收尾：$asrError")
            finish(fromTimeout = true)
            return
        }
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
        localAsr?.cancel()
        localAsr = null
        result.setLength(0)
        main.removeCallbacksAndMessages(null)
        discardCurrentEntry(appContext)
        post { listener?.onLevel(0f) }
    }

    /**
     * 唯一出口。上屏文本可能是服务端终稿，也可能是 3 秒超时后用一遍结果兜底——
     * 两者行为完全一样，只有日志能区分，所以 [fromTimeout] 必须记下来。
     *
     * 还有一种情况也走这里：**从头到尾就没有识别连接**（没网 / 中途断了）。
     * 那时 [result] 是空的，音频却完整，于是被 [archive] 安置成可重试的记录。
     */
    private fun finish(fromTimeout: Boolean) {
        if (!stopped.compareAndSet(false, true)) {
            Log.d(TAG, "重复 finish 被忽略（来自${if (fromTimeout) "超时兜底" else "终稿"}）")
            return
        }
        main.removeCallbacksAndMessages(null)
        val raw = result.toString()
        val asrFailure = asrError
        isRecording = false
        isWaitingFinal = false
        recorder?.stop()
        recorder = null
        asr?.cancel()
        asr = null
        localAsr?.cancel()
        localAsr = null
        result.setLength(0)
        val artifact = closedArtifact()
        val text = raw.trim()
        val punctuationMode = VoicePrefs.punctuationMode(appContext)
        val processed = runCatching { PostProcessor.process(text, appContext) }.getOrDefault(text)
        val finalText = runCatching { PunctuationProcessor.apply(processed, punctuationMode) }.getOrDefault(processed)
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
            Log.i(TAG, "标点处理（${punctuationMode.label}）：${text.length} → ${finalText.length} 字")
        }
        archive(artifact, finalText, duration, fromTimeout, error = asrFailure?.takeIf { finalText.isEmpty() })
        post { listener?.onLevel(0f) }
        if (finalText.isEmpty() && failedEntryId.isNotEmpty()) {
            // 没转出文字但音频在：别当成功收场，把重试入口递出去。
            // 断网那种要说清「这段还在，能再识别一次」，而不是报一个错就完。
            val id = failedEntryId
            val message =
                when {
                    asrFailure != null -> "${VoiceFailure.describe(asrFailure)}，录音已保存"
                    fromTimeout -> "没等到识别结果"
                    else -> "没有识别出文字"
                }
            post { listener?.onRetryable(id, message) }
        } else {
            post { listener?.onFinal(finalText) }
        }
    }

    /**
     * **真的失败了**：麦克风坏了、录音中断。识别出错不走这里——那只是识别没了，
     * 录音还在（见 [onAsrBroken]）。
     */
    private fun finishWithError(message: String) {
        if (!stopped.compareAndSet(false, true)) return
        main.removeCallbacksAndMessages(null)
        recorder?.stop()
        recorder = null
        asr?.cancel()
        asr = null
        localAsr?.cancel()
        localAsr = null
        result.setLength(0)
        isRecording = false
        isWaitingFinal = false
        Log.e(TAG, "录音失败：$message（停止后 ${elapsedSinceStop()}ms）")
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
                    val message =
                        when {
                            error != null -> VoiceFailure.describe(error)
                            fromTimeout -> "没等到识别结果"
                            else -> "没有识别出文字"
                        }
                    val status =
                        if (error != null && VoiceFailure.isNetwork(error)) {
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

    /** 停止录音后等云端二遍终稿的上限。 */
    private const val FINAL_TIMEOUT_MS = 3000L

    /** 本地没有网络往返，但可能要排完队里最后几块音频；给 5 秒。 */
    private const val LOCAL_FINAL_TIMEOUT_MS = 5000L
}
