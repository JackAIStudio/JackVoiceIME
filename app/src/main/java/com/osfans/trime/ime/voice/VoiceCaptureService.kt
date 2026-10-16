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
 */
class VoiceCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                VoiceEngine.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
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
        val cancelIntent =
            PendingIntent.getService(
                this,
                0,
                Intent(this, VoiceCaptureService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_trime_status)
                .setContentTitle(getString(R.string.voice_listening))
                .setContentText(getString(R.string.voice_listening_hint))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, getString(R.string.voice_cancel), cancelIntent)
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
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
        VoiceEngine.stopRecording()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jackvoice_recording"
        private const val NOTIFICATION_ID = 0x4A43 // "JC"
        const val ACTION_CANCEL = "com.osfans.trime.voice.CANCEL"

        /** 起前台服务 + 开始录音。调用方必须是用户刚点过的界面（输入法自己算）。 */
        fun start(ctx: Context) {
            val intent = Intent(ctx, VoiceCaptureService::class.java)
            runCatching { ContextCompat.startForegroundService(ctx, intent) }
                .onFailure { Timber.w(it, "startForegroundService 被拒") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, VoiceCaptureService::class.java)) }
        }
    }
}
