/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ui.jackvoice.JvColors

/**
 * 展开后的候选面板：盖住按键区，把候选摊成网格。
 *
 * 顶栏那一行的候选条只放得下五六个，右边那颗 `⌄` 点开就是这里；再点 `⌃` 收起。
 *
 * **只画屏幕上看得见的那几行。** 以前是往一个 LinearLayout 里把整份候选都
 * addView 出来——中文候选十几条无所谓，emoji 是 1906 个，点 `⌄` 那一下要建
 * 一千多个 TextView，卡顿就是这么来的。现在换成 RecyclerView：滚出屏幕的格子
 * 被回收复用，一屏永远只有十几二十个 View。
 *
 * 数据分两层：
 * - 外面（KeyboardWindow）按页喂：中文是 Rime 一批 16 条地往回要，
 *   emoji 是本地列表一页一页放；
 * - 这里只管把手上这批画出来，快到底时喊一声 [onLoadMore]。
 *
 * 下标一律是**全量列表**里的位置，点第 200 个仍然能把第 200 个候选选上屏。
 */
class CandidatePanelView(
    context: Context,
    /** 每行几列。中文/英文候选是 3；emoji 网格要 9，一屏才看得下足够多。 */
    private val perRow: Int = DEFAULT_PER_ROW,
    /** 行高（dp）。中文候选 50；emoji 用 44，9 列才不显得扁。 */
    private val rowHeightDp: Int = DEFAULT_ROW_HEIGHT_DP,
) : RecyclerView(context) {
    /** 参数是候选在**全量列表**里的下标。 */
    var onPick: ((Int) -> Unit)? = null

    /** 长按某个候选：emoji 弹肤色 / 性别变体，中文没有这个语义。 */
    var onLongPick: ((index: Int, anchor: View) -> Unit)? = null

    /**
     * 快滑到底了，还想要下一批。
     *
     * 外面负责限流（中文是 CandidatePages，emoji 是 EmojiPager 的 hasMore），
     * 所以这里滚一下就可能喊一次，重复喊不会多要。
     */
    var onLoadMore: (() -> Unit)? = null

    /** 手上这批候选（下标就是全量下标）。 */
    private val items = ArrayList<CandidateProto>()

    /** 「加载中」脚注要不要占一行。 */
    private var footerLoading = false

    private val cells = CellAdapter()

    private val gridLayout =
        GridLayoutManager(context, perRow).apply {
            isItemPrefetchEnabled = true
            // 脚注（「加载中…」）要占满一整行，否则在 9 列网格里只有 1/9 宽，
            // 字挤成一条竖线。候选格子照旧一格一个。
            spanSizeLookup =
                object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = if (position >= items.size) perRow else 1
                }
        }

    private val emojiGrid: Boolean get() = perRow > DEFAULT_PER_ROW

    init {
        setBackgroundColor(JvColors.keyboardBg)
        layoutManager = gridLayout
        adapter = cells
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        setPadding(dp(8), dp(6), dp(8), dp(6))
        clipToPadding = true
        // 多备几行：手指还没滑到，下一行已经在池子里了
        setItemViewCacheSize(perRow * 3)
        addOnScrollListener(
            object : OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    if (dy > 0) maybeLoadMore()
                }
            },
        )
    }

    /** 换了一批候选：清空重画，回到顶部。 */
    fun show(newItems: Array<CandidateProto>) {
        items.clear()
        items.addAll(newItems)
        gridLayout.spanSizeLookup.invalidateSpanIndexCache()
        cells.notifyDataSetChanged()
        scrollToPosition(0)
        post { maybeLoadMore() }
    }

    /** 下一批候选到了，接在后面；下标接着往下排。 */
    fun append(newItems: Array<CandidateProto>) {
        if (newItems.isEmpty()) return
        val base = items.size
        items.addAll(newItems)
        gridLayout.spanSizeLookup.invalidateSpanIndexCache()
        cells.notifyItemRangeInserted(base, newItems.size)
        post { maybeLoadMore() }
    }

    /** 还在要下一批：脚注亮出来，免得用户以为已经到底了。 */
    fun setLoadingMore(loading: Boolean) {
        if (footerLoading == loading) return
        footerLoading = loading
        gridLayout.spanSizeLookup.invalidateSpanIndexCache()
        if (loading) cells.notifyItemInserted(items.size) else cells.notifyItemRemoved(items.size)
    }

    /** 外观模式变化后按当前色板重建一遍，候选本身不动。 */
    fun refreshColors() {
        setBackgroundColor(JvColors.keyboardBg)
        cells.notifyDataSetChanged()
    }

    fun clear() {
        items.clear()
        footerLoading = false
        gridLayout.spanSizeLookup.invalidateSpanIndexCache()
        cells.notifyDataSetChanged()
    }

    /** 面板里第 [index] 条（全量下标）。收起前外面按这个取值。 */
    fun itemAt(index: Int): CandidateProto? = items.getOrNull(index)

    /** 只剩最后一行时就提前要下一批，别等手指撞到底。 */
    private fun maybeLoadMore() {
        if (items.isEmpty()) return
        val lastVisible = gridLayout.findLastVisibleItemPosition()
        if (lastVisible < 0) return
        if (items.size - 1 - lastVisible > perRow) return
        onLoadMore?.invoke()
    }

    private inner class CellAdapter : Adapter<Holder>() {
        override fun getItemCount(): Int = items.size + if (footerLoading) 1 else 0

        override fun getItemViewType(position: Int): Int = if (position >= items.size) TYPE_FOOTER else TYPE_CELL

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder {
            // 行高在这里定死：onBindViewHolder 里改 layoutParams 会在布局过程中
            // 触发 requestLayout，RecyclerView 会直接抛「computing a layout」。
            val text =
                TextView(context).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    includeFontPadding = false
                    isClickable = true
                    layoutParams =
                        if (viewType == TYPE_FOOTER) {
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                        } else {
                            LayoutParams(LayoutParams.MATCH_PARENT, dp(rowHeightDp))
                        }
                }
            if (viewType == TYPE_FOOTER) {
                text.setText(R.string.candidate_loading_more)
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                text.setPadding(0, dp(8), 0, dp(10))
            }
            return Holder(text)
        }

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val text = holder.text
            if (position >= items.size) {
                text.setTextColor(JvColors.textSecondary)
                text.setOnClickListener(null)
                text.setOnLongClickListener(null)
                return
            }
            text.setTextColor(JvColors.text)
            // 中文候选是给眼睛读的字，19sp 正好；emoji 是图形，得撑到接近格子的高度。
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (emojiGrid) 26f else 19f)
            text.text = items[position].text
            text.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index in items.indices) {
                    InputFeedbackManager.keyPressVibrate(text)
                    onPick?.invoke(index)
                }
            }
            if (onLongPick != null && emojiGrid) {
                text.isLongClickable = true
                text.setOnLongClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index in items.indices) {
                        InputFeedbackManager.keyPressVibrate(text)
                        onLongPick?.invoke(index, text)
                    }
                    true
                }
            } else {
                text.isLongClickable = false
                text.setOnLongClickListener(null)
            }
        }
    }

    private class Holder(
        val text: TextView,
    ) : ViewHolder(text)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val DEFAULT_PER_ROW = 3
        const val DEFAULT_ROW_HEIGHT_DP = 50

        /** emoji 网格：9 列 × 44dp。411dp 宽的屏一格约 45dp，手指按得住。 */
        const val EMOJI_PER_ROW = 9
        const val EMOJI_ROW_HEIGHT_DP = 44

        private const val TYPE_CELL = 0
        private const val TYPE_FOOTER = 1
    }
}
