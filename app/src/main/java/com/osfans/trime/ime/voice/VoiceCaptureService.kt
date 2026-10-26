/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import timber.log.Timber

/**
 * 麦克风前台服务。
 *
 * 它自己不干活，唯一作用是**把进程提到前台**——Android 11 起没有前台状态就拿不到
 * 麦克风（实测 `getInputForAttr: EX_SECURITY, missing perms for source 6`）。
 * 录音与识别在 [VoiceEngine] 里跑；这里只负责前台状态和那条通知。
 *
 * 与 Typeless 的 `AudioRecordingForegroundService` 是同一个位置的东西。
 *
 * ## 通知跟着会话状态走
 *
 * 以前标题写死「正在聆听…」：录音、处理中、正在重识别，通知栏里的字一个字都不变，
 * 用户自然分不清「它还在听」还是「它卡住了」。现在 [refresh] 按 [VoiceState]
 * 换标题，并且**没有活跃会话时自己收摊**——它本来就不该比录音活得久。
 *
 * ## 只由 VoiceSession 开关
 *
 * 起和停都必须成对出现在会话里（[VoiceSession.start] / [VoiceSession.finishCapture]）。
 * 别处再单开一个 start 就会重演「通知栏永远挂着正在聆听」那个 bug：
 * 两次 start/stop 是异步排队进 AMS 的，先后顺序不由调用方决定。
 */
class VoiceCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                // 走会话而不是直接掐引擎：面板/顶栏的状态要跟着回到空闲。
                if (VoiceSession.isBound) VoiceSession.cancel() else VoiceEngine.cancel()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                // 挂后台听写时唯一的「说完了」入口：停录、等终稿、自动上屏。
                if (VoiceSession.isBound) VoiceSession.stop() else VoiceEngine.stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 会话已经空闲了还收到 start（迟到的启动请求）：直接收摊，
        // 不要挂上一条谁都负责关的「正在聆听」。
        if (!VoiceSession.isCapturing()) {
            Timber.i("忽略过期的麦克风前台服务启动请求")
            stopSelf()
            return START_NOT_STICKY
        }
        // 前台化只是为了「更容易拿到麦克风」。MIUI 上 targetSdk 37 起，输入法进程
        // 起 microphone 型前台服务会被判为「不在资格状态」而抛 SecurityException——
        // 那是**非致命**的：录音本体在 VoiceEngine 里，能录就录，录不了会走错误回调。
        return try {
            goForeground()
            START_NOT_STICKY
        } catch (e: Exception) {
            Timber.w(e, "麦克风前台服务启动被拒，继续尝试直接录音")
            runCatching { stopSelf() }
            START_NOT_STICKY
        }
    }

    private fun goForeground() {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(title(), hint()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    private fun buildNotification(
        titleText: String,
        hintText: String,
    ): Notification {
        val cancelIntent =
            PendingIntent.getService(
                this,
                0,
                Intent(this, VoiceCaptureService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, VoiceCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_trime_status)
            .setContentTitle(titleText)
            .setContentText(hintText)
            // 点通知卡片 = 说完了（有些 ROM 不展示 action 按钮，卡片是唯一可靠的入口）。
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_trime_status, getString(R.string.voice_stop), stopIntent)
            .addAction(R.drawable.ic_trime_status, getString(R.string.voice_cancel), cancelIntent)
            .build()
    }

    private fun title(): String = when (VoiceSession.state) {
        VoiceState.Recording -> getString(R.string.voice_listening)
        VoiceState.Thinking -> getString(R.string.voice_thinking)
        VoiceState.Idle -> getString(R.string.voice_listening)
    }

    private fun hint(): String = when (VoiceSession.state) {
        VoiceState.Recording -> getString(R.string.voice_listening_hint)
        VoiceState.Thinking -> getString(R.string.voice_thinking_hint)
        VoiceState.Idle -> getString(R.string.voice_listening_hint)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.voice_channel_desc)
                setShowBadge(false)
            },
        )
    }

    override fun onDestroy() {
        if (running === this) running = null
        VoiceEngine.stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jackvoice_recording"
        private const val NOTIFICATION_ID = 0x4A43 // "JC"
        const val ACTION_CANCEL = "com.osfans.trime.voice.CANCEL"
        const val ACTION_STOP = "com.osfans.trime.voice.STOP"

        /**
         * 活着的那一个服务实例。
         *
         * 只为了刷通知内容——`NotificationManager.notify` 需要一个 `Context`，
         * 而通知的标题要跟着会话状态变。服务销毁时置空，不会把进程钉在内存里
         * （它本来也只活一轮录音）。
         */
        @Volatile
        private var running: VoiceCaptureService? = null

        /** 起前台服务 + 开始录音。调用方必须是用户刚点过的界面（输入法自己算）。 */
        fun start(ctx: Context) {
            val intent = Intent(ctx, VoiceCaptureService::class.java)
            runCatching { ContextCompat.startForegroundService(ctx, intent) }
                .onFailure { Timber.w(it, "startForegroundService 被拒") }
        }

        fun stop(ctx: Context) {
            // 先撤掉通知：stopService 是异步的，麦克风可能还要几十毫秒才真正松开，
            // 但「正在聆听」这几个字不该再留在通知栏里。
            runCatching { notificationManager(ctx)?.cancel(NOTIFICATION_ID) }
            runCatching { ctx.stopService(Intent(ctx, VoiceCaptureService::class.java)) }
        }

        /**
         * 按当前 [VoiceSession.state] 换掉通知的标题。
         *
         * 服务没起来、或会话已经收摊时什么都不做（后者顺手把残留的通知撤掉）。
         */
        fun refresh() {
            val svc = running ?: return
            if (!VoiceSession.isCapturing()) {
                runCatching { notificationManager(svc)?.cancel(NOTIFICATION_ID) }
                return
            }
            runCatching {
                notificationManager(svc)?.notify(NOTIFICATION_ID, svc.buildNotification(svc.title(), svc.hint()))
            }
        }

        private fun notificationManager(ctx: Context): NotificationManager? = ctx.getSystemService(NotificationManager::class.java)
    }
}
