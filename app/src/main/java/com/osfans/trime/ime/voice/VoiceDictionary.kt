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
import java.util.UUID

/**
 * 词库：首次安装把 assets 里的默认词库灌进用户数据，之后所有条目都可编辑。
 *
 * 和桌面版一致，不再区分“内置/我的”。旧版本的 user/hidden 列表只在迁移时读一次，
 * 保证已经删过或加过的词不会因为这次升级丢状态。
 */
object VoiceDictionary {
    const val CLOUD_FORMAT = "jackvoice-glossary-v1"

    private const val KEY_GLOSSARY_HOTWORDS = "voice_glossary_hotwords_v1"
    private const val KEY_GLOSSARY_RULES = "voice_glossary_rules_v1"
    private const val KEY_GLOSSARY_SEEDED = "voice_glossary_seeded_v1"

    // 旧版本存储，仅用于迁移。
    private const val KEY_USER_HOTWORDS = "voice_user_hotwords"
    private const val KEY_HIDDEN_HOTWORDS = "voice_hidden_hotwords"
    private const val KEY_USER_RULES = "voice_user_rules"
    private const val KEY_HIDDEN_RULES = "voice_hidden_rules"

    data class Rule(
        val from: String,
        val to: String,
    )

    data class Snapshot(
        val hotwords: List<String>,
        val rules: List<Rule>,
    )

    // ── 热词 ────────────────────────────────────────────────────────────

    fun hotwords(ctx: Context): List<String> {
        ensureSeeded(ctx)
        return HotwordSanitizer.sanitize(readList(ctx, KEY_GLOSSARY_HOTWORDS))
    }

    /** @return 词合法并已保存/本来就在词库里时为 true。 */
    fun addHotword(
        ctx: Context,
        word: String,
    ): Boolean {
        val cleaned = HotwordSanitizer.normalize(word)
        if (cleaned.isEmpty() || !HotwordSanitizer.isValid(cleaned)) return false
        val current = hotwords(ctx)
        if (current.any { it.equals(cleaned, ignoreCase = true) }) return true
        writeList(ctx, KEY_GLOSSARY_HOTWORDS, current + cleaned)
        VoicePrefs.markGlossaryLocalChange(ctx)
        return true
    }

    fun removeHotword(
        ctx: Context,
        word: String,
    ): Boolean {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return false
        val current = hotwords(ctx)
        val next = current.filterNot { it.lowercase() == key }
        if (next.size == current.size) return false
        writeList(ctx, KEY_GLOSSARY_HOTWORDS, next)
        VoicePrefs.markGlossaryLocalChange(ctx)
        return true
    }

    // ── 替换规则 ────────────────────────────────────────────────────────

    fun rules(ctx: Context): List<Rule> {
        ensureSeeded(ctx)
        return mergeRules(emptyList(), readRules(ctx, KEY_GLOSSARY_RULES))
    }

