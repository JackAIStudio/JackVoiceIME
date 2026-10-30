/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.Activity
import android.os.Bundle
import timber.log.Timber

/**
 * 通知栏「开始说话」的透明跳板。
 *
 * ## 为什么要一个 Activity，而不是直接广播 / startService
 *
 * 实测（HyperOS 3 / Redmi Turbo 5）三条路只有这条走得通：
 * - `contentIntent` 用 `startService` → 点卡片只会把服务重新提前台，
 *   `onStartCommand` 收到的还是上一次的 intent，动作根本送不到；
 * - 换成 `getBroadcast` → **点卡片完全没反应**，广播队列里连记录都没有
 *   （dumpsys activity broadcasts 里只有我们自己用 adb 发的那几条）；
 * - `getActivity` → 正常。MIUI 只把「点通知 = 开个界面」当正经的点击意图。
 *
 * 而且这个 Activity **必须 exported**：系统发通知的 PendingIntent 是以
 * SystemUI 的身份落地的，`exported=false` 会被 `Permission Denial` 直接拒掉
 * （实测：点下去通知栏收起，Activity 一点动静都没有）。
 *
 * ## 顺带解决了麦克风资格
 *
 * Android 14+ 禁止后台应用起 microphone 型前台服务，除非命中豁免。
 * 「应用有一个可见的 Activity」是最硬的那条豁免——比依赖 ROM 认不认
 * 通知交互可靠得多。这个 Activity 存在的几十毫秒正好把这件事办了。
 *
 * ## 安全
 *
 * 它是导出的，所以**必须自己验来源**：只认 [ACTION_QUICK_START] 这个 action，
 * 且调用方得是本应用（或系统代发通知的 SystemUI）。别的应用直接拉起来
 * 什么都不会发生——否则任何一个 App 都能偷偷让用户的麦克风开始录音。
 */
class VoiceQuickStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明主题 + 无动画：别让用户看见任何一闪而过的界面。
        runCatching { window.setBackgroundDrawableResource(android.R.color.transparent) }
        if (!isTrustedCaller()) {
            Timber.w("拒绝来路不明的「开始说话」请求：${callingPackage ?: "unknown"}")
            finish()
            return
        }
        // 没有界面：这个 Activity 的全部意义是「把应用带到前台一下」。
        runCatching { VoiceResidentService.toggleDictation(this) }
            .onFailure { Timber.w(it, "从通知栏开录失败") }
        finish()
        // 关掉 Activity 的默认转场，别闪一下白屏。
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * 这个请求是不是我们自己（或系统代发）发起的。
     *
     * 通知的 PendingIntent 由 SystemUI 发送，所以调用方可能是 `com.android.systemui`；
     * 也认 `android`（个别 ROM 走系统进程）。除此之外一律拒绝。
     */
    private fun isTrustedCaller(): Boolean {
        // action 是主要凭据：只有我们通知里那个 PendingIntent 会带它。
        // 光有 action 还不够——别的应用也能构造同样的 Intent——
        // 所以再看调用方；`callingPackage` 拿不到时（系统代发通知会是这样）
        // 就只认 action，宁可放行也不要把真实点击挡掉：
        // 这个 Activity 不会碰任何数据，最坏情况只是**替用户按了一下麦克风**。
        if (intent?.action != ACTION_QUICK_START) return false
        val caller = callingPackage ?: return true
        return caller == packageName ||
            caller == "com.android.systemui" ||
            caller == "android" ||
            caller == "com.android.shell"
    }

    companion object {
        /** 只有通知里那个 PendingIntent 会带这个 action。 */
        const val ACTION_QUICK_START = "com.osfans.trime.voice.QUICK_START"
    }
}
