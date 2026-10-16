/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray

/**
 * 词典：内置热词/替换规则（打包在 assets 里，与 Xime 插件同源）+ 用户自己加的。
 *
 * 对应 Typeless 的「词典」页：它的「自动添加」≈ 我们的内置词库，
 * 「手动添加」≈ 用户在这里自己加的词。
 */
object VoiceDictionary {
    private const val KEY_USER_HOTWORDS = "voice_user_hotwords"
    private const val KEY_USER_RULES = "voice_user_rules"

    data class Rule(
        val from: String,
        val to: String,
        val builtin: Boolean,
    )

    // ── 热词 ────────────────────────────────────────────────────────────

    fun builtinHotwords(ctx: Context): List<String> = BuiltinData.hotwords(ctx)

    fun userHotwords(ctx: Context): List<String> = readList(ctx, KEY_USER_HOTWORDS)

    fun hotwords(ctx: Context): List<String> {
        val builtin = builtinHotwords(ctx)
        return builtin + userHotwords(ctx).filterNot { it in builtin }
    }

    fun addHotword(
        ctx: Context,
        word: String,
    ) {
        val w = word.trim()
        if (w.isEmpty() || w in hotwords(ctx)) return
        writeList(ctx, KEY_USER_HOTWORDS, userHotwords(ctx) + w)
    }

    fun removeHotword(
        ctx: Context,
        word: String,
    ) {
        writeList(ctx, KEY_USER_HOTWORDS, userHotwords(ctx).filterNot { it == word })
    }

    // ── 替换规则 ────────────────────────────────────────────────────────

    fun builtinRules(ctx: Context): List<Rule> =
        BuiltinData.rules(ctx).map { Rule(it.from, it.to, true) }

    fun rules(ctx: Context): List<Rule> {
        val user = readRules(ctx)
        return builtinRules(ctx) + user
    }

    fun addRule(
        ctx: Context,
        from: String,
        to: String,
    ) {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty()) return
        val existing = readRules(ctx).filterNot { it.from == f }
        writeRules(ctx, existing + Rule(f, t, false))
    }

    fun removeRule(
        ctx: Context,
        from: String,
    ) {
        writeRules(ctx, readRules(ctx).filterNot { it.from == from })
    }

    // ── 存储 ────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun readList(
        ctx: Context,
        key: String,
    ): List<String> = runCatching {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeList(
        ctx: Context,
        key: String,
        value: List<String>,
    ) = prefs(ctx).edit {
        putString(key, JSONArray(value).toString())
    }

    private fun readRules(ctx: Context): List<Rule> = runCatching {
        val raw = prefs(ctx).getString(KEY_USER_RULES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Rule(o.getString("from"), o.getString("to"), false)
        }
    }.getOrDefault(emptyList())

    private fun writeRules(
        ctx: Context,
        rules: List<Rule>,
    ) = prefs(ctx).edit {
        val arr = JSONArray()
        rules.forEach {
            arr.put(
                org.json.JSONObject()
                    .put("from", it.from)
                    .put("to", it.to),
            )
        }
        putString(KEY_USER_RULES, arr.toString())
    }
}
