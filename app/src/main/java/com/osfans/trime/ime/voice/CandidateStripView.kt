/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.keyboard.InputFeedbackManager

/**
 * 候选词条。
 *
 * Typeless 的做法是：候选和「品牌 + 切换胶囊」**共用顶栏那一行**——不输入时是品牌和
 * 🎙|拼|EN，一输入就被候选顶替，候选消失又换回来。所以键盘的总高度从头到尾不变，
 * 也不会出现两条横栏叠在一起。
 *
 * 这个控件就是那一行里的候选列表：横向可滑动，点一个词就上屏。
 */
class CandidateStripView(
    context: Context,
) : HorizontalScrollView(context) {
    /** 参数是候选在**全量列表**里的下标。 */
    var onPick: ((Int) -> Unit)? = null

    private val row =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setPadding(dp(6), 0, dp(6), 0)
    }

    fun update(
        items: Array<CandidateProto>,
        highlighted: Int,
    ) {
        row.removeAllViews()
        items.forEachIndexed { index, candidate ->
            row.addView(item(candidate, index == highlighted, index))
        }
        scrollTo(0, 0)
    }

    fun clear() {
        row.removeAllViews()
    }

    private fun item(
        candidate: CandidateProto,
        highlighted: Boolean,
        index: Int,
    ): TextView =
        TextView(context).apply {
            text = candidate.text
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(9), dp(5), dp(9), dp(5))
            if (highlighted) {
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(HIGHLIGHT, dp(11))
            }
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onPick?.invoke(index)
            }
        }

    private fun rounded(
        color: Int,
        radius: Int,
    ) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TEXT = 0xFF111111.toInt()
        private const val HIGHLIGHT = 0xFFE9E9E9.toInt()
    }
}
