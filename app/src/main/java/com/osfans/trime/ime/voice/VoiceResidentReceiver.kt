/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 常驻听写通知的看门人：开机、应用更新、被划掉之后把它挂回来。
 *
 * ## 为什么要单独一个 receiver
 *
 * 常驻通知来自一个前台服务，而前台服务不会「开机自启」——没有谁去
 * `startForegroundService` 它就不会存在。用户早上开机第一件事往往是发消息，
 * 这时候通知栏里没有那个入口，等于功能不存在。
 *
 * ## 划掉为什么也要补
 *
 * `setOngoing(true)` 只是不让点 X，MIUI 上左滑照样能划掉。划掉就立刻补回来，
 * 是 Clash Meta 那种「划不掉」的手感；与 LockScreenWeather 的做法一致。
 * 只补一次、不做循环重试：用户铁了心要它消失时，反复弹回来只会招人烦——
 * 真要关，去设置里关开关（[VoicePrefs.KEY_RESIDENT_NOTIFICATION]）。
 */
class VoiceResidentReceiver : BroadcastReceiver() {
    override fun onReceive(
        ctx: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            // 「开始说话」的广播入口。
            //
            // 通知卡片走的是透明 Activity（[VoiceQuickStartActivity]）——实测
            // 卡片指向广播时，MIUI 上点了**完全没反应**，广播队列里连记录都没有。
            // 这条广播保留下来做两件事：一是给调试/脚本一个不用点屏幕的触发方式，
            // 二是万一某个 ROM 的卡片只认广播时还有个入口。
            VoiceResidentService.ACTION_START -> VoiceResidentService.toggleDictation(ctx)

            // 划掉之后补发。这里刻意**先判断条件**，
            // 否则用户刚在设置里关了开关，它还会再冒出来一次。
            ACTION_REPOST -> {
                if (!VoiceResidentService.canReside(ctx)) return
                if (VoiceSession.isCapturing()) return
                VoiceResidentService.ensure(ctx)
            }

            // 开机 / 应用更新 / 输入法被系统重装：条件具备就把入口挂回去。
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> runCatching { VoiceResidentService.ensure(ctx) }
                .onFailure { Timber.w(it, "开机后拉起常驻听写通知失败") }
        }
    }

    companion object {
        const val ACTION_REPOST = "com.osfans.trime.voice.RESIDENT_REPOST"
    }
}
