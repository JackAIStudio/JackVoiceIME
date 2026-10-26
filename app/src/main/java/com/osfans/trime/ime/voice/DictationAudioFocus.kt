/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import timber.log.Timber

/**
 * 说话期间把别的 App 的音频按住。
 *
 * 手机里同时放着歌、视频、播客的时候说话，那些声音会一起进麦克风，
 * 也盖住自己在说什么。桌面端早就有「听写时临时静音系统音频」，这里补上对应物。
 *
 * ## 为什么不是整机静音
 *
 * Android 没有正规的「切静音模式」接口——那是 WRITE_SECURE_SETTINGS，
 * 要动响铃和来电，副作用太大；改 STREAM_MUSIC 音量等于替用户按了音量键，
 * 而且忘了恢复就是事故。这里走系统本来就有的一层：音频焦点。
 *
 * ## 为什么是 GAIN_TRANSIENT_EXCLUSIVE
 *
 * 更常见的 GAIN_TRANSIENT 只让音乐压低音量（ducking），说话时那点残余
 * 背景声照样进麦克风，等于白做。EXCLUSIVE 让正在放音的 App 收到
 * AUDIOFOCUS_LOSS 后按系统规范自行暂停，说完交还焦点它自动续播。
 *
 * usage 用 USAGE_ASSISTANT 而非 VOICE_COMMUNICATION：后者会参与「通话中
 * 压低其他音频」的策略，听写不是通话，不该触发通话态。
 *
 * 音频焦点由系统保证在进程死亡时回收，所以不需要桌面端那套
 * 「崩溃后下次启动修复静音状态」的恢复记录。
 */
object DictationAudioFocus {
    private var manager: AudioManager? = null
    private var request: AudioFocusRequest? = null

    /** 焦点被系统收回（来电、另一个通话类 App）时回调，由调用方决定要不要停录。 */
    private var onLost: (() -> Unit)? = null

    @Volatile
    private var engaged = false

    /**
     * 进入录音时调用。
     *
     * @return false 表示系统拒绝或设备不支持——录音照常开始，只是背景音按不住。
     */
    fun acquire(
        ctx: Context,
        onLost: (() -> Unit)? = null,
    ): Boolean {
        if (engaged) return true
        if (!VoicePrefs.muteOtherAudio(ctx)) return false
        val am = ctx.applicationContext.getSystemService(AudioManager::class.java) ?: return false
        manager = am
        this.onLost = onLost

        val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        val req =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        Timber.w("音频焦点被系统收回，停止本轮录音")
                        onLost?.invoke()
                    }
                }
                .setWillPauseWhenDucked(false)
                .build()
        request = req

        val result = runCatching { am.requestAudioFocus(req) }.getOrNull()
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        engaged = granted
        if (granted) {
            Timber.i("已获得音频焦点：说话期间其他应用让出声音")
        } else {
            Timber.w("音频焦点未获得（$result），背景音按不住，但录音继续")
        }
        return granted
    }

    /** 录音结束时调用。可重复调用；没拿住焦点时是空操作。 */
    fun release() {
        if (!engaged) return
        engaged = false
        onLost = null
        val am = manager ?: return
        request?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        request = null
        Timber.i("已交还音频焦点，其他应用可以继续播放")
    }
}
