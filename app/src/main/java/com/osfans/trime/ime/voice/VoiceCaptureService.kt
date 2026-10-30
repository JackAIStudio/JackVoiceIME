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
 *
 * ## 收摊时必须显式摘掉前台
 *
 * 这是个坑：`NotificationManager.cancel()` 撤不掉**前台服务自己的**那条通知。
 * 只要服务还是前台状态，通知就由 AMS 托着，只有 `stopForeground(STOP_FOREGROUND_REMOVE)`
 * 才真的把它从通知栏拿走。以前 [stop] 只做了 cancel + stopService，
 * 服务一旦没被及时销毁（迟到/重复的 start、ROM 不肯立刻回收），
 * 那条「正在聆听」就永远停在通知栏里，用户下拉一看以为还在听。
 * 现在 [onDestroy] 和 [stop] 都显式摘，两条路任走其一都能清干净。
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
            ACTION_STOP -> {
                // 挂后台听写时唯一的「说完了」入口。
                //
                // 这里**必须标成来自通知栏**：用户没在看键盘，多半只是记一下，
                // 终稿回来会走剪贴板而不是插进光标。录音和文字照旧进历史。
                //
                // 同理**不看 isBound**：没有输入法服务时 VoiceSession 用这一轮记下的
                // 应用上下文收尾。以前这里退到 `VoiceEngine.stopRecording()`，
                // 而那条路没有会话监听者——终稿回来没人接，文字就只剩历史里那一条。
                //
                // 这里以前还有一条 ACTION_CANCEL 分支，走 VoiceSession.cancel()——
                // 删音频、删记录。通知栏上不该出现一个会把刚说的话扔掉的动作：
                // 用户按录音是想拿到字，不是想让系统帮忙毁掉素材。
                VoiceSession.stop(VoiceStopSource.Notification)
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
            // 前台状态**已经拿到**，现在才是开录的合法时机。
            //
            // 为什么不能由调用方先开录再起服务：`startForegroundService` 是异步的，
            // `VoiceSession.start()` 紧接着调 `VoiceEngine.startRecording()` 时
            // 进程还没进前台，麦克风会被系统按「后台应用」拒掉——从通知栏开录
            // （没有键盘、App 在后台）正是这个时序，实测点了没反应。
            VoiceSession.onForegroundReady()
            START_NOT_STICKY
        } catch (e: Exception) {
            Timber.w(e, "麦克风前台服务启动被拒，继续尝试直接录音")
            // 服务没能进前台：照样试着开录。MIUI 上这条也常被拒，
            // 但录音本体在 VoiceEngine 里，能录就录。
            VoiceSession.onForegroundReady()
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
        // 通知栏同时只留一条 JackVoice：真开始录了，那条「点一下就开始说话」就该让位，
        // 否则下拉里会并排两条，用户不知道该点哪个（点错那个还会把录音停掉）。
        runCatching { VoiceResidentService.stop(this) }
    }

    private fun buildNotification(
        titleText: String,
        hintText: String,
    ): Notification {
        // 录音中：标题旁挂一个系统计时器。它由系统自己走秒，我们一次 notify 就够，
        // 不用为了秒数每秒刷一次通知（那样锁屏会一直闪）。
        val recording = VoiceSession.state == VoiceState.Recording
        val startedAt = VoiceEngine.startedAtMillis
        val showElapsed = recording && startedAt > 0L
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
            // 实时文字通常一两行放不下：折叠时显示开头，下拉能看全。
            .setStyle(NotificationCompat.BigTextStyle().bigText(hintText.ifEmpty { titleText }))
            // 正文跟着识别结果变，但只有第一下该亮屏/响一声。
            .setOnlyAlertOnce(true)
            .apply {
                if (showElapsed) {
                    setUsesChronometer(true)
                    setWhen(startedAt)
                } else if (recording) {
                    // 拿不到开始时刻时退化成静态文案，别显示一个从 1970 年跑起的秒表。
                    setUsesChronometer(false)
                    setShowWhen(false)
                }
            }
            // 点通知卡片 = 说完了（有些 ROM 不展示 action 按钮，卡片是唯一可靠的入口）。
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 只留「完成」一颗。
            //
            // 以前这里还有一颗「取消」，点下去会把刚录的音频连同记录一起删掉。
            // 那个按钮解决的是一个不存在的需求：用户按下录音是为了拿到字，
            // 不是为了让系统帮忙毁掉素材。真说错了重说一遍就行，成本远低于
            // 「手滑点错、刚才那段话没了」。音频的留存不需要用户来担保。
            .addAction(R.drawable.ic_trime_status, getString(R.string.voice_stop), stopIntent)
            .build()
    }

    private fun title(): String = when (VoiceSession.state) {
        VoiceState.Recording -> getString(R.string.voice_listening)
        VoiceState.Thinking -> getString(R.string.voice_thinking)
        VoiceState.Idle -> getString(R.string.voice_listening)
    }

    private fun hint(): String = when (VoiceSession.state) {
        // 没网时这一轮只录音、不出字：通知栏也要说清，否则用户以为字还会出来。
        VoiceState.Recording -> {
            val preview = VoiceNotificationText.latestSentence(VoiceSession.lastPartial)
            when {
                preview.isNotEmpty() -> preview
                VoiceSession.asrDegraded -> getString(R.string.voice_listening_offline_hint)
                else -> getString(R.string.voice_listening_hint)
            }
        }

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
        // 显式摘掉前台：只 cancel 通知是没用的，前台服务那条由 AMS 托着，
        // 不 stopForeground 就会一直留在通知栏里。
        dropForeground()
        super.onDestroy()
    }

    /** 把服务从通知栏里彻底摘掉。撤不掉的写法只有一种：只 cancel、不 stopForeground。 */
    private fun dropForeground() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        runCatching { notificationManager(this)?.cancel(NOTIFICATION_ID) }
    }

    companion object {
        private const val CHANNEL_ID = "jackvoice_recording"
        private const val NOTIFICATION_ID = 0x4A43 // "JC"
        const val ACTION_STOP = "com.osfans.trime.voice.STOP"

        /**
         * 「已复制」那条通知走**独立的 channel 和 id**。
         *
         * 不能复用录音那条：那条是 `setOngoing(true)` 的前台通知，用的是同一个 id，
         * 两者会互相顶掉——要么回执把「正在聆听」挤掉，要么回执被当作业
         * 一起摘掉，用户什么都看不到。
         */
        private const val COPIED_CHANNEL_ID = "jackvoice_copied"
        private const val COPIED_NOTIFICATION_ID = 0x4A44

        /** 复制回执里最多展示多少字；全文在历史页。 */
        private const val COPIED_PREVIEW_CHARS = 80

        /** 文字预览最快多久刷一次通知。 */
        private const val PREVIEW_REFRESH_MS = 280L

        /** 上一次写进通知的那句预览，用来挡掉重复内容。 */
        @Volatile
        private var lastPreviewText = ""

        @Volatile
        private var lastPreviewAt = 0L

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
            // 上一轮的「已复制」回执这时候已经没有意义了：新的一轮已经开始，
            // 留着只会让用户以为这次也复制好了。
            runCatching { notificationManager(ctx)?.cancel(COPIED_NOTIFICATION_ID) }
        }

        fun stop(ctx: Context) {
            // 先摘前台再停服务：`stopService` 是异步的，麦克风可能还要几十毫秒才真正松开，
            // 但「正在聆听」这几个字不该再留在通知栏里。
            //
            // 这里必须显式 `stopForeground`：只 `cancel` 通知对前台服务无效——
            // 只要它还是前台，通知就由 AMS 托着。用户截图里那条一直不走的
            // 「正在聆听」就是这么来的。
            running?.let { svc ->
                runCatching {
                    ServiceCompat.stopForeground(svc, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            }
            runCatching { notificationManager(ctx)?.cancel(NOTIFICATION_ID) }
            runCatching { ctx.stopService(Intent(ctx, VoiceCaptureService::class.java)) }
            // 这条通知已经不在了，下一轮预览从空白开始重新判断要不要刷。
            resetPreviewThrottle()
        }

        /**
         * 按当前 [VoiceSession.state] 换掉通知的标题。
         *
         * 服务没起来、或会话已经收摊时什么都不做（后者顺手把残留的通知撤掉）。
         */
        fun refresh() {
            val svc = running ?: return
            if (!VoiceSession.isCapturing()) {
                // 会话已经结束：连前台一起摘，别只撤通知。
                svc.dropForeground()
                return
            }
            runCatching {
                notificationManager(svc)?.notify(NOTIFICATION_ID, svc.buildNotification(svc.title(), svc.hint()))
            }
        }

        /**
         * 流式结果到了：内容变了才刷，并且最多每 [PREVIEW_REFRESH_MS] 一次。
         *
         * 识别结果几十毫秒来一条，每条都 notify 会让通知栏不停重建、锁屏跟着闪，
         * 也白白耗电。文字预览只是「顺带看一眼」的东西，跟不上半个字无所谓；
         * 状态变化（开始/停止/出错）仍然走 [refresh] 立即生效。
         */
        fun refreshPreview() {
            val svc = running ?: return
            val preview = VoiceNotificationText.latestSentence(VoiceSession.lastPartial)
            val now = android.os.SystemClock.elapsedRealtime()
            if (preview == lastPreviewText) return
            if (now - lastPreviewAt < PREVIEW_REFRESH_MS) return
            lastPreviewText = preview
            lastPreviewAt = now
            refresh()
        }

        /** 关掉通知时把节流状态一起清掉，下一轮从头开始。 */
        private fun resetPreviewThrottle() {
            lastPreviewText = ""
            lastPreviewAt = 0L
        }

        /**
         * 告诉用户「文字已经放进剪贴板了，去粘吧」。
         *
         * 为什么非要一条通知而不是只弹 Toast：从通知栏停的时候键盘往往收着，
         * 输入法的 Toast 看不见；Android 12+ 还会把后台应用发的 Toast 直接吞掉。
         * 回执没了，用户就不知道到底复制没复制，只能去历史页确认——那这个功能
         * 等于没做。
         *
         * 这里**不依赖录音那条前台服务**：它这时候已经停了（终稿一到就收摊），
         * 而回执要比它活得久一点，所以拿应用上下文自己发一条普通通知。
         */
        fun notifyCopied(
            ctx: Context,
            text: String,
        ) {
            runCatching {
                val app = ctx.applicationContext
                ensureCopiedChannel(app)
                notificationManager(app)?.notify(COPIED_NOTIFICATION_ID, buildCopiedNotification(app, text))
            }.onFailure { Timber.w(it, "发「已复制」通知失败") }
        }

        private fun buildCopiedNotification(
            ctx: Context,
            text: String,
        ): Notification {
            val openIntent =
                PendingIntent.getActivity(
                    ctx,
                    2,
                    Intent(ctx, com.osfans.trime.ui.jackvoice.JackVoiceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val preview = VoiceNotificationText.latestSentence(text, COPIED_PREVIEW_CHARS)
            return NotificationCompat.Builder(ctx, COPIED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_trime_status)
                .setContentTitle(ctx.getString(R.string.voice_copied_title))
                .setContentText(ctx.getString(R.string.voice_copied_hint))
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                // 这条是「事办完了」的回执，不是正在进行的任务：可以划掉，也不挂着。
                // 静默（不出声、不震动）：用户刚刚自己按的「完成」，不需要再被通知一次，
                // 他要的是回到输入框时能看见「已经准备好了」。
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        private fun ensureCopiedChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(COPIED_CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    COPIED_CHANNEL_ID,
                    ctx.getString(R.string.voice_copied_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = ctx.getString(R.string.voice_copied_channel_desc)
                    setShowBadge(false)
                },
            )
        }

        private fun notificationManager(ctx: Context): NotificationManager? = ctx.getSystemService(NotificationManager::class.java)
    }
}
