/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 展开候选面板的分页游标。
 *
 * Rime 每次给我们的候选最多就 16 条（`Candidates.Bulk` 的 bulk 上限），再往后得自己
 * 按全局下标往回要：`getCandidates(offset, limit)`。这个类只管那点状态——下一批从第几条
 * 开始、能不能再要、什么时候算要完了——不碰 Rime 也不碰 View，好单测，也免得把
 * 「正在加载」这类标志散在面板和键盘窗口两边。
 *
 * 用法：`beginLoad()` 占位并拿到起点（null 表示这会儿别发请求），拿到结果后
 * [endLoad]，失败或被丢弃就 [abortLoad] 把占位还回去。
 */
class CandidatePages(
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    /** 手上已有的候选条数，也就是下一批的起点。 */
    var loaded: Int = 0
        private set

    /** Rime 那边已经给不出更多候选了。 */
    var exhausted: Boolean = false
        private set

    private var loading = false

    /** 一批请求在飞行中。 */
    val isLoading: Boolean get() = loading

    /**
     * 换了一批候选（重新输入、切语言、收起再展开）后重置。
     *
     * @param loaded 手上已经有的条数，新的请求从它往后要。
     * @param exhausted 这批候选是否已经到头了（英文补全、Rime 明说过总数、候选不满一页）。
     */
    fun reset(
        loaded: Int,
        exhausted: Boolean,
    ) {
        this.loaded = loaded
        this.exhausted = exhausted
        loading = false
    }

    /** 占位一次加载，返回这一批的起点；正在加载或已经到头时返回 null。 */
    fun beginLoad(): Int? {
        if (loading || exhausted) return null
        loading = true
        return loaded
    }

    /** 这批回来了：[count] 是实际拿到的条数，不满一页就说明到头了。 */
    fun endLoad(count: Int) {
        loading = false
        loaded += count
        if (count < pageSize) exhausted = true
    }

    /** 请求失败或结果作废：把占位还回去，下次滚到底还能再试。 */
    fun abortLoad() {
        loading = false
    }

    companion object {
        /** 一批要多少条。够填满一屏还多几屏，又不会一次让 Rime 生成太多。 */
        const val DEFAULT_PAGE_SIZE = 80
    }
}
