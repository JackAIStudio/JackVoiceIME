/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.BuildConfig

/**
 * 语音相关的本地设置，存在 Trime 的默认 SharedPreferences 里。
 *
 * 与 JackVoice 桌面版共用同一套 API Key / Resource ID / 热词表，
 * 换哪一端都不用重新申请。设置页落盘时写的就是这几个 key。
 */
object VoicePrefs {
    const val KEY_API_KEY = "voice_api_key"
    const val KEY_RESOURCE_ID = "voice_resource_id"
    const val KEY_BOOSTING_TABLE_ID = "voice_boosting_table_id"
    const val KEY_HOTWORDS_ENABLED = "voice_hotwords_enabled"
    const val KEY_LAST_TAB = "voice_last_tab"
    const val KEY_TOS_ACCESS_KEY = "voice_tos_access_key"
    const val KEY_TOS_SECRET_KEY = "voice_tos_secret_key"
    const val KEY_TOS_BUCKET = "voice_tos_bucket"
    const val KEY_TOS_REGION = "voice_tos_region"
    const val KEY_TOS_ENDPOINT = "voice_tos_endpoint"

    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API_KEY, "").orEmpty().trim()

    fun resourceId(ctx: Context): String = prefs(ctx).getString(KEY_RESOURCE_ID, "").orEmpty().trim()
        .ifEmpty { VolcProtocol.DEFAULT_RESOURCE_ID }

    /** 和桌面版共用云端热词表时使用；首次安装默认取构建时的本地配置。 */
    fun boostingTableId(ctx: Context): String = prefs(ctx)
        .getString(KEY_BOOSTING_TABLE_ID, BuildConfig.VOLC_BOOSTING_TABLE_ID)
        .orEmpty()
        .trim()

    fun hotwordsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HOTWORDS_ENABLED, true)

    fun setApiKey(
        ctx: Context,
        value: String,
    ) = prefs(ctx).edit { putString(KEY_API_KEY, value.trim()) }

    fun setHotwordsEnabled(
        ctx: Context,
        value: Boolean,
    ) = prefs(ctx).edit { putBoolean(KEY_HOTWORDS_ENABLED, value) }

    fun setBoostingTableId(
        ctx: Context,
        value: String,
    ) = prefs(ctx).edit { putString(KEY_BOOSTING_TABLE_ID, value.trim()) }

    fun tosAccessKey(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_ACCESS_KEY, "").orEmpty().trim()

    fun tosSecretKey(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_SECRET_KEY, "").orEmpty().trim()

    fun tosBucket(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_BUCKET, "jackvoice-glossary").orEmpty().trim()

    fun tosRegion(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_REGION, "cn-beijing").orEmpty().trim()

    fun tosEndpoint(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_ENDPOINT, "tos-cn-beijing.volces.com").orEmpty().trim()

    fun setTosAccessKey(
        ctx: Context,
        value: String,
    ) = prefs(ctx).edit { putString(KEY_TOS_ACCESS_KEY, value.trim()) }

    fun setTosSecretKey(
        ctx: Context,
        value: String,
    ) = prefs(ctx).edit { putString(KEY_TOS_SECRET_KEY, value.trim()) }

    fun setTosBucket(
        ctx: Context,
        value: String,
    ) = prefs(ctx).edit { putString(KEY_TOS_BUCKET, value.trim()) }

    /**
     * 用户上一次停在哪个模块（语音 / 拼音 / EN）。
     *
     * 每次展开键盘先回到这一屏，而不是永远弹语音面板；「收起键盘」不算一次选择，
     * 所以 [VoiceTab.Hide] 永远不落盘。默认仍是语音——这是这个输入法的主键盘。
     */
    fun lastTab(ctx: Context): VoiceTab {
        val name = prefs(ctx).getString(KEY_LAST_TAB, null) ?: return VoiceTab.Voice
        return VoiceTab.entries.firstOrNull { it.name == name && it != VoiceTab.Hide }
            ?: VoiceTab.Voice
    }

    fun setLastTab(
        ctx: Context,
        tab: VoiceTab,
    ) {
        if (tab == VoiceTab.Hide) return
        if (lastTab(ctx) == tab) return
        prefs(ctx).edit { putString(KEY_LAST_TAB, tab.name) }
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)
}
