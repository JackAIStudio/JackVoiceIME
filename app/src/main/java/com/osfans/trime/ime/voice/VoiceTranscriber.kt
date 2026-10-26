/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.io.File

/**
 * 把**已经存在本地的那段录音**重新送进识别，补出文字。
 *
 * 和现场录音共用同一条流式通道（[VolcAsrClient]）与同一套后处理，
 * 所以补出来的字跟当时录的会是同一个结果，不存在「重试反而变差」。
 *
 * 与桌面版 `transcribe_pcm` 的节奏一致：3200 字节一块、块间 10ms，
 * 回放比实时快，但不会一次性全灌进去——一次性灌会撑爆发送队列，
 * 甚至出现「末包发出去之后又来一块」。
 *
 * 一次只跑一条：用户连点两次不该把同一条录音识别两遍。
 */
object VoiceTranscriber {
    interface Callback {
        /** 本地重识别的解码进度（0..1）；云端没有分片进度，不会回调。 */
        fun onProgress(
            entryId: String,
            progress: Float,
        ) = Unit

        fun onDone(
            entryId: String,
            text: String,
        )

        /** @param retryable 音频还在、多半是网络问题，稍后还能再试 */
        fun onFailed(
            entryId: String,
            message: String,
            retryable: Boolean,
        )
    }

    private val main = Handler(Looper.getMainLooper())

    private var job: Job? = null
    private var localJob: LocalJob? = null

    /** 正在重识别的那条记录。界面据此显示「正在重新识别」。 */
    @Volatile
    var busyEntryId: String? = null
        private set

    fun isBusy(entryId: String? = null): Boolean {
        val current = job?.entryId ?: localJob?.entryId ?: return false
        return entryId == null || current == entryId
    }

