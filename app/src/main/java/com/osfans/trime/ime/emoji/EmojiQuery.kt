/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

/**
 * emoji 模式的**输入规则**：哪些键算搜索词、哪些放行。
 *
 * 抽出来的原因有两个：
 *  1. 这是纯逻辑，能单测；[EmojiMode] 还要跟 Rime 打交道，测起来得拖一整个会话。
 *  2. 规则本身容易写错却不报错——比如把空格也吞了，键盘不会崩，只是那颗最大的键
 *     变成哑键，用户会以为坏了。
 *
 * 规则：
 *  - 只收 ASCII 字母和数字，统一转小写（`P` 与 `p` 搜一样的东西）
 *  - **空格放行**：emoji 模式下按空格就是想打空格
 *  - **标点放行**：`。`、`/` 这些照常上屏
 *  - 退格先删搜索词，删空了才放行去删正文
 */
class EmojiQuery {
    var text: String = ""
        private set

    val isEmpty: Boolean get() = text.isEmpty()

    /**
     * @return true 表示这个字符被吃掉了，不要再送给 Rime 或输入框。
     */
    fun accept(ch: Char): Boolean {
        if (!isSearchable(ch)) return false
        text += ch.lowercaseChar()
        return true
    }

    /**
     * @return true 表示退格被吃掉了；false 时调用方应让它继续删正文。
     */
    fun backspace(): Boolean {
        if (text.isEmpty()) return false
        text = text.dropLast(1)
        return true
    }

    fun clear() {
        text = ""
    }

    companion object {
        /**
         * 这个字符能不能作为搜索词。
         *
         * 公开出来是为了让「批量输入」和「单键输入」用**同一套**判断——
         * 这两处各写一份的话，迟早会出现「单键能搜、粘贴进来不能搜」这种怪事。
         */
        fun isSearchable(ch: Char): Boolean =
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
    }
}