    /** 添加或更新一条规则；同名来源忽略大小写。 */
    fun addRule(
        ctx: Context,
        from: String,
        to: String,
    ): Boolean {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty()) return false
        val current = rules(ctx)
        val key = f.lowercase()
        val index = current.indexOfFirst { it.from.lowercase() == key }
        val next = current.toMutableList()
        if (index >= 0) {
            next[index] = next[index].copy(to = t)
        } else {
            next += Rule(f, t)
        }
        writeRules(ctx, KEY_GLOSSARY_RULES, next)
        VoicePrefs.markGlossaryLocalChange(ctx)
        return true
    }

    /** 编辑时如果来源改了，先把旧来源删掉。 */
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
        val current = rules(ctx)
        val next = current.filterNot { it.from.lowercase() == key }
        if (next.size == current.size) return false
        writeRules(ctx, KEY_GLOSSARY_RULES, next)
        VoicePrefs.markGlossaryLocalChange(ctx)
        return true
    }

    // ── 云端 glossary.json ─────────────────────────────────────────────

    /** 用云端词库整体替换本机词库。 */
    fun replaceAll(
        ctx: Context,
        hotwords: List<String>,
        rules: List<Rule>,
    ) {
        writeList(ctx, KEY_GLOSSARY_HOTWORDS, HotwordSanitizer.sanitize(hotwords))
        writeRules(ctx, KEY_GLOSSARY_RULES, mergeRules(emptyList(), rules))
        prefs(ctx).edit { putBoolean(KEY_GLOSSARY_SEEDED, true) }
    }

    fun localSnapshot(ctx: Context): Snapshot = Snapshot(hotwords(ctx), rules(ctx))

    /**
     * 生成要推送的 `jackvoice-glossary-v1`。
     * 已存在的条目沿用原 ID，新增条目生成稳定 UUID。
     */
    fun toCloudGlossary(
        ctx: Context,
        existing: JSONObject?,
        updatedBy: String,
    ): JSONObject = toCloudGlossary(hotwords(ctx), rules(ctx), existing, updatedBy)

    fun toCloudGlossary(
        hotwords: List<String>,
        rules: List<Rule>,
        existing: JSONObject?,
        updatedBy: String,
    ): JSONObject {
        val oldHotwords = HashMap<String, JSONObject>()
        val oldReplacements = HashMap<String, JSONObject>()
        existing?.optJSONArray("hotwords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val term = HotwordSanitizer.normalize(item.optString("term")).lowercase()
                if (term.isNotEmpty()) oldHotwords[term] = item
            }
        }
        existing?.optJSONArray("replacements")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val from = item.optString("from").trim().lowercase()
                if (from.isNotEmpty()) oldReplacements[from] = item
            }
        }

        val normalizedHotwords = HotwordSanitizer.sanitize(hotwords)
        val seenHotwords = normalizedHotwords.map { it.lowercase() }.toHashSet()
        val hotwordArray = JSONArray()
        normalizedHotwords.forEach { term ->
            val old = oldHotwords[term.lowercase()]
            hotwordArray.put(
                JSONObject()
                    .put("id", old?.optString("id").orEmpty().ifEmpty { newId() })
                    .put("term", term)
                    .put("isEnabled", true),
            )
        }

        val mergedRules = mergeRules(emptyList(), rules)
        val seenRules = mergedRules.map { it.from.trim().lowercase() }.toHashSet()
        val replacementArray = JSONArray()
        mergedRules.forEach { rule ->
            val old = oldReplacements[rule.from.lowercase()]
            replacementArray.put(
                JSONObject()
                    .put("id", old?.optString("id").orEmpty().ifEmpty { newId() })
                    .put("from", rule.from)
                    .put("to", rule.to)
                    .put("isEnabled", true),
            )
        }

        // 当前 UI 没有“停用”开关，但云端已有的停用项不能因为一次推送就消失。
        existing?.optJSONArray("hotwords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optBoolean("isEnabled", true)) continue
                val term = HotwordSanitizer.normalize(item.optString("term"))
                if (term.isEmpty() || term.lowercase() in seenHotwords) continue
                hotwordArray.put(item)
            }
        }

        existing?.optJSONArray("replacements")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optBoolean("isEnabled", true)) continue
                val from = item.optString("from").trim().lowercase()
                if (from.isEmpty() || from in seenRules) continue
                replacementArray.put(item)
            }
        }

        return JSONObject()
            .put("format", CLOUD_FORMAT)
            .put("version", 1)
            .put("updatedAt", nowIso())
            .put("updatedBy", updatedBy)
            .put("hotwords", hotwordArray)
            .put("replacements", replacementArray)
    }

    /** @return 实际写入的热词数、替换规则数。 */
    fun applyCloudGlossary(
        ctx: Context,
        glossary: JSONObject,
    ): Pair<Int, Int> {
        val snapshot = cloudSnapshot(glossary) ?: error("云端词库格式不是 $CLOUD_FORMAT")
        replaceAll(ctx, snapshot.hotwords, snapshot.rules)
        return snapshot.hotwords.size to snapshot.rules.size
    }

    /** 云端 JSON → 本机可用的词库；格式不对时返回 null，绝不拿空数组去覆盖本地。 */
    fun cloudSnapshot(glossary: JSONObject): Snapshot? {
        if (glossary.optString("format") != CLOUD_FORMAT) return null
        val parsedHotwords = ArrayList<String>()
        glossary.optJSONArray("hotwords")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (!item.optBoolean("isEnabled", true)) continue
                val term = item.optString("term").trim()
                if (term.isNotEmpty()) parsedHotwords += term
            }
        }
        val parsedRules = ArrayList<Rule>()
        glossary.optJSONArray("replacements")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (!item.optBoolean("isEnabled", true)) continue
                val from = item.optString("from").trim()
                val to = item.optString("to").trim()
                if (from.isNotEmpty() && to.isNotEmpty()) parsedRules += Rule(from, to)
            }
        }
        return Snapshot(HotwordSanitizer.sanitize(parsedHotwords), mergeRules(emptyList(), parsedRules))
    }

    private fun newId(): String = UUID.randomUUID().toString().uppercase()

    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    // ── 迁移 ────────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureSeeded(ctx: Context) {
        val prefs = prefs(ctx)
        if (prefs.getBoolean(KEY_GLOSSARY_SEEDED, false)) return

        val hiddenHotwords =
            readList(ctx, KEY_HIDDEN_HOTWORDS)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        val oldUserHotwords = HotwordSanitizer.sanitize(readList(ctx, KEY_USER_HOTWORDS))
        val seededHotwords =
            HotwordSanitizer.sanitize(
                HotwordSanitizer.sanitize(BuiltinData.hotwords(ctx))
                    .filterNot { it.lowercase() in hiddenHotwords } + oldUserHotwords,
            )

        val hiddenRules =
            readList(ctx, KEY_HIDDEN_RULES)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        val builtinRules =
            BuiltinData.rules(ctx)
                .filterNot { it.from.trim().lowercase() in hiddenRules }
                .map { Rule(it.from, it.to) }
        val seededRules = mergeRules(builtinRules, readRules(ctx, KEY_USER_RULES))

        writeList(ctx, KEY_GLOSSARY_HOTWORDS, seededHotwords)
        writeRules(ctx, KEY_GLOSSARY_RULES, seededRules)
        prefs.edit { putBoolean(KEY_GLOSSARY_SEEDED, true) }
    }

    /** 同来源保留第一次出现的 from 写法，后出现的 to 覆盖先出现的 to。 */
    private fun mergeRules(
        primary: List<Rule>,
        overrides: List<Rule>,
    ): List<Rule> {
        val out = ArrayList<Rule>()
        val indexes = HashMap<String, Int>()
        for (rule in primary + overrides) {
            val from = rule.from.trim()
            val to = rule.to.trim()
            if (from.isEmpty() || to.isEmpty()) continue
            val key = from.lowercase()
            val index = indexes[key]
            if (index != null) {
                out[index] = out[index].copy(to = to)
            } else {
                indexes[key] = out.size
                out += Rule(from, to)
            }
        }
        return out
    }

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

    private fun readRules(
        ctx: Context,
        key: String,
    ): List<Rule> = runCatching {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val from = o.optString("from").trim()
            val to = o.optString("to").trim()
            if (from.isEmpty() || to.isEmpty()) null else Rule(from, to)
        }
    }.getOrDefault(emptyList())

    private fun writeRules(
        ctx: Context,
        key: String,
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
        putString(key, arr.toString())
    }
}
