/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 语音面板中央那一句提示的**唯一决策点**。
 *
 * 这里刻意不碰 View：面板中央曾经同时摆着「点击说话」和「正在重新识别…」
 * 两个 TextView，`showMessage()`、`setRetrying()`、`setState()` 各写各的，
 * 于是截图里出现了三层文字重影。现在只有 [VoicePanelView.statusView] 一个
 * TextView，显示哪一句全部由这个纯函数决定，任何回调顺序都只会得到一句话。
 */
internal object VoiceCenterStatus {
    /**
     * @param previewVisible 重新识别的预览卡片是否占着面板
     * @param retrying 是否真的有重新识别任务在跑
     * @param trackpadActive 空格触控板是否正占用面板
     * @param message 临时提示；过期后由调用方置空
     * @param partialChars 等终稿时，这一轮已经收到的字数（流式一遍结果）
     * @return null 表示中央不显示文字
     */
    fun resolve(
        state: VoiceState,
        previewVisible: Boolean,
        retrying: Boolean,
        retryProgress: Float,
        trackpadActive: Boolean,
        message: String?,
        partialChars: Int = 0,
    ): String? {
        // 等终稿**必须**有一句话。
        //
        // 以前这里第一行就是 `if (state != Idle) return null`，于是 Thinking 时
        // 整块屏幕只剩一颗转圈的胶囊，一个字都没有；那句「正在整理文字…」只写在
        // 通知栏文案里，而通知这时候早被收走了。用户没有任何依据判断它在干活
        // 还是卡死了——这就是「一直 thinking」这个体感的真正来源。
        if (state == VoiceState.Thinking) {
            return if (partialChars > 0) {
                "正在整理文字… 已收下 $partialChars 字"
            } else {
                "正在整理文字…"
            }
        }
        if (state != VoiceState.Idle) return null
        return when {
            previewVisible -> null
            retrying -> retryingLabel(retryProgress)
            trackpadActive -> null
            !message.isNullOrBlank() -> message
            else -> IDLE_HINT
        }
    }

    fun retryingLabel(progress: Float): String =
        if (progress > 0f) {
            "正在重新识别 ${(progress * 100).toInt().coerceIn(0, 100)}%"
        } else {
            "正在重新识别…"
        }

    /**
     * 等终稿时，胶囊下面那一行说明「点一下会怎样」。
     *
     * 只写结果，不写「取消」：点它**不会**丢东西，用户需要知道这一点才敢按。
     */
    fun thinkingHint(partialChars: Int): String =
        if (partialChars > 0) {
            "再点一下，就用这 $partialChars 字"
        } else {
            "再点一下就不等了"
        }

    const val IDLE_HINT = "点击说话"
}
