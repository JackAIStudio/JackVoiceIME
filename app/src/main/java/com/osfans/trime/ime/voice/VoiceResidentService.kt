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
import com.osfans.trime.ui.jackvoice.JackVoiceActivity
import timber.log.Timber

/**
 * 常驻通知：**不用点输入框，下拉就能开录**。
 *
 * 以前只有录音时才有通知（[VoiceCaptureService] 那条「正在聆听」），
 * 而它的存在前提是「已经在录了」。于是想开始录只能先展开键盘、进语音面板，
 * 再点那颗麦克风——键盘收着的时候完全没有入口。
 *
 * ## 为什么不复用 VoiceCaptureService
 *
 * 那条通知是**麦克风前台服务**（`foregroundServiceType=microphone`）的。
 * 它活着就意味着「正在拿麦克风的资格」，而空闲时我们没有这个资格，
 * 也不该有：MIUI 上 targetSdk 37 的输入法进程起 microphone 型前台服务
 * 会被判「不在资格状态」直接抛 SecurityException（见 [VoiceCaptureService] 注释）。
 *
 * 所以这里另起一个 **specialUse** 型的前台服务，只在空闲时挂着一条
 * 「点一下就开始说话」的通知；真开始录了就撤掉，让位给录音那条。
 * 两条生命周期首尾相接，任何时刻通知栏里都只有一条 JackVoice。
 *
 * ## 为什么是前台服务，不是普通通知
 *
 * 参照 [LockScreenWeather] 的实测：HyperOS 上普通通知会被折叠进「更多通知」、
 * 锁屏也看不到；只有前台服务那条（带 FOREGROUND_SERVICE flag）才能稳稳置顶常驻。
 * 另外 Android 12+ 从后台发普通通知会被限流，前台服务天然绕开这一层。
 *
 * ## 点一下就开录，系统为什么允许
 *
 * Android 14+ 禁止后台起「需要 while-in-use 权限」的前台服务（麦克风就在其中）——
 * 但**「通过与通知互动而启动」是官方明列的豁免项**
 * （developer.android.com/develop/background-work/services/fgs/restrictions-bg-start）。
 * 这条通知的点击目标指向 [VoiceResidentService] 自己，由它去起麦克风前台服务；
 * dumpsys 里那条 PendingIntent 带着 `allowlist: NOTIFICATION_SERVICE`，
 * 就是系统认这条豁免的凭据。
 *
 * 还有一条同样关键的豁免：**系统当前的输入法**可以后台启动前台服务。
 * 只要用户选中的是 JackVoice，这条路的资格就更稳。
 *
 * ## 为什么点击目标是「自己」而不是输入法服务
 *
 * 输入法服务带 `BIND_INPUT_METHOD` 权限，`startService` 打不进去
 * （实测 `Requires permission android.permission.BIND_INPUT_METHOD`）；
 * 而且键盘没展开时它可能根本不存在。常驻服务一定活着——这条通知就是它发的。
 *
 * ## 只在「真的能录」的时候挂出来
 *
 * 没麦克风权限、API Key 空着、本地模型没下载完——这三种情况挂一条点了没反应的
 * 常驻通知比不挂更糟。所以 [refresh] 会先判断，不具备条件就把自己收掉，
 * 用户去设置里补完再回来（设置页改完会调 [refresh]）。
 */
class VoiceResidentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 点通知**不走这里**：卡片指向 [VoiceQuickStartActivity] 那个透明跳板，
        // 由它调 [toggleDictation]。实测 contentIntent 设成本服务时，
        // 点卡片只会把服务重新提前台，动作根本送不进 onStartCommand。
        // 这个服务只负责「把那条通知挂起来」。
        if (!canReside(this)) {
            // 开关关了 / 缺条件：别留一条点不动、划不掉的常驻通知。
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            goForeground()
            START_STICKY
        } catch (e: Exception) {
            Timber.w(e, "常驻通知前台服务启动被拒")
            runCatching { stopSelf() }
            START_NOT_STICKY
        }
    }

    private fun goForeground() {
        ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "jackvoice_resident"

        /**
         * 与录音通知（0x4A43）、复制回执（0x4A44）错开的 id。
         *
         * 复用同一个 id 会让两条通知互相顶掉：刚开始录，常驻那条就把
         * 「正在聆听」挤掉了，用户看到的标题永远停在「点一下就开始说话」。
         */
        private const val NOTIFICATION_ID = 0x4A45

        /** 通知栏／下拉里点「开始说话」：直接落到输入法服务的录音动作。 */
        const val ACTION_START = "com.osfans.trime.voice.START"

        /** 划掉之后补发的广播（MIUI 上 OnGoing 照样能被左滑划掉）。 */
        private const val ACTION_REPOST = VoiceResidentReceiver.ACTION_REPOST

        /** 恢复常驻（设置页改完、开机、应用更新后）。 */
        private const val ACTION_ENSURE = "com.osfans.trime.voice.RESIDENT_ENSURE"

        private const val RC_START = 10
        private const val RC_OPEN = 11
        private const val RC_REPOST = 12

        @Volatile
        private var running: VoiceResidentService? = null

        /**
         * 挂上常驻通知。已经挂着就什么都不做（可重复调用）。
         *
         * 调用方：设置页打开开关、开机广播、应用更新、输入法服务创建。
         */
        fun ensure(ctx: Context) {
            if (!canReside(ctx)) {
                // 条件不具备（关开关 / 没权限 / 没配引擎）：**只跳过，不主动收**。
                //
                // 这里以前会调 stop()，但「录音刚结束」那一瞬 `canStartCapture` 仍可能
                // 返回 false（引擎还在收尾），于是刚录完就把入口收了——用户想接着录
                // 第二条时发现通知栏空了。真该消失的场景（关开关）由 stop() 自己调。
                return
            }
            // 正在录音：让位给「正在聆听」那条。录音结束会自己回来（见 VoiceSession）。
            if (VoiceSession.isCapturing()) {
                stop(ctx)
                return
            }
            if (running != null) return
            val intent = Intent(ctx, VoiceResidentService::class.java).setAction(ACTION_ENSURE)
            runCatching { ContextCompat.startForegroundService(ctx, intent) }
                .onFailure { Timber.w(it, "拉起常驻听写通知失败") }
        }

        /** 摘掉常驻通知（开关关闭、开始录音、输入法被回收时）。 */
        fun stop(ctx: Context) {
            val svc = running
            // 只 cancel 通知对前台服务无效：只要它还是前台，通知就由 AMS 托着。
            if (svc != null) {
                runCatching { ServiceCompat.stopForeground(svc, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            }
            runCatching { notificationManager(ctx)?.cancel(NOTIFICATION_ID) }
            runCatching { ctx.stopService(Intent(ctx, VoiceResidentService::class.java)) }
        }

        /**
         * 跟着会话状态走：录音开始→收起，录音结束→挂回来。
         *
         * 从 [VoiceSession] 的收尾出口调，别在别处再记一遍。
         */
        fun onSessionChanged(ctx: Context) {
            if (!canReside(ctx)) return
            if (VoiceSession.isCapturing()) {
                stop(ctx)
            } else {
                ensure(ctx)
            }
        }

        /**
         * 点一下 = 开始说 / 说完了，两态一个按钮。
         *
         * 与录音那条通知的卡片语义一致：录音中点它等于「停」，空闲时点它等于「录」。
         * 用户不必记两套规矩，也不会因为点错把正在录的那一轮掐掉。
         *
         * 从 [VoiceResidentReceiver] 进来（广播），**不经过输入法服务**：
         * 键盘没展开时那个服务可能根本没被系统创建，而用户要的恰恰是那种时候也能录。
         */
        fun toggleDictation(ctx: Context) {
            // 状态和引擎偶尔会错开（进程被重建、上一轮中途失败）。先按引擎对齐一次：
            // 引擎其实已经闲着却还写着「录音中」时，这一下点击要用来**开录**，
            // 不能白白花在「停止」上——用户看到的就是「点第一下没反应，点第二下才录」。
            VoiceSession.reconcileIfStale()

            when {
                VoiceSession.isCapturing() -> VoiceSession.stop(VoiceStopSource.Notification)

                else -> {
                    if (!VoiceSession.start(ctx)) {
                        // 没起来（没权限 / 没配 Key）：通知栏没法解释，退回主界面让用户看。
                        Timber.w("从通知栏开录失败，打开主界面")
                        runCatching {
                            ctx.startActivity(
                                Intent(ctx, JackVoiceActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
        }

        /**
         * 现在够不够条件挂这条常驻通知。
         *
         * 三个「点了也不会有声音」的情况都挡掉：
         * 用户关了开关、没给麦克风权限、识别引擎还没配好。
         */
        fun canReside(ctx: Context): Boolean {
            if (!VoicePrefs.residentEnabled(ctx)) return false
            // 输入法没被选中时，点了通知也落不到录音动作上（那张卡片会打开 App）。
            if (!VoiceEngine.canStartCapture(ctx)) return false
            return true
        }

        private fun buildNotification(ctx: Context): Notification {
            val title = ctx.getString(R.string.voice_resident_title)
            val hint = ctx.getString(R.string.voice_resident_hint)

            val startIntent =
                PendingIntent.getActivity(
                    ctx,
                    RC_START,
                    // 用**透明 Activity** 投递，不用 startService、也不用广播。
                    //
                    // 实测（HyperOS 3 / Redmi Turbo 5）三条路只有这条走得通：
                    // - startService → 点卡片只把服务重新提前台，动作送不进 onStartCommand；
                    // - getBroadcast → 点了**完全没反应**，广播队列里连记录都没有；
                    // - getActivity  → 正常。MIUI 只把「点通知 = 开个界面」当正经点击。
                    //
                    // 这个 Activity 是透明的、进 onCreate 就 finish，用户看不到它；
                    // 顺带把「应用有一个可见界面」这条麦克风豁免也拿到了。
                    Intent(ctx, VoiceQuickStartActivity::class.java)
                        .setAction(VoiceQuickStartActivity.ACTION_QUICK_START)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

            val openIntent =
                PendingIntent.getActivity(
                    ctx,
                    RC_OPEN,
                    Intent(ctx, JackVoiceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_trime_status)
                .setContentTitle(title)
                .setContentText(hint)
                .setStyle(NotificationCompat.BigTextStyle().bigText(hint))
                // 整张卡片和「开始说话」按钮走同一个动作：有些 ROM 不展示 action 按钮，
                // 卡片是唯一可靠入口（这条经验与录音那条通知一致）。
                .setContentIntent(startIntent)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(R.drawable.ic_trime_status, ctx.getString(R.string.voice_resident_action), startIntent)
                // 长按 / 展开时给一个「打开 JackVoice」的入口，免得想看历史还得回桌面。
                .addAction(R.drawable.ic_trime_status, ctx.getString(R.string.voice_resident_open), openIntent)
                // OnGoing 只是不让点 X，左滑在 MIUI 上照样能划掉；划掉就立刻补回来。
                .setDeleteIntent(repostPendingIntent(ctx))
                .build()
        }

        private fun repostPendingIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx,
                RC_REPOST,
                Intent(ctx, VoiceResidentReceiver::class.java).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        internal fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.voice_resident_channel_name),
                    // LOW：不进「静默通知」折叠区，但绝不响铃震动——它是常用入口，
                    // 不是一次事件。实测 Clash 的常驻通知也是 LOW。
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = ctx.getString(R.string.voice_resident_channel_desc)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }

        private fun notificationManager(ctx: Context): NotificationManager? =
            ctx.getSystemService(NotificationManager::class.java)

        /** 供本地测试 / 调试页读：现在到底有没有挂着。 */
        val isShowing: Boolean get() = running != null
    }
}
