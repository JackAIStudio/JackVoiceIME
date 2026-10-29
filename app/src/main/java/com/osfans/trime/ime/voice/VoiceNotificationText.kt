/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 通知栏里那句实时预览该显示什么。
 *
 * 流式识别给的是**整段累积文本**（[VoiceSession.lastPartial]），原样摆进通知有两个问题：
 * 它一直在变长，两行很快被占满；而用户想看的其实是「我刚说的这句出来没」，
 * 前面的内容他自己知道。所以这里只取最后一句。
 *
 * 拆成纯函数是为了能测：句子边界、超长截断这些判断错一次，通知栏就会长期显示
 * 一段莫名其妙的话，而它不像面板那样当场就能看见。
 */
internal object VoiceNotificationText {
    /**
     * 句子结束符。
     *
     * 分号也算——中文里它是小句边界，按它切比死等句号更贴近「当前这句」；
     * 换行同理（有些识别结果按行给）。
     */
    private val TERMINATORS = charArrayOf('。', '！', '？', '!', '?', '…', '；', ';', '\n')

    /**
     * 折叠时那一行最多多少字。
     *
     * 一整句一口气说下来可能很长（没有句号就没有边界），所以还要按字数兜一次：
     * 正在说的部分总在末尾，截掉前面不影响判断。
     */
    const val MAX_CHARS = 60

    fun latestSentence(
        raw: String,
        maxChars: Int = MAX_CHARS,
    ): String {
        if (raw.isBlank() || maxChars <= 0) return ""
        // 末尾的标点先摘掉：句号落在最后时，它后面是空的，
        // 不摘就会把「刚说完的这一句」当成「还没有这一句」。
        val text = raw.trim().trim { it in TERMINATORS || it.isWhitespace() }
        if (text.isEmpty()) return ""
        val cut = text.indexOfLast { it in TERMINATORS }
        val segment = if (cut >= 0) text.substring(cut + 1).trim() else text
        if (segment.isEmpty()) return ""
        if (segment.length <= maxChars) return segment
        return "…" + segment.takeLast((maxChars - 1).coerceAtLeast(0))
    }
}
