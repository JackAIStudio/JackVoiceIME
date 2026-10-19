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
    const val KEY_PUNCTUATION_MODE = "voice_punctuation_mode"
    const val KEY_LAST_TAB = "voice_last_tab"
    const val KEY_TOS_ACCESS_KEY = "voice_tos_access_key"
    const val KEY_TOS_SECRET_KEY = "voice_tos_secret_key"
    const val KEY_TOS_BUCKET = "voice_tos_bucket"
    const val KEY_TOS_REGION = "voice_tos_region"
    const val KEY_TOS_ENDPOINT = "voice_tos_endpoint"
    const val KEY_GLOSSARY_BASE = "voice_glossary_base_v1"
    const val KEY_GLOSSARY_LAST_SYNCED_AT = "voice_glossary_last_synced_at"
    const val KEY_GLOSSARY_LAST_ERROR = "voice_glossary_last_error"
    const val KEY_GLOSSARY_LOCAL_CHANGED_AT = "voice_glossary_local_changed_at"
    const val KEY_GLOSSARY_LOCAL_REVISION = "voice_glossary_local_revision"
    const val KEY_GLOSSARY_LAST_CONFLICTS = "voice_glossary_last_conflicts"
    const val KEY_GLOSSARY_AUTO_SYNC = "voice_glossary_auto_sync"

    fun apiKey(ctx: Context): String = prefs(ctx).getString(KEY_API_KEY, "").orEmpty().trim()

    fun resourceId(ctx: Context): String = prefs(ctx).getString(KEY_RESOURCE_ID, "").orEmpty().trim()
        .ifEmpty { VolcProtocol.DEFAULT_RESOURCE_ID }

    /** 和桌面版共用云端热词表时使用；首次安装默认取构建时的本地配置。 */
    fun boostingTableId(ctx: Context): String = prefs(ctx)
        .getString(KEY_BOOSTING_TABLE_ID, BuildConfig.VOLC_BOOSTING_TABLE_ID)
        .orEmpty()
        .trim()

    fun hotwordsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HOTWORDS_ENABLED, true)

    /** 默认全标点：第一次用不丢东西，要改在键盘左下角一点就切。 */
    fun punctuationMode(ctx: Context): PunctuationMode = PunctuationMode.fromId(prefs(ctx).getString(KEY_PUNCTUATION_MODE, null))

    fun setPunctuationMode(
        ctx: Context,
        mode: PunctuationMode,
    ) = prefs(ctx).edit { putString(KEY_PUNCTUATION_MODE, mode.id) }

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

    fun tosAccessKey(ctx: Context): String {
        migrateLegacyTosCredentials(ctx)
        return TosCredentialStore.load(ctx)?.accessKeyId.orEmpty()
    }

    fun tosSecretKey(ctx: Context): String {
        migrateLegacyTosCredentials(ctx)
        return TosCredentialStore.load(ctx)?.secretAccessKey.orEmpty()
    }

    fun tosBucket(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_BUCKET, "jackvoice-glossary").orEmpty().trim()

    fun tosRegion(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_REGION, "cn-beijing").orEmpty().trim()

    fun tosEndpoint(ctx: Context): String =
        prefs(ctx).getString(KEY_TOS_ENDPOINT, "tos-cn-beijing.volces.com").orEmpty().trim()

    fun setTosAccessKey(
        ctx: Context,
        value: String,
    ) = TosCredentialStore.setAccessKey(ctx, value)

    fun setTosSecretKey(
        ctx: Context,
        value: String,
    ) = TosCredentialStore.setSecretKey(ctx, value)

    fun glossaryBase(ctx: Context): String? =
        prefs(ctx).getString(KEY_GLOSSARY_BASE, null)?.takeIf { it.isNotBlank() }

    fun setGlossaryBase(
        ctx: Context,
        value: String?,
    ) = prefs(ctx).edit {
        if (value.isNullOrBlank()) remove(KEY_GLOSSARY_BASE) else putString(KEY_GLOSSARY_BASE, value)
    }

    fun glossaryLastSyncedAt(ctx: Context): Long =
        prefs(ctx).getLong(KEY_GLOSSARY_LAST_SYNCED_AT, 0L)

    fun setGlossaryLastSyncedAt(
        ctx: Context,
        value: Long,
    ) = prefs(ctx).edit { putLong(KEY_GLOSSARY_LAST_SYNCED_AT, value) }

    fun glossaryLastError(ctx: Context): String? =
        prefs(ctx).getString(KEY_GLOSSARY_LAST_ERROR, null)?.takeIf { it.isNotBlank() }

    fun setGlossaryLastError(
        ctx: Context,
        value: String?,
    ) = prefs(ctx).edit {
        if (value.isNullOrBlank()) remove(KEY_GLOSSARY_LAST_ERROR) else putString(KEY_GLOSSARY_LAST_ERROR, value)
    }

    fun glossaryLocalChangedAt(ctx: Context): Long =
        prefs(ctx).getLong(KEY_GLOSSARY_LOCAL_CHANGED_AT, 0L)

    fun markGlossaryLocalChange(ctx: Context) {
        val now = System.currentTimeMillis()
        val nextRevision = prefs(ctx).getLong(KEY_GLOSSARY_LOCAL_REVISION, 0L) + 1L
        prefs(ctx).edit {
            putLong(KEY_GLOSSARY_LOCAL_CHANGED_AT, now)
            putLong(KEY_GLOSSARY_LOCAL_REVISION, nextRevision)
        }
    }

    fun clearGlossaryLocalChange(ctx: Context) = prefs(ctx).edit {
        remove(KEY_GLOSSARY_LOCAL_CHANGED_AT)
    }

    fun glossaryLocalRevision(ctx: Context): Long =
        prefs(ctx).getLong(KEY_GLOSSARY_LOCAL_REVISION, 0L)

    fun glossaryLastConflicts(ctx: Context): Int =
        prefs(ctx).getInt(KEY_GLOSSARY_LAST_CONFLICTS, 0)

    fun setGlossaryLastConflicts(
        ctx: Context,
        value: Int,
    ) = prefs(ctx).edit { putInt(KEY_GLOSSARY_LAST_CONFLICTS, value) }

    fun glossaryAutoSync(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_GLOSSARY_AUTO_SYNC, true)

    fun setGlossaryAutoSync(
        ctx: Context,
        value: Boolean,
    ) = prefs(ctx).edit { putBoolean(KEY_GLOSSARY_AUTO_SYNC, value) }

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

    /** 旧版本把 TOS 密钥写在默认 SharedPreferences 里，会被 Auto Backup 带上。 */
    private fun migrateLegacyTosCredentials(ctx: Context) {
        val p = prefs(ctx)
        val accessKey = p.getString(KEY_TOS_ACCESS_KEY, "").orEmpty().trim()
        val secretKey = p.getString(KEY_TOS_SECRET_KEY, "").orEmpty().trim()
        if (accessKey.isEmpty() && secretKey.isEmpty()) return
        val stored = TosCredentialStore.load(ctx)
        val mergedAccessKey = stored?.accessKeyId?.takeIf { it.isNotBlank() } ?: accessKey
        val mergedSecretKey = stored?.secretAccessKey?.takeIf { it.isNotBlank() } ?: secretKey
        TosCredentialStore.save(ctx, mergedAccessKey, mergedSecretKey)
        p.edit {
            remove(KEY_TOS_ACCESS_KEY)
            remove(KEY_TOS_SECRET_KEY)
        }
    }
}
