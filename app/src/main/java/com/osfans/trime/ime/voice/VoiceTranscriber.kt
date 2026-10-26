/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber

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

    /** 正在重识别的那条记录。界面据此显示「正在重新识别」。 */
    @Volatile
    var busyEntryId: String? = null
        private set

    fun isBusy(entryId: String? = null): Boolean {
        val current = job ?: return false
        return entryId == null || current.entryId == entryId
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
        if (job != null) {
            // 说清楚是哪一条，别让用户对着「另一段录音」猜。
            val busy = job?.entryId
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
        val key = VoicePrefs.apiKey(ctx)
        if (key.isBlank()) {
            callback.onFailed(entryId, "请先在设置里填语音 API Key", retryable = false)
            return false
        }
        val pcm = VoiceAudio.readPcm(ctx, entry.fileName!!)
        if (pcm == null || pcm.isEmpty()) {
            callback.onFailed(entryId, "录音文件读不出来", retryable = false)
            return false
        }
        job = Job(ctx.applicationContext, entryId, pcm, key, callback)
        job?.start()
        main.post { busyEntryId = entryId }
        return true
    }

    /** 用户切走 / 关面板：把正在跑的这条收掉。 */
    fun cancel() {
        job?.abandon("已取消")
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

        private val timeout =
            Runnable {
                if (done) return@Runnable
                finish()
                callback.onFailed(entryId, "重新识别超时，请稍后再试", retryable = true)
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
            if (sent < chunks.size && c.queuedFrames() <= SEND_WINDOW) {
                if (c.feed(chunks[sent])) sent += 1
            }
            if (sent >= chunks.size) {
                c.stop()
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

    private const val CHUNK_BYTES = 3_200

    /** 发送窗口：队列里压着的帧数低于它才继续送。 */
    private const val SEND_WINDOW = 8L

    private const val SEND_INTERVAL_MS = 10L

    /** 整段回放的上限。一分钟的录音通常几秒就出结果。 */
    private const val TIMEOUT_MS = 60_000L
}
