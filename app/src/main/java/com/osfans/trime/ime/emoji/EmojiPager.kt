/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

/**
 * emoji 展开面板的分页数据源——**纯逻辑，不碰 Android**，好单测。
 *
 * 以前点 `⌄` 是「一次性把整份列表摊成 View」：1906 个 emoji 一次建 1906 个
 * TextView、加进 212 行 LinearLayout，那一帧要卡住好几百毫秒。emoji 网格本来
 * 就是一屏二十来格的规则格子，用户看不到的东西没必要先造出来。
 *
 * 这里只管「现在该有哪几条」：按页取，滚到边上再取下一页。面板只管把当前
 * 这一批画出来，滚出屏幕的行由 RecyclerView 自己回收。
 *
 * 下标一律是**全量列表里的位置**，不是页内偏移——点第 200 个仍然能把第 200 个
 * 选上屏。
 */
class EmojiPager(
    /** 一页多少条。9 列 × 6 行 = 54，比一屏多一点，翻页时感觉不到接缝。 */
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    /** 当前这一份完整序列。换了查询就整份换掉。 */
    private var source: List<String> = emptyList()

    /** 已经放出来给面板的条数（= 下一次 [loadMore] 的起点）。 */
    var loaded: Int = 0
        private set

    /** 这一份序列总共多少条。 */
    val total: Int get() = source.size

    /** 还有没有下一页。 */
    val hasMore: Boolean get() = loaded < source.size

    /**
     * 换一份序列，回到第一页。
     *
     * 传进来的是**完整**列表：emoji 全在内存里，切页只是切片，不会重新搜一遍。
     * 第一页立刻可用，剩下的等滚到边上再 [loadMore]。
     */
    fun reset(items: List<String>) {
        source = items
        loaded = minOf(items.size, pageSize)
    }

    /**
     * 再放一页出来。
     *
     * @return 新放出来的那几条；没有更多时返回空列表。
     */
    fun loadMore(): List<String> {
        if (!hasMore) return emptyList()
        val end = minOf(loaded + pageSize, source.size)
        val page = source.subList(loaded, end)
        loaded = end
        return page
    }

    fun clear() {
        source = emptyList()
        loaded = 0
    }

    companion object {
        /**
         * 一页多少条。
         *
         * 9 列，面板一屏大约 5 行（44dp × 5 ≈ 220dp，正好是按键区高度），
         * 54 条能填满一屏还多出半屏，手指还没撞到底下一页就铺好了。
         */
        const val DEFAULT_PAGE_SIZE = 54
    }
}
