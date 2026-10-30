/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Emoji 搜索索引。
 *
 * 数据来自 `assets/emoji_index.json`，由 `lexicon/export-emoji-index.mjs` 从
 * Unicode emoji-test + CLDR 中英文关键词 + 拼音数据生成。**只读，不联网，不依赖 Rime。**
 *
 * 文件是明文 JSON（APK 打包时 AAPT 自己会 deflate 到约 150KB）。
 * 试过写 `.json.gz`，但 AAPT 打包 assets 时会把 `.gz` 解压掉，反而多一层坑——
 * 见生成脚本里那段注释。
 *
 * 索引是「词 → emoji 下标列表」，中文关键词、拼音、英文关键词三条都在同一张表里，
 * 所以 `pisa` / `披萨` / `pizza` 搜到的是同一个 🍕。
 */
object EmojiIndex {
    private const val ASSET = "emoji_index.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var loaded = false

    /** 全部 emoji，下标即索引里用的下标，也是 CLDR 的排列顺序。 */
    @Volatile
    var emojis: List<String> = emptyList()
        private set

    @Volatile
    var categories: List<EmojiCategory> = emptyList()
        private set

    /** 词 → emoji 下标。已经按人工纠偏把目标 emoji 插到最前。 */
    @Volatile
    private var index: Map<String, IntArray> = emptyMap()

    /** 下标 → 主名（中文名 / 英文名）。排序用。 */
    @Volatile
    private var primary: Map<Int, List<String>> = emptyMap()

    /** 下标 → 主名拼音（全拼 + 首字母）。 */
    @Volatile
    private var primaryPy: Map<Int, List<String>> = emptyMap()

    /** 词 → 被钉住的 emoji 下标。 */
    @Volatile
    private var pinned: Map<String, Int> = emptyMap()

    /** 下标 → 肤色/性别变体。长按时弹出来。 */
    @Volatile
    private var variants: Map<Int, List<String>> = emptyMap()

    data class EmojiCategory(
        val icon: String,
        val name: String,
        val start: Int,
        val count: Int,
    )

    /**
     * 加载并解析索引。幂等，重复调用只有第一次真的干活。
     *
     * 解析在调用线程同步做（首次约 30–60ms，gzip 解压 + JSON）；调用方都在后台线程
     * 或输入法启动期，不阻塞输入。失败时记住「加载过」，避免每次按键都重试一遍坏数据。
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            runCatching { parse(context) }
                .onFailure {
                    Timber.e(it, "emoji 索引加载失败，emoji 面板将为空")
                    emojis = emptyList()
                    categories = emptyList()
                    index = emptyMap()
                }
        }
    }

    private fun parse(context: Context) {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject

        emojis = root["emojis"]!!.jsonArray.map { it.jsonPrimitive.content }

        categories =
            root["categories"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                EmojiCategory(
                    icon = o["i"]!!.jsonPrimitive.content,
                    name = o["z"]!!.jsonPrimitive.content,
                    start = o["s"]!!.jsonPrimitive.int,
                    count = o["c"]!!.jsonPrimitive.int,
                )
            }

        index =
            root["index"]!!.jsonObject.mapValues { (_, v) ->
                IntArray(v.jsonArray.size) { v.jsonArray[it].jsonPrimitive.int }
            }

        // JSON 对象的键一律是字符串，这里转回 emoji 下标。
        primary =
            root["primary"]?.jsonObject.orEmpty()
                .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.jsonArray.map { e -> e.jsonPrimitive.content } } }
                .toMap()

        primaryPy =
            root["primaryPy"]?.jsonObject.orEmpty()
                .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.jsonArray.map { e -> e.jsonPrimitive.content } } }
                .toMap()

        pinned =
            root["pinned"]?.jsonObject.orEmpty()
                .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.toIntOrNull()?.let { k to it } }
                .toMap()

        variants =
            root["variants"]?.jsonObject.orEmpty()
                .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.jsonArray.map { e -> e.jsonPrimitive.content } } }
                .toMap()

        Timber.d(
            "emoji 索引就绪：%d 个 emoji，%d 个分类，%d 个词条",
            emojis.size,
            categories.size,
            index.size,
        )
    }

    /**
     * 搜索。
     *
     * @param query 用户已经打出的字母（拼音 / 英文）或中文字（如果将来接进来）。
     *   会被归一化成小写；空串返回空。
     * @param limit 最多给多少条。
     * @param recents 最近用过的 emoji。它们不参与打分，直接提到最前面，
     *   保持「刚用过的先出现」的手感。
     */
    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        recents: List<String> = emptyList(),
    ): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        // 下标 → 该 emoji 目前最好的分数
        val best = HashMap<Int, Int>(256)

        for ((term, posting) in index) {
            val match = EmojiRanker.match(term, q) ?: continue
            val pinForTerm = pinned[term] ?: -1
            for (i in posting) {
                val s = i.toString()
                val isPrimary = EmojiRanker.primaryMatches(primary[i], q) ||
                    EmojiRanker.primaryMatches(primaryPy[i], q)
                val score = EmojiRanker.score(pinned = i == pinForTerm, isPrimary = isPrimary, match = match)
                val cur = best[i]
                if (cur == null || score < cur) best[i] = score
            }
        }

        if (best.isEmpty()) return emptyList()

        // 分数小的在前；同分按下标（= CLDR 常用度顺序）。
        val ordered =
            best.entries
                .sortedWith(compareBy({ it.value }, { it.key }))
                .map { emojis[it.key] }

        return withRecentsFirst(ordered, recents).take(limit)
    }

    /** 最近用过的提到最前，其余保持原顺序，去重。 */
    private fun withRecentsFirst(
        ordered: List<String>,
        recents: List<String>,
    ): List<String> {
        if (recents.isEmpty()) return ordered
        val seen = HashSet<String>(recents.size * 2)
        val out = ArrayList<String>(ordered.size + recents.size)
        for (r in recents) {
            if (seen.add(r)) out += r
        }
        for (e in ordered) {
            if (seen.add(e)) out += e
        }
        return out
    }

    /** 某个 emoji 的变体（肤色 / 性别）。长按时弹出来选。 */
    fun variantsOf(emoji: String): List<String> {
        val i = emojis.indexOf(emoji)
        if (i < 0) return emptyList()
        return variants[i] ?: emptyList()
    }

    /** 某个分类下的全部 emoji，按原顺序。 */
    fun category(category: EmojiCategory): List<String> = emojis.subList(category.start, (category.start + category.count).coerceAtMost(emojis.size))

    /** 全部分类展开的顺序：分类一、分类二……（展开面板用来做分页）。 */
    fun allInOrder(): List<String> = emojis

    /** 搜索框还没输入时，候选行显示的那批最近使用的。 */
    const val DEFAULT_LIMIT = 60

    /** 候选条上最多放几个。 */
    const val STRIP_LIMIT = 24
}
