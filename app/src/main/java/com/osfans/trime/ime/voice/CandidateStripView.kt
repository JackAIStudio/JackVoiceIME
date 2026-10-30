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
import com.osfans.trime.ui.jackvoice.JvColors

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

    /**
     * 长按某个候选。
     *
     * 只有 emoji 会用到：长按弹肤色 / 性别变体。中文候选长按没有额外语义，所以
     * 这个回调为空时保持原来的行为（不长按）。
     */
    var onLongPick: ((index: Int, anchor: android.view.View) -> Unit)? = null

    private var lastItems: Array<CandidateProto> = emptyArray()
    private var lastHighlighted = 0

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
        lastItems = items
        lastHighlighted = highlighted
        row.removeAllViews()
        items.forEachIndexed { index, candidate ->
            row.addView(item(candidate, index == highlighted, index))
        }
        scrollTo(0, 0)
    }

    /** 外观模式变化后按当前色板重建一遍，候选内容本身不变。 */
    fun refreshColors() {
        if (lastItems.isEmpty()) return
        update(lastItems, lastHighlighted)
    }

    fun clear() {
        lastItems = emptyArray()
        row.removeAllViews()
    }

    private fun item(
        candidate: CandidateProto,
        highlighted: Boolean,
        index: Int,
    ): TextView = TextView(context).apply {
        text = candidate.text
        setTextColor(JvColors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(9), dp(5), dp(9), dp(5))
        if (highlighted) {
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(JvColors.candidateHighlight, dp(11))
        }
        isClickable = true
        setOnClickListener {
            InputFeedbackManager.keyPressVibrate(this)
            onPick?.invoke(index)
        }
        if (onLongPick != null) {
            isLongClickable = true
            setOnLongClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onLongPick?.invoke(index, this)
                true
            }
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
}
