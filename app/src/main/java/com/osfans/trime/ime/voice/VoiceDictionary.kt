/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 词典：内置热词/替换规则（打包在 assets 里）+ 用户自己加的。
 *
 * 和桌面版一致，内置项也可以删：删内置热词只是把它加入隐藏列表，删内置替换词
 * 只是把这个来源加入隐藏列表；用户之后重新添加时再取消隐藏。
 */
object VoiceDictionary {
    private const val KEY_USER_HOTWORDS = "voice_user_hotwords"
    private const val KEY_HIDDEN_HOTWORDS = "voice_hidden_hotwords"
    private const val KEY_USER_RULES = "voice_user_rules"
    private const val KEY_HIDDEN_RULES = "voice_hidden_rules"

    data class Rule(
        val from: String,
        val to: String,
        val builtin: Boolean,
    )

    // ── 热词 ────────────────────────────────────────────────────────────

    fun builtinHotwords(ctx: Context): List<String> = HotwordSanitizer.sanitize(BuiltinData.hotwords(ctx))

    fun userHotwords(ctx: Context): List<String> = HotwordSanitizer.sanitize(readList(ctx, KEY_USER_HOTWORDS))

    fun hotwords(ctx: Context): List<String> {
        val hidden = hiddenHotwordKeys(ctx)
        val builtin = builtinHotwords(ctx).filterNot { it.lowercase() in hidden }
        return HotwordSanitizer.sanitize(builtin + userHotwords(ctx))
    }

    /**
     * 添加热词。会按火山识别形归一化（`B roll` → `Broll`）。
     *
     * @return 词合法并已保存/本来就在词库里时为 true。
     */
    fun addHotword(
        ctx: Context,
        word: String,
    ): Boolean {
        val cleaned = HotwordSanitizer.normalize(word)
        if (cleaned.isEmpty() || !HotwordSanitizer.isValid(cleaned)) return false
        val key = cleaned.lowercase()

        val hidden = hiddenHotwordKeys(ctx).toMutableSet()
        val wasHidden = hidden.remove(key)
        if (wasHidden) writeList(ctx, KEY_HIDDEN_HOTWORDS, hidden.toList())

        val user = userHotwords(ctx)
        if (user.any { it.lowercase() == key }) return true
        // 内置词本来就在词库里：不重复写进用户列表，除非它是刚被恢复的。
        if (!wasHidden && builtinHotwords(ctx).any { it.lowercase() == key }) return true

        writeList(ctx, KEY_USER_HOTWORDS, user + cleaned)
        return true
    }

    fun removeHotword(
        ctx: Context,
        word: String,
    ): Boolean {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return false

        val user = userHotwords(ctx)
        val userHadIt = user.any { it.lowercase() == key }
        if (userHadIt) {
            writeList(ctx, KEY_USER_HOTWORDS, user.filterNot { it.lowercase() == key })
        }

        val builtinHadIt = builtinHotwords(ctx).any { it.lowercase() == key }
        if (builtinHadIt) {
            writeList(ctx, KEY_HIDDEN_HOTWORDS, (hiddenHotwordKeys(ctx) + key).toList())
        }
        return userHadIt || builtinHadIt
    }

    private fun hiddenHotwordKeys(ctx: Context): Set<String> = readList(ctx, KEY_HIDDEN_HOTWORDS)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    // ── 替换规则 ────────────────────────────────────────────────────────

    fun builtinRules(ctx: Context): List<Rule> = BuiltinData.rules(ctx).map { Rule(it.from, it.to, true) }

    /** 用户自己维护的规则（含覆盖内置的规则），已按来源去重并按长度排序。 */
    fun userRules(ctx: Context): List<Rule> {
        val out = ArrayList<Rule>()
        val indexes = HashMap<String, Int>()
        for (rule in readRules(ctx)) {
            val from = rule.from.trim()
            val to = rule.to.trim()
            if (from.isEmpty() || to.isEmpty()) continue
            val key = from.lowercase()
            val index = indexes[key]
            if (index != null) {
                out[index] = out[index].copy(to = to)
            } else {
                indexes[key] = out.size
                out += Rule(from, to, false)
            }
        }
        return out.sortedByDescending { it.from.length }
    }

    /** 合并内置与用户规则：用户覆盖同来源的内置规则，保留内置的展示写法。 */
    fun rules(ctx: Context): List<Rule> {
        val hidden = hiddenRuleKeys(ctx)
        val merged = LinkedHashMap<String, Rule>()
        for (rule in builtinRules(ctx)) {
            val key = rule.from.trim().lowercase()
            if (key.isEmpty() || key in hidden) continue
            merged[key] = rule
        }
        for (rule in userRules(ctx)) {
            val key = rule.from.lowercase()
            val existing = merged[key]
            merged[key] =
                if (existing != null) {
                    existing.copy(to = rule.to, builtin = false)
                } else {
                    rule
                }
        }
        return merged.values.toList()
    }

    /** 添加或更新一条用户规则；同名来源忽略大小写。 */
    fun addRule(
        ctx: Context,
        from: String,
        to: String,
    ): Boolean {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty()) return false
        val key = f.lowercase()

        val rules = userRules(ctx).toMutableList()
        val index = rules.indexOfFirst { it.from.lowercase() == key }
        if (index >= 0) {
            rules[index] = rules[index].copy(to = t)
        } else {
            rules += Rule(f, t, false)
        }
        writeRules(ctx, rules)

        val hidden = hiddenRuleKeys(ctx).toMutableSet()
        if (hidden.remove(key)) writeList(ctx, KEY_HIDDEN_RULES, hidden.toList())
        return true
    }

    /** 编辑时如果来源改了，先把旧来源删掉（内置来源会同时隐藏）。 */
    fun updateRule(
        ctx: Context,
        originalFrom: String,
        from: String,
        to: String,
    ): Boolean {
        if (originalFrom.trim().lowercase() != from.trim().lowercase()) {
            removeRule(ctx, originalFrom)
        }
        return addRule(ctx, from, to)
    }

    fun removeRule(
        ctx: Context,
        from: String,
    ): Boolean {
        val key = from.trim().lowercase()
        if (key.isEmpty()) return false

        val user = userRules(ctx)
        val userHadIt = user.any { it.from.lowercase() == key }
        if (userHadIt) {
            writeRules(ctx, user.filterNot { it.from.lowercase() == key })
        }

        val builtinHadIt = builtinRules(ctx).any { it.from.trim().lowercase() == key }
        if (builtinHadIt) {
            writeList(ctx, KEY_HIDDEN_RULES, (hiddenRuleKeys(ctx) + key).toList())
        }
        return userHadIt || builtinHadIt
    }

    private fun hiddenRuleKeys(ctx: Context): Set<String> = readList(ctx, KEY_HIDDEN_RULES)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    // ── 存储 ────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun readList(
        ctx: Context,
        key: String,
    ): List<String> = runCatching {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { index ->
            if (arr.isNull(index)) null else arr.optString(index).takeIf { it.isNotBlank() }
        }
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
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val from = o.optString("from").trim()
            val to = o.optString("to").trim()
            if (from.isEmpty() || to.isEmpty()) null else Rule(from, to, false)
        }
    }.getOrDefault(emptyList())

    private fun writeRules(
        ctx: Context,
        rules: List<Rule>,
    ) = prefs(ctx).edit {
        val arr = JSONArray()
        rules.forEach {
            arr.put(
                JSONObject()
                    .put("from", it.from)
                    .put("to", it.to),
            )
        }
        putString(KEY_USER_RULES, arr.toString())
    }
}
