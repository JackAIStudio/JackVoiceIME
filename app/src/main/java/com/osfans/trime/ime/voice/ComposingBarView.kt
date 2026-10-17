/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 拼写条：正在拼的拼音单独占一行，贴在键盘上沿外面（原来 Trime 画方案提示的位置）。
 *
 *   ceshi
 *   测试 侧室 侧视  ⌄      ← 这一行在键盘里，见 VoiceToolbarView
 *   q w e r t y u i o p
 *
 * 背景和键盘同色、只做上方圆角，所以看起来就是键盘往上长了一截。
 * 这一条不需要接收触摸（没有任何可点的东西），别再加 PopupWindow。
 */
class ComposingBarView(
    context: Context,
) : FrameLayout(context) {
    private val label =
        TextView(context).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    init {
        background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(BACKGROUND)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        addView(
            label,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            },
        )
    }

    /** 拼写为空（组合结束）就整条收起。 */
    fun setComposingText(text: String) {
        label.text = text
        visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val HEIGHT_DP = 44

        private const val BACKGROUND = 0xFFF1F1F1.toInt()
        private const val TEXT = 0xFF111111.toInt()
    }
}
