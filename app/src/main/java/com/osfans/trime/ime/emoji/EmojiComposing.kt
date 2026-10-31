/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

/**
 * emoji 搜索词在**输入框里**留下的那串组合文本的状态——**纯逻辑，不碰 Android**，好单测。
 *
 * 为什么要单独一个类：这串字母是输入法自己用 `setComposingText` 写进去的，
 * 每次改动都得让输入框里的内容和 [EmojiQuery] 对齐，而「该删几个字」这一步最容易错。
 * 错了不会崩，只会：
 *  - 删少了 → 输入框里永远躺着一串 `pisa`（**这就是「✕ 点了没反应」**）；
 *  - 删多了 → **删掉用户自己打的正文**，这是真的会丢字。
 *
 * 所以这里只管一件事：**下一步该往输入框写什么**。
 *
 * 关键的一条：提交 emoji 之后必须 [reset]。`commitText` 会就地替换掉那段组合，
 * 输入框里已经没有搜索词了；这时候如果还记着旧长度，下一次清空就会按旧长度
 * 去删——删到的是 emoji 前面的正文。
 */
class EmojiComposing {
    /**
     * 上一次写进输入框的那段组合文本的长度。
     *
     * 不能拿 `query.length` 代替：清空 / 退格时 query 已经变短了，
     * 用新长度去删会删得比实际少，剩下的那截就永远留在输入框里。
     */
    var length: Int = 0
        private set

    /**
     * 根据**当前**搜索词算出输入框该做什么。
     *
     * @param query 最新的搜索词（已经变过的那个）。
     */
    fun next(query: String): Action {
        // 有搜索词：整段重设。查询词最长也就十来个字母，
        // 这点开销比维护增量状态稳，也不用担心光标位置。
        if (query.isNotEmpty()) {
            length = query.length
            return Action.Set(query)
        }
        // 搜索词空了：把上次写进去的那串删掉。
        // 长度必须用**上一次记下的**，不能用 query.length（那时是 0）。
        val stale = length
        length = 0
        return if (stale > 0) Action.Clear(stale) else Action.None
    }

    /**
     * 提交 emoji 之后调用。
     *
     * `commitText` 已经把那段组合就地替换掉了，输入框里不再有搜索词；
     * 这里必须把长度清零，否则下一次清空会按旧长度删到用户的正文。
     */
    fun reset() {
        length = 0
    }

    /** 换输入框 / 退出 emoji 模式：输入框已经不是原来那个了，长度作废。 */
    fun onEditorChanged() {
        length = 0
    }

    /** 输入框该做的一步。 */
    sealed interface Action {
        /** 什么都不用做（本来就没写过搜索词）。 */
        data object None : Action

        /** 把搜索词整段设为 [text]。 */
        data class Set(
            val text: String,
        ) : Action

        /**
         * 删掉 [count] 个字符，把上次写进去的搜索词收掉。
         *
         * [count] 是**上次**写进去的长度，不是现在的。
         */
        data class Clear(
            val count: Int,
        ) : Action
    }
}
