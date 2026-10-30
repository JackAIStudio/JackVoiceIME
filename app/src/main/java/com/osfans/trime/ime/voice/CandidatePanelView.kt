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
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ui.jackvoice.JvColors

/**
 * 展开后的候选面板：盖住按键区，把整页候选摊成网格。
 *
 * 顶栏那一行的候选条只放得下五六个，右边那颗 `⌄` 点开就是这里；再点 `⌃` 收起。
 *
 * Rime 一次只交 16 条候选，所以滑到底不能就完了：[onLoadMore] 由键盘窗口接，
 * 往回要下一批；拿到的候选原样 [append] 在网格后面，下标继续按**全量列表**排，
 * 点第 200 个仍然能把第 200 个候选选上屏。
 */
class CandidatePanelView(
    context: Context,
    /** 每行几列。中文/英文候选是 3；emoji 网格要 9，一屏才看得下足够多。 */
    private val perRow: Int = DEFAULT_PER_ROW,
    /** 行高（dp）。中文候选 50；emoji 用 44，9 列才不显得扁。 */
    private val rowHeightDp: Int = DEFAULT_ROW_HEIGHT_DP,
) : ScrollView(context) {
    /** 参数是候选在**全量列表**里的下标。 */
    var onPick: ((Int) -> Unit)? = null

    /** 快滑到底了，还想要下一批。外面负责限流，这里滚一下就可能喊一次。 */
    var onLoadMore: (() -> Unit)? = null

    /** 已经在网格里的候选，下标就是它在全量列表里的位置。 */
    private val shown = mutableListOf<CandidateProto>()

    private val grid =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

    /** 「加载中」脚注，永远挂在网格最后一行下面。 */
    private val footer =
        TextView(context).apply {
            setText(R.string.candidate_loading_more)
            setTextColor(JvColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(10))
        }

    private val footerParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    /** 正在往里填的那一行，以及它已经填了几格。 */
    private var row: LinearLayout? = null
    private var filled = 0

    /** 最后一行不满时补的空格子（不补的话剩下的格子会被拉宽）。 */
    private val spacers = mutableListOf<View>()

    init {
        setBackgroundColor(JvColors.keyboardBg)
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnScrollChangeListener { _, _, _, _, _ -> maybeLoadMore() }
    }

    /** 换了一批候选：清空重画，回到顶部。 */
    fun show(items: Array<CandidateProto>) {
        shown.clear()
        shown.addAll(items)
        render()
        scrollTo(0, 0)
        post { maybeLoadMore() }
    }

    /** 下一批候选到了，接在后面；下标接着往下排。 */
    fun append(items: Array<CandidateProto>) {
        if (items.isEmpty()) return
        val base = shown.size
        shown.addAll(items)

        grid.removeView(footer)
        removeSpacers()
        items.forEachIndexed { offset, candidate ->
            if (row == null || filled == perRow) newRow()
            addCell(candidate, base + offset)
            filled++
        }
        padRow()
        grid.addView(footer, footerParams)
        post { maybeLoadMore() }
    }

    /** 还在要下一批：脚注亮出来，免得用户以为已经到底了。 */
    fun setLoadingMore(loading: Boolean) {
        footer.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /** 外观模式变化后按当前色板重建一遍，候选本身不动。 */
    fun refreshColors() {
        setBackgroundColor(JvColors.keyboardBg)
        footer.setTextColor(JvColors.textSecondary)
        val y = scrollY
        render()
        post { scrollTo(0, y) }
    }

    fun clear() {
        shown.clear()
        render()
        footer.visibility = View.GONE
    }

    /** 整页重画（[shown] 不变），换色和换候选都走这里。 */
    private fun render() {
        grid.removeAllViews()
        row = null
        filled = 0
        spacers.clear()
        shown.forEachIndexed { index, candidate ->
            if (row == null || filled == perRow) newRow()
            addCell(candidate, index)
            filled++
        }
        padRow()
        grid.addView(footer, footerParams)
    }

    private fun newRow() {
        val newRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        row = newRow
        filled = 0
        grid.addView(
            newRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowHeightDp)),
        )
    }

    private fun addCell(
        candidate: CandidateProto,
        index: Int,
    ) {
        row?.addView(
            cell(candidate, index),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
    }

    private fun padRow() {
        val current = row ?: return
        if (filled in 1 until perRow) {
            repeat(perRow - filled) {
                val spacer = View(context)
                current.addView(
                    spacer,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
                spacers += spacer
            }
        }
    }

    private fun removeSpacers() {
        val current = row ?: return
        spacers.forEach { current.removeView(it) }
        spacers.clear()
    }

    /** 只剩不到一屏时提前要下一批，别等手指撞到底。 */
    private fun maybeLoadMore() {
        if (shown.isEmpty() || footer.visibility == View.VISIBLE) return
        val content = getChildAt(0) ?: return
        val remaining = content.height - height - scrollY
        if (remaining <= dp(LOAD_MORE_THRESHOLD_DP)) onLoadMore?.invoke()
    }

    private fun cell(
        candidate: CandidateProto,
        index: Int,
    ): TextView = TextView(context).apply {
        text = candidate.text
        setTextColor(JvColors.text)
        // 中文候选是给眼睛读的字，19sp 正好；emoji 是图形，得撑到接近格子的高度。
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (perRow > DEFAULT_PER_ROW) 26f else 19f)
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
        const val DEFAULT_PER_ROW = 3
        const val DEFAULT_ROW_HEIGHT_DP = 50

        /** emoji 网格：9 列 × 44dp。411dp 宽的屏一格约 45dp，手指按得住。 */
        const val EMOJI_PER_ROW = 9
        const val EMOJI_ROW_HEIGHT_DP = 44

        /** 距离底部还有这么多像素就去要下一批。 */
        private const val LOAD_MORE_THRESHOLD_DP = 120
    }
}
