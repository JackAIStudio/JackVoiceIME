/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context

/**
 * 终稿后处理：内置替换规则（与 `plugin/` 的 postProcess 语义一致）。
 * 用户追加规则后续接 Trime 偏好，这里先只走内置词库。
 */
object PostProcessor {
    fun process(
        text: String,
        ctx: Context,
    ): String {
        if (text.isEmpty()) return text
        val rules = BuiltinData.rules(ctx)
        if (rules.isEmpty()) return text
        return ReplacementEngine.apply(text, rules).text
    }
}
