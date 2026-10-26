/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 现在有没有网。
 *
 * 只用来**省掉一次注定失败的建连**：没网时不开 WebSocket，直接进「先录着」的模式，
 * 用户不用先盯着转圈等十秒连接超时，才被告知网络不行。
 *
 * 它**不是**安全闸门：判断为有网、实际连不上（VPN 半死、DNS 挂了、机场掉线）
 * 照样会发生，那种情况由 [VoiceEngine] 的降级路径接住——录音不中断，
 * 停下来之后可以重新识别。所以这里宁可保守（有网络就当真），
 * 也不要因为某些 ROM 的 VALIDATED 标记短暂为假就把好好的网络判死。
 */
object VoiceNetwork {
    fun isOnline(ctx: Context): Boolean =
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
}
