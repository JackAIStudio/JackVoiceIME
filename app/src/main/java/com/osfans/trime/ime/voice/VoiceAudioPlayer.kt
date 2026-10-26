/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * 历史录音的试听。
 *
 * 「录音已经留下来了」这句话要能被验证：用户得能听见那段声音。
 * 这里只管播放一条，**同时只放一条**——点另一条就换过去，
 * 避免两条录音叠在一起（历史页里很容易连着点）。
 *
 * 播放器是全局单例：录音本来就在本机，不需要任何网络和解码准备，
 * `MediaPlayer` 起来就放。
 */
object VoiceAudioPlayer {
    /** 播放进度回调，[progress] 0..1。 */
    interface Listener {
        fun onPlayerChanged(
            playingEntryId: String?,
            progress: Float,
        )
    }

    private val main = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var currentId: String? = null
    private var currentDurationMs: Long = 0L
    private var listeners = mutableListOf<Listener>()

    /** 正在播 / 暂停的那条记录编号；null 表示没有。 */
    val playingEntryId: String? get() = currentId

    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
        listener.onPlayerChanged(currentId, progress())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * 点同一条：在「播 / 停」之间切。
     * 点另一条：停掉当前的，换成它。
     */
    fun toggle(
        ctx: Context,
        entry: VoiceHistory.Entry,
    ) {
        if (currentId == entry.id && player != null) {
            stop()
            return
        }
        play(ctx, entry)
    }

    private fun play(
        ctx: Context,
        entry: VoiceHistory.Entry,
    ) {
        stop()
        val name = entry.fileName ?: return
        val file = VoiceAudio.file(ctx, name) ?: return
        if (!file.isFile) {
            Timber.w("录音文件不在：$name")
            return
        }
        val mp =
            runCatching {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { stop() }
                    setOnErrorListener { _, what, extra ->
                        Timber.w("播放录音失败 what=$what extra=$extra")
                        stop()
                        true
                    }
                    prepare()
                    start()
                }
            }.onFailure { Timber.w(it, "打不开录音：$name") }.getOrNull() ?: return
        player = mp
        currentId = entry.id
        currentDurationMs = mp.duration.toLong().coerceAtLeast(0L)
        notifyChanged()
        scheduleTick()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        currentId = null
        currentDurationMs = 0L
        notifyChanged()
    }

    /** 当前进度 0..1。 */
    private fun progress(): Float {
        val mp = player ?: return 0f
        val duration = runCatching { mp.duration }.getOrDefault(0)
        if (duration <= 0) return 0f
        val position = runCatching { mp.currentPosition }.getOrDefault(0)
        return (position.toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun scheduleTick() {
        main.postDelayed(
            {
                if (player != null) {
                    notifyChanged()
                    scheduleTick()
                }
            },
            TICK_MS,
        )
    }

    private fun notifyChanged() {
        val id = currentId
        val p = progress()
        // 复制一份再遍历：回调里可能会 removeListener。
        listeners.toList().forEach { it.onPlayerChanged(id, p) }
    }

    private const val TICK_MS = 120L
}
