/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ui.jackvoice.JvColors

/**
 * 展开后的候选面板：盖住按键区，把整页候选摊成网格。
 *
 * 顶栏那一行的候选条只放得下五六个，右边那颗 `⌄` 点开就是这里；再点 `⌃` 收起。
 */
class CandidatePanelView(
    context: Context,
) : ScrollView(context) {
    /** 参数是候选在**全量列表**里的下标。 */
    var onPick: ((Int) -> Unit)? = null

    private var lastItems: Array<CandidateProto> = emptyArray()

    private val grid =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

    init {
        setBackgroundColor(JvColors.keyboardBg)
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(items: Array<CandidateProto>) {
        lastItems = items
        grid.removeAllViews()
        var row: LinearLayout? = null
        items.forEachIndexed { index, candidate ->
            if (index % PER_ROW == 0) {
                row =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)))
            }
            row!!.addView(
                cell(candidate, index),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
        }
        // 最后一行补空位，免得剩下的格子被拉宽
        val remainder = items.size % PER_ROW
        if (remainder != 0) {
            repeat(PER_ROW - remainder) {
                row!!.addView(
                    View(context),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
            }
        }
        scrollTo(0, 0)
    }

    /** 外观模式变化后按当前色板重建一遍。 */
    fun refreshColors() {
        setBackgroundColor(JvColors.keyboardBg)
        if (lastItems.isEmpty()) return
        show(lastItems)
    }

    fun clear() {
        lastItems = emptyArray()
        grid.removeAllViews()
    }

    private fun cell(
        candidate: CandidateProto,
        index: Int,
    ): TextView = TextView(context).apply {
        text = candidate.text
        setTextColor(JvColors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        gravity = Gravity.CENTER
        maxLines = 1
        isClickable = true
        setOnClickListener {
            InputFeedbackManager.keyPressVibrate(this)
            onPick?.invoke(index)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PER_ROW = 3
        private const val ROW_HEIGHT_DP = 50
    }
}