    /**
     * 重新识别一条已存录音。
     *
     * @return false 表示没跑起来（正在忙 / 没有音频 / 没配 Key），
     *         原因已经通过 [callback] 报出去了。
     */
    fun transcribe(
        ctx: Context,
        entryId: String,
        callback: Callback,
    ): Boolean {
        if (job != null || localJob != null) {
            // 说清楚是哪一条，别让用户对着「另一段录音」猜。
            val busy = job?.entryId ?: localJob?.entryId
            val busyEntry = busy?.let { runCatching { VoiceHistory.find(ctx, it) }.getOrNull() }
            val at =
                busyEntry?.let {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(it.atMillis))
                }
            val message =
                if (at != null) {
                    "$at 那条正在重新识别，完成后再点这条"
                } else {
                    "另一条正在重新识别，请稍等"
                }
            callback.onFailed(entryId, message, retryable = true)
            return false
        }
        val entry = VoiceHistory.find(ctx, entryId)
        if (entry == null || !entry.hasAudio) {
            callback.onFailed(entryId, "这条记录没有可用的录音", retryable = false)
            return false
        }
        val audioFile = VoiceAudio.file(ctx, entry.fileName!!)
        if (audioFile == null || !audioFile.isFile || audioFile.length() <= 44) {
            callback.onFailed(entryId, "录音文件读不出来", retryable = false)
            return false
        }
        if (VoicePrefs.engine(ctx) == AsrEngine.Local) {
            if (!LocalModelManager.isReady(ctx)) {
                callback.onFailed(entryId, "本地模型还没下载好", retryable = false)
                return false
            }
            localJob =
                LocalJob(
                    ctx = ctx.applicationContext,
                    entryId = entryId,
                    audioFile = audioFile,
                    durationMs = entry.durationMs,
                    callback = callback,
                )
            busyEntryId = entryId
            localJob?.start()
            return true
        }
        val pcm = VoiceAudio.readPcm(ctx, entry.fileName!!)
        if (pcm == null || pcm.isEmpty()) {
            callback.onFailed(entryId, "录音文件读不出来", retryable = false)
            return false
        }
        val key = VoicePrefs.apiKey(ctx)
        if (key.isBlank()) {
            callback.onFailed(entryId, "请先在设置里填语音 API Key", retryable = false)
            return false
        }
        job = Job(ctx.applicationContext, entryId, pcm, key, callback)
        busyEntryId = entryId
        job?.start()
        return true
    }

    /** 用户切走 / 关面板：把正在跑的这条收掉。 */
    fun cancel() {
        job?.abandon("已取消")
        localJob?.abandon("已取消")
    }

    /**
     * 一次回放。所有回调都先回到主线程再碰状态，
     * OkHttp 的线程不该直接改 [VoiceHistory]。
     */
    private class Job(
        private val ctx: Context,
        val entryId: String,
        private val pcm: ByteArray,
        private val key: String,
        private val callback: Callback,
    ) {
        private val chunks = pcm.toList().chunked(CHUNK_BYTES).map { it.toByteArray() }

        private var sent = 0
        private var opened = false
        private var done = false
        private var client: VolcAsrClient? = null

        /**
         * 空转次数：`pump` 每一拍都在等「发送队列空出来」，可队列如果一直不空，
         * `sent` 就永远不前进，而每 10ms 一拍会一直排下去——直到 60 秒总超时。
         * 用户看到的就是「正在重新识别…」转一分钟。
         *
         * 连续空转超过 [MAX_STALL_TICKS] 就当这条送不出去了，立刻收尾报错，
         * 别让人干等。
         */
        private var stalledTicks = 0

        private val timeout =
            Runnable {
                if (done) return@Runnable
                finish()
                callback.onFailed(entryId, "重新识别超时，请稍后再试", retryable = true)
            }

        /**
         * 建连看门狗：`onOpen` 不来时 `pump` 根本不会启动，只能靠 60 秒总超时兜底。
         * 那太久了——连不上就说连不上。
         */
        private val openTimeout =
            Runnable {
                if (done || opened) return@Runnable
                finish()
                callback.onFailed(entryId, "连接识别服务超时，请检查网络", retryable = true)
            }

        fun start() {
            val c =
                VolcAsrClient(
                    VolcAsrClient.Config(
                        apiKey = key,
                        resourceId = VoicePrefs.resourceId(ctx),
                        hotwords = if (VoicePrefs.hotwordsEnabled(ctx)) VoiceDictionary.hotwords(ctx) else emptyList(),
                        boostingTableId = if (VoicePrefs.hotwordsEnabled(ctx)) VoicePrefs.boostingTableId(ctx) else "",
                    ),
                    object : VolcAsrClient.Listener {
                        override fun onPartial(text: String) = Unit

                        override fun onFinal(text: String) {
                            if (done) return
                            val processed = postProcess(ctx, text)
                            finish()
                            if (processed.isBlank()) {
                                callback.onFailed(entryId, "这段录音里没听到人声", retryable = false)
                            } else {
                                callback.onDone(entryId, processed)
                            }
                        }

                        override fun onError(message: String) {
                            if (done) return
                            Timber.w("重新识别失败 $entryId：$message")
                            finish()
                            callback.onFailed(entryId, message, retryable = true)
                        }

                        override fun onClosed() {
                            // 正常情况下终稿先到；只关不发说明对端提前收摊了。
                            if (done) return
                            finish()
                            callback.onFailed(entryId, "连接被对端关闭", retryable = true)
                        }

                        override fun onOpen() {
                            if (done || opened) return
                            opened = true
                            pump()
                        }
                    },
                )
            client = c
            c.start()
            main.postDelayed(timeout, TIMEOUT_MS)
            main.postDelayed(openTimeout, OPEN_TIMEOUT_MS)
        }

        private fun postProcess(
            context: Context,
            text: String,
        ): String {
            val mode = VoicePrefs.punctuationMode(context)
            val processed = runCatching { PostProcessor.process(text, context) }.getOrDefault(text)
            return runCatching { PunctuationProcessor.apply(processed, mode) }.getOrDefault(processed)
        }

        /**
         * 一块一块送。队列里压着的帧多了就等下一拍，
         * 顺带把「末包之后又来一块」从根上避掉。
         */
        private fun pump() {
            if (done) return
            val c = client ?: return
            var progressed = false
            if (sent < chunks.size && c.queuedFrames() <= SEND_WINDOW) {
                if (c.feed(chunks[sent])) {
                    sent += 1
                    progressed = true
                }
            }
            if (sent >= chunks.size) {
                c.stop()
                return
            }
            // 队列一直满、送不进去：别无限空转，攒够次数就认输。
            // 否则 `sent` 卡住不动，`pump` 每 10ms 排一次，直到 60 秒总超时——
            // 用户盯着「正在重新识别…」干等一分钟，就是这么来的。
            if (progressed) {
                stalledTicks = 0
            } else if (++stalledTicks > MAX_STALL_TICKS) {
                finish()
                callback.onFailed(entryId, "这段录音送不出去，请稍后再试", retryable = true)
                return
            }
            main.postDelayed({ pump() }, SEND_INTERVAL_MS)
        }

        /** 收尾：挪回主线程、断开连接、让总闸重新可以接活。 */
        private fun finish() {
            if (done) return
            done = true
            client?.cancel()
            client = null
            main.removeCallbacks(timeout)
            main.removeCallbacks(openTimeout)
            // 结果与状态要落在同一条时间线上，别让 UI 先看到「空闲」再看到文字。
            main.post {
                if (VoiceTranscriber.job === this) VoiceTranscriber.job = null
                busyEntryId = null
            }
        }

        fun abandon(reason: String) {
            if (done) return
            finish()
            main.post { callback.onFailed(entryId, reason, retryable = true) }
        }
    }

    /**
     * 本地重识别：读已落盘的 PCM，交给共享的 sherpa recognizer 做一次离线解码。
     *
     * 不建 WebSocket、不看网络；本地模式下这条路径不会再出现「正在重新识别…」
     * 卡在等服务器。超过 [LOCAL_TIMEOUT_MS] 由看门狗收掉，界面恢复可点。
     */
    private class LocalJob(
        private val ctx: Context,
        val entryId: String,
        private val audioFile: File,
        private val durationMs: Long,
        private val callback: Callback,
    ) {
        @Volatile
        private var done = false
        private val worker = Thread({ run() }, "jackvoice-local-retranscribe")

        /** 按音频时长给足余量：4 分钟录音不能再用固定 30 秒一刀切。 */
        private val timeoutMs = (durationMs * 2 + 15_000L).coerceAtLeast(LOCAL_TIMEOUT_MS)
        private val timeout = Runnable { fail("本地重新识别超时，请稍后再试") }

        fun start() {
            worker.start()
            main.postDelayed(timeout, timeoutMs)
        }

        private fun run() {
            val result =
                runCatching {
                    LocalAsrEngine.transcribe(
                        modelDir = LocalModelManager.modelDir(ctx),
                        pcmFile = audioFile,
                        onProgress = { progress ->
                            main.post { if (!done) callback.onProgress(entryId, progress) }
                        },
                        isCancelled = { done },
                    )
                }
            main.post {
                if (done) return@post
                done = true
                cleanup()
                result
                    .onSuccess { raw ->
                        val processed = postProcess(ctx, raw)
                        if (processed.isBlank()) {
                            callback.onFailed(entryId, "这段录音里没听到人声", retryable = false)
                        } else {
                            callback.onDone(entryId, processed)
                        }
                    }.onFailure {
                        Timber.w(it, "本地重新识别失败 $entryId")
                        callback.onFailed(entryId, it.message ?: "本地重新识别失败", retryable = true)
                    }
            }
        }

        private fun fail(message: String) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            cleanup()
            callback.onFailed(entryId, message, retryable = true)
        }

        fun abandon(reason: String) = fail(reason)

        private fun cleanup() {
            main.post {
                if (VoiceTranscriber.localJob === this) VoiceTranscriber.localJob = null
                busyEntryId = null
            }
        }

        private fun postProcess(
            context: Context,
            text: String,
        ): String {
            val mode = VoicePrefs.punctuationMode(context)
            val processed = runCatching { PostProcessor.process(text, context) }.getOrDefault(text)
            return runCatching { PunctuationProcessor.apply(processed, mode) }.getOrDefault(processed)
        }
    }

    private const val CHUNK_BYTES = 3_200

    /** 发送窗口：队列里压着的帧数低于它才继续送。 */
    private const val SEND_WINDOW = 8L

    private const val SEND_INTERVAL_MS = 10L

    /**
     * 连续多少拍送不进去就认输。
     *
     * 一拍 10ms，200 拍 = 2 秒。整段回放正常几秒就推完了，
     * 连续两秒一帧都推不进去，基本就是连接废了，没必要再耗。
     */
    private const val MAX_STALL_TICKS = 200

    /**
     * 建连上限。
     *
     * `onOpen` 不来时 `pump` 不会启动，只剩总超时兜底——那要 60 秒。
     * 断网、DNS 卡住时用户得盯着「正在重新识别…」转一分钟，太离谱。
     * 15 秒足够走完一次 TCP + TLS 握手，走不完就是连不上。
     */
    private const val OPEN_TIMEOUT_MS = 15_000L

    /** 整段回放的上限。一分钟的录音通常几秒就出结果。 */
    private const val TIMEOUT_MS = 60_000L

    /** 本地重识别的最短看门狗；长录音按 durationMs 放宽。 */
    private const val LOCAL_TIMEOUT_MS = 30_000L
}
