/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import timber.log.Timber

/**
 * 说话期间把别的 App 的声音按住。
 *
 * 手机里同时放着歌、视频、直播的时候说话，那些声音会一起进麦克风，
 * 也盖住自己在说什么。桌面端早就有「听写时临时静音系统音频」，这里补上对应物。
 *
 * ## 为什么是两层，不是一层
 *
 * 音频焦点是**协商机制**：守规矩的播放器收到 `AUDIOFOCUS_LOSS` 会自己暂停，
 * 但**没申请焦点的应用完全收不到通知**，照放不误。直播类应用正是重灾区
 * （华为测过，直播类未申请焦点率达 39%，见 [MuteMode] 的注释）。
 *
 * 所以第二层直接把 `STREAM_MUSIC` 的音量归零——音量是系统层的，
 * 播放器没有拒绝的余地。B 站直播就是靠这一层才静得下来。
 *
 * ## 为什么不是整机静音
 *
 * Android 没有正规的「切静音模式」接口——那是 `WRITE_SECURE_SETTINGS`，
 * 要动响铃和来电，副作用太大。这里只压 `STREAM_MUSIC`：来电、闹钟、
 * 通知音都还在，它们本来就该响。
 *
 * ## 关于「音量卡在 0」这件事
 *
 * 归零之后如果进程直接被杀（输入法被系统回收、用户强停），原音量就丢了。
 * 所以归零之前先把原值写进 SharedPreferences，下次启动时
 * [recoverIfStuck] 发现「上次是静音中死掉的」就把音量还回去。
 * 只处理正常收尾是不够的——用户真正会遇到的恰恰是那些不正常的情况。
 */
object DictationAudioMute {
    // ── 焦点 ────────────────────────────────────────────────────────────

    private var manager: AudioManager? = null
    private var request: AudioFocusRequest? = null

    /** 焦点被系统收回（来电、另一个通话类 App）时回调，由调用方决定要不要停录。 */
    private var onLost: (() -> Unit)? = null

    @Volatile
    private var focused = false

    // ── 音量归零 ────────────────────────────────────────────────────────

    /**
     * 归零之前用户存的媒体音量。null 表示当前没压着。
     *
     * 0 是合法音量，不能拿 0 当「没记录」，所以用可空类型而不是哨兵值。
     */
    private var savedVolume: Int? = null

    /**
     * engage 时记下的 application context。
     *
     * **收尾路径不能依赖调用方传 Context**：输入法销毁那一路是 `service = null`
     * 之后再收尾的，拿不到就永远还不回音量。application context 活到进程结束，
     * 留着它是安全的。
     */
    private var appCtx: Context? = null

    private const val PREFS = "voice_audio_mute"
    private const val KEY_SAVED_VOLUME = "saved_music_volume"

    @Volatile
    private var engaged = false

    /** 当前生效的档位，供 UI 显示。空闲时是 [MuteMode.Off]。 */
    @Volatile
    var activeMode: MuteMode = MuteMode.Off
        private set

    /**
     * 现在是不是「别的应用本该没声音」。
     *
     * 键盘按键音用它判断该不该闭嘴：录音期间弹一下，那点声音会进麦克风。
     * 只看 engaged 而不是看具体档位——[MuteMode.Focus] 下用户本来就期望
     * 整个世界安静下来，这时候自己再响一下很突兀。
     */
    fun isAudiblyMuting(): Boolean = engaged || savedVolume != null

    /**
     * 进入录音时调用。
     *
     * @return false 表示没能按住（用户关了、系统拒绝、设备不支持）——
     *   录音照常开始，只是背景音还在响。
     */
    fun engage(
        ctx: Context,
        onLost: (() -> Unit)? = null,
    ): Boolean {
        if (engaged) return true
        val mode = VoicePrefs.muteMode(ctx)
        if (mode == MuteMode.Off) {
            activeMode = MuteMode.Off
            return false
        }
        appCtx = ctx.applicationContext
        val app = appCtx ?: return false
        activeMode = mode

        val focusGranted = acquireFocus(app, mode, onLost)
        // 焦点拿不到不代表失败：音量那一层照样能按住，尤其是直播。
        val volumeMuted = if (mode == MuteMode.Zero) muteStream(app) else false
        engaged = focusGranted || volumeMuted
        return engaged
    }

