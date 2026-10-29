/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 用户是从哪儿按的「停」。
 *
 * 这个来源**只决定最后一步落到哪里**（插进光标 / 复制到剪贴板），
 * 不影响录音、识别、历史——那些一律照常，文字和音频永远先落盘。
 */
enum class VoiceStopSource {
    /** 输入法面板上的麦克风键 / 顶栏 🎙。用户此刻正看着键盘，想打字。 */
    Panel,

    /** 通知栏那条「正在聆听」的卡片或「完成」按钮。用户没在看键盘，多半只是记一下。 */
    Notification,
}

/**
 * 一段识别好的文字，最后该落到哪里。
 *
 * ## 为什么这件事需要一个显式判断
 *
 * 原来只有一条路：能拿到光标就插，拿不到就排队等下次光标（`pending`）。
 * 问题出在「拿不到」的两种原因被混成了一种：
 *
 * - 键盘收着、用户还想着那个输入框 → 排队是对的；
 * - 用户是**从通知栏**按的停 → 他根本没打算往哪儿插，排队的结果是
 *   下次随便打开哪个输入框，这段话就自己落进去（实测会插进另一个 App 的搜索框）。
 *
 * 所以这里把「用户想要什么」和「现在能不能插」拆成两步：
 * 来源决定意图，光标只决定能不能兑现。兑现不了的退到剪贴板——
 * 用户自己决定粘到哪，总比替他猜一个位置好。
 *
 * ## 历史永远是底
 *
 * 两个分支都**不表示丢字**：文字和音频在 [VoiceEngine] 的 `archive()` 里
 * 早就写进历史了，用户随时能在历史页看到、复制、重听。
 */
enum class VoiceDelivery {
    /** 直接插进当前光标。 */
    Insert,

    /** 复制到剪贴板，由用户自己粘贴。历史照旧。 */
    Copy,
    ;

    companion object {
        /**
         * @param source 用户从哪儿按的停
         * @param targetReady 现在有没有可用的光标（键盘展开 + 输入连接还在）
         */
        fun resolve(
            source: VoiceStopSource,
            targetReady: Boolean,
        ): VoiceDelivery = when {
            // 从通知栏停的：不管有没有光标都不插。用户没在看键盘，
            // 说明他要的是「记下来」，不是「打进这个框」。
            source == VoiceStopSource.Notification -> Copy

            // 面板停的，但键盘已经收起：没有可靠落点，退到剪贴板。
            // 不排队的理由见类注释最后一段。
            !targetReady -> Copy

            else -> Insert
        }
    }
}
