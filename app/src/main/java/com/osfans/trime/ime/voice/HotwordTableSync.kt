/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 火山 ASR 热词表的整表覆盖。TOS 词库同步和它分开触发。 */
object HotwordTableSync {
    sealed interface Outcome {
        data class Synced(val count: Int) : Outcome

        data class Skipped(val reason: String) : Outcome
    }

    suspend fun sync(
        ctx: Context,
        words: List<String> = VoiceDictionary.hotwords(ctx),
    ): Result<Outcome> {
        val tableId = VoicePrefs.boostingTableId(ctx)
        val apiKey = VoicePrefs.apiKey(ctx)
        if (tableId.isBlank()) return Result.success(Outcome.Skipped("未配置云端热词表 ID"))
        if (apiKey.isBlank()) return Result.success(Outcome.Skipped("未配置火山 API Key"))
        val cleaned = HotwordSanitizer.sanitize(words)
        if (cleaned.isEmpty()) {
            return Result.success(
                Outcome.Skipped("没有热词；火山热词表不接受空文件，云端旧表未清空"),
            )
        }
        return withContext(Dispatchers.IO) {
            VolcHotwordApi.updateBoostingTable(apiKey, tableId, cleaned)
                .map { Outcome.Synced(cleaned.size) }
        }
    }
}