    /** 录音结束时调用。可重复调用；没拿住时是空操作。 */
    fun release() {
        // 焦点有可能拿到了但音量没压（Focus 档），所以两个条件分开判断，
        // 不能只看 engaged。
        val hasFocus = focused
        val hasVolume = savedVolume != null
        engaged = false
        activeMode = MuteMode.Off
        onLost = null
        if (hasFocus) abandonFocus()
        if (hasVolume) appCtx?.let { restoreStream(it) }
    }

    // ── 焦点 ────────────────────────────────────────────────────────────

    private fun focusListener(): AudioManager.OnAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                Timber.w("音频焦点被系统收回，停止本轮录音")
                onLost?.invoke()
            }
        }

    private fun acquireFocus(
        ctx: Context,
        mode: MuteMode,
        lost: (() -> Unit)?,
    ): Boolean {
        if (mode == MuteMode.Off) return false
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        manager = am
        onLost = lost

        // usage 用 USAGE_ASSISTANT 而非 VOICE_COMMUNICATION：后者会参与
        // 「通话中压低其他音频」的策略，听写不是通话，不该触发通话态。
        val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        val granted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener(focusListener())
                        // EXCLUSIVE 已经是「让对方暂停」；再显式声明不被压低，
                        // 避免个别机型把它当 ducking、只把歌变小一点。
                        .setWillPauseWhenDucked(false)
                        .build()
                request = req
                runCatching { am.requestAudioFocus(req) }.getOrNull() ==
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    am.requestAudioFocus(
                        focusListener(),
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                    )
                }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }

        focused = granted
        if (granted) {
            Timber.i("已获得音频焦点：守规矩的应用会让出声音")
        } else {
            Timber.w("音频焦点未获得，只能靠音量那一层")
        }
        return granted
    }

    private fun abandonFocus() {
        if (!focused) {
            request = null
            return
        }
        focused = false
        val am = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(null) }
        }
        request = null
        Timber.i("已交还音频焦点")
    }

    // ── 音量归零 ────────────────────────────────────────────────────────

    /**
     * 把媒体音量压到 0，并记下原值。
     *
     * 只动 `STREAM_MUSIC`：直播、视频、音乐、游戏都在这条流上，
     * 插着耳机时同样有效。
     */
    @SuppressLint("MissingPermission")
    private fun muteStream(ctx: Context): Boolean {
        if (savedVolume != null) return true
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        manager = am
        val current =
            runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return false
        // 原值先落盘再改音量：顺序反过来的话，中间被杀就丢了这个数。
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SAVED_VOLUME, current)
                .apply()
        }
        // 勿扰 / 静音模式下部分机型会抛 SecurityException，属于正常情况。
        val ok = runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }.isSuccess
        if (ok) {
            savedVolume = current
            Timber.i("媒体音量已归零（原值 $current）")
        } else {
            Timber.w("音量归零失败，可能被勿扰模式挡住")
            clearSaved(ctx)
        }
        return ok
    }

    /** 把音量还回去。失败不抛，留给 [recoverIfStuck]。 */
    private fun restoreStream(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        // 优先用内存里的值；进程刚起来时内存是空的，读落盘的那份。
        val saved =
            savedVolume ?: runCatching {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_SAVED_VOLUME, -1)
            }.getOrNull()?.takeIf { it >= 0 }
        savedVolume = null
        if (saved == null) return
        val ok = runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }.isSuccess
        if (ok) {
            clearSaved(ctx)
            Timber.i("媒体音量已恢复为 $saved")
        } else {
            Timber.w("音量恢复失败，留给下次启动自愈")
        }
    }

    private fun clearSaved(ctx: Context) {
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SAVED_VOLUME)
                .apply()
        }
    }

    /**
     * 上次静音途中进程就没了（输入法被回收、被强停、崩溃）。
     *
     * 在输入法服务启动时调一次。落盘里有原值就说明当时没走到 [release]，
     * 现在把音量还回去，否则用户下次放歌会发现手机没声音，还找不到原因。
     *
     * 只在**当前音量确实是 0** 时才动手：用户自己把音量调到 0 属于正常使用，
     * 而那种情况下落盘里不会有残留（[release] 会清掉），不会误伤。
     */
    fun recoverIfStuck(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
        if (saved < 0) return
        val current = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (current != 0) {
            // 用户已经自己调过音量了，那条记录已经过期，别再插手。
            clearSaved(ctx)
            return
        }
        val ok = runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }.isSuccess
        if (ok) {
            clearSaved(ctx)
            Timber.w("检测到上次静音未恢复，已把媒体音量还原为 $saved")
        }
    }
}
