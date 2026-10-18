/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.jackvoice

import com.osfans.trime.data.prefs.AppearanceManager

/**
 * JackVoice 自己的语义色板。
 *
 * 主界面（Jv）和输入法里手搭的键盘 UI（语音面板、顶栏、候选、拼写气泡）
 * 都从这里取色，不直接写死 ARGB。深浅两套值按 [AppearanceManager] 的生效模式返回，
 * 所以「自动」跟系统、或者用户在设置里固定浅色/深色，这三处永远一致。
 *
 * 键面颜色刻意和 `trime.yaml` 的 `jv_dark` 配色对齐：
 * 键盘背景 #1C1C1E、键面 #2C2C2E、主文字 #F5F5F7。
 */
object JvColors {
    private val isNight: Boolean
        get() = AppearanceManager.isNight()

    // ── 主界面 ──────────────────────────────────────────────────────────

    val bg: Int
        get() = if (isNight) 0xFF0B0B0C.toInt() else 0xFFFFFFFF.toInt()

    val card: Int
        get() = if (isNight) 0xFF171719.toInt() else 0xFFF5F5F5.toInt()

    val cardStrong: Int
        get() = if (isNight) 0xFF262628.toInt() else 0xFFEFEFEF.toInt()

    val text: Int
        get() = if (isNight) 0xFFF2F2F7.toInt() else 0xFF111111.toInt()

    val textSecondary: Int
        get() = if (isNight) 0xFF9A9A9F.toInt() else 0xFF8A8A8A.toInt()

    val accent: Int
        get() = if (isNight) 0xFF4C8DFF.toInt() else 0xFF2B6BFE.toInt()

    val onAccent: Int
        get() = 0xFFFFFFFF.toInt()

    val danger: Int
        get() = if (isNight) 0xFFFF6B6B.toInt() else 0xFFE5484D.toInt()

    val divider: Int
        get() = if (isNight) 0xFF2A2A2C.toInt() else 0xFFEDEDED.toInt()

    /** 分段控件里被选中的那一格：浅色时白底浮在灰卡上，深色时用更亮一档的灰。 */
    val segmentSelected: Int
        get() = if (isNight) 0xFF343437.toInt() else 0xFFFFFFFF.toInt()

    // ── 键盘 / 语音面板 ─────────────────────────────────────────────────

    val keyboardBg: Int
        get() = if (isNight) 0xFF1C1C1E.toInt() else 0xFFF1F1F1.toInt()

    val keyBg: Int
        get() = if (isNight) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()

    val toolbarActive: Int
        get() = if (isNight) 0xFF3A3A3C.toInt() else 0xFFEFEFEF.toInt()

    val candidateHighlight: Int
        get() = if (isNight) 0xFF3A3A3C.toInt() else 0xFFE9E9E9.toInt()

    /** 拼写气泡：半透明，浮在键盘上沿外面。 */
    val composingBubble: Int
        get() = if (isNight) 0xE61C1C1E.toInt() else 0xD9F1F1F1.toInt()

    val trackpadHintBg: Int
        get() = if (isNight) 0xE63A3A3C.toInt() else 0xCC161616.toInt()

    val trackpadHintText: Int
        get() = 0xFFFFFFFF.toInt()

    val glyph: Int
        get() = if (isNight) 0xFFC7C7CC.toInt() else 0xFF3A3A3A.toInt()

    /** 中间大按钮：浅色黑胶囊白话筒，深色反过来，免得一团黑陷进深色底里。 */
    val orbBg: Int
        get() = if (isNight) 0xFFF2F2F7.toInt() else 0xFF111111.toInt()

    val orbFg: Int
        get() = if (isNight) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()

    val orbHalo: Int
        get() = if (isNight) 0xFF2F2F31.toInt() else 0xFFE8E8E8.toInt()

    val thinkingBg: Int
        get() = if (isNight) 0xFFE5E5EA.toInt() else 0xFF2E2E2E.toInt()

    val thinkingProgress: Int
        get() = if (isNight) 0xFFC7C7CC.toInt() else 0xFF5C5C5C.toInt()

    val thinkingText: Int
        get() = if (isNight) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()

    val recording: Int
        get() = 0xFFE5484D.toInt()

    val thinking: Int
        get() = 0xFFF5A623.toInt()
}
