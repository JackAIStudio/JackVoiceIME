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
     * @return null 表示中央不显示文字
     */
    fun resolve(
        state: VoiceState,
        previewVisible: Boolean,
        retrying: Boolean,
        retryProgress: Float,
        trackpadActive: Boolean,
        message: String?,
    ): String? {
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

    const val IDLE_HINT = "点击说话"
}
