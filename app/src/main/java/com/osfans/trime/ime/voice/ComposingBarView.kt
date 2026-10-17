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
 * 拼写气泡：正在拼的拼音贴在键盘上沿**左上角**，只占文字那么大。
 *
 *   ┌────────┐
 *   │ ce shi │   ← 就是这一小块，半透明，右边和上方都留给 App
 *   ├────────┴───────────────  ← 键盘顶边（不动的红线）
 *   │ 测试 侧室 侧视  ⌄
 *   │ q w e r t y u i o p
 *
 * **它不算键盘高度**：键盘区顶边固定在候选那一行上方，拼写条浮在键盘外面。
 * 以前这里是一条全宽、不透明、跟键盘一样高的横条，而且会去改输入法内容区顶边，
 * 于是拼写出现/消失时整块键盘也忽高忽低，屏幕跟着抖。
 *
 * 宽度跟着文字走，最多占屏宽的 [MAX_WIDTH_RATIO]，长拼音省略而不是铺满一行。
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
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    init {
        setPadding(dp(16), 0, dp(16), 0)
        background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(BACKGROUND)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        addView(
            label,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        // 父容器给的上限是整屏宽；气泡自己再收一道，不能整行盖住底下 App。
        val available = MeasureSpec.getSize(widthMeasureSpec)
        label.maxWidth = maxOf(1, (available * MAX_WIDTH_RATIO).toInt() - dp(32))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** 拼写为空（组合结束）就整条收起。 */
    fun setComposingText(text: String) {
        label.text = text
        visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val HEIGHT_DP = 44

        /** 气泡最多占屏宽的比例，剩下的部分留给 App 内容透出来。 */
        private const val MAX_WIDTH_RATIO = 0.6f

        private const val BACKGROUND = 0xD9F1F1F1.toInt()
        private const val TEXT = 0xFF111111.toInt()
    }
}
