package com.osfans.trime.ime.voice

import android.content.Context
import org.json.JSONArray

/**
 * 内置词库。数据来自 `app/src/main/assets/`，
 * 由 `plugin/src/data/` 的同一份源数据转换而来（59 条热词 + 76 条替换规则），
 * 保证 App 与 Xime 插件行为一致。
 */
object BuiltinData {

    @Volatile
    private var hotwordsCache: List<String>? = null

    @Volatile
    private var rulesCache: List<ReplacementEngine.Rule>? = null

    fun hotwords(ctx: Context): List<String> {
        hotwordsCache?.let { return it }
        val list = runCatching {
            val text = ctx.assets.open("hotwords.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        hotwordsCache = list
        return list
    }

    fun rules(ctx: Context): List<ReplacementEngine.Rule> {
        rulesCache?.let { return it }
        val list = runCatching {
            val text = ctx.assets.open("replacements.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ReplacementEngine.Rule(o.getString("from"), o.getString("to"))
            }
        }.getOrDefault(emptyList())
        rulesCache = list
        return list
    }
}
