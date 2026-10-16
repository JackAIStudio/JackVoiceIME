/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * 语音相关的本地设置，存在 Trime 的默认 SharedPreferences 里。
 *
 * 与 `plugin/`（Xime 插件版）和 JackVoice 独立应用共用同一套 API Key / Resource ID，
 * 换哪一端都不用重新申请。设置页落盘时写的就是这几个 key。
 */
object VoicePrefs {
    const val KEY_API_KEY = "voice_api_key"
    const val KEY_RESOURCE_ID = "voice_resource_id"
    const val KEY_HOTWORDS_ENABLED = "voice_hotwords_enabled"

    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API_KEY, "").orEmpty().trim()

    fun resourceId(ctx: Context): String =
        prefs(ctx).getString(KEY_RESOURCE_ID, "").orEmpty().trim()
            .ifEmpty { VolcProtocol.DEFAULT_RESOURCE_ID }

    fun hotwordsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HOTWORDS_ENABLED, true)

    fun setApiKey(
        ctx: Context,
        value: String,
    ) = prefs(ctx).edit { putString(KEY_API_KEY, value.trim()) }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)
}
