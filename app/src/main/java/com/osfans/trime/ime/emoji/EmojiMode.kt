/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady

/**
 * Emoji 模式：**候选行换来源，键盘不变。**
 *
 * 它不是第四门语言，也不是第四种键盘布局——键面、高度、底行全都保持原样，
 * 变的只有顶栏那一行候选从哪里来：
 *
 * ```
 * 中文   输入框 pisa_   候选 披萨 | 皮萨 | 比萨
 * 英文   输入框 pisa    候选 pizza | piston
 * emoji  输入框 pisa_   候选 🍕 | 🥧 | 🍝
 * ```
 *
 * 所以进 emoji 模式不切方案、不碰 ascii_mode、不换布局；退出时也不用「恢复」什么，
 * 中文的 Rime 组合原样还在。
 *
 * 这里只管状态与查询串，不碰 View——候选行的重绘由 KeyboardWindow 负责。
 */
class EmojiMode(
    private val rime: RimeSession,
) {
    /** 是否处于 emoji 模式。 */
    var enabled: Boolean = false
        private set

    /** 已打的字母，用来搜 emoji。规则见 [EmojiQuery]（纯逻辑，可单测）。 */
    private val queryState = EmojiQuery()

    /** 当前搜索词。和 Rime 的组合无关——emoji 不走 Rime。 */
    val query: String get() = queryState.text

    /**
     * 切到 emoji 模式。
     *
     * 中文正在拼的那串**直接丢掉**，不提交。
     *
     * 试过先 commitComposition() 再进 emoji：输入框里会凭空多出「披萨」两个字，
     * 用户点 🙂 明明是想发表情，却先把一段没选完的中文确认了；接着再搜 `pisa`
     * 提交 🍕，就变成「🍕披🍕」这种拼接物。丢掉才是符合预期的——
     * 没打过候选的中文还算不上「已经打出来」的字。
     */
    fun enter() {
        if (enabled) return
        enabled = true
        queryState.clear()
        rime.launchOnReady {
            it.clearComposition()
        }
    }

    /** 退出 emoji 模式。组合里的字母一并收掉，回中文/英文时是干净的。 */
    fun exit() {
        if (!enabled) return
        enabled = false
        queryState.clear()
    }

    /**
     * 打了一个字符。
     *
     * @return true 表示这个字符被 emoji 模式吃掉了，不要再送给 Rime。
     */
    fun onChar(ch: Char): Boolean {
        if (!enabled) return false
        return queryState.accept(ch)
    }

    /**
     * 退格。
     *
     * @return true 表示吃掉了；query 已经空的时候返回 false，让退格继续去删输入框里的正文。
     */
    fun onBackspace(): Boolean {
        if (!enabled) return false
        return queryState.backspace()
    }

    /** 点了一个 emoji：搜索词清掉，下一次从「最近」重新开始。 */
    fun onPicked() {
        queryState.clear()
    }

    /** 一次清掉整个搜索词（候选行那颗 ✕）。 */
    fun clearQuery() {
        queryState.clear()
    }
}
