/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import android.content.Context
import com.osfans.trime.TrimeApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File

/**
 * 最近用过的 emoji。
 *
 * 单独一个文件，不进 SharedPreferences：这份数据会频繁写（每点一个 emoji 一次），
 * 而 SharedPreferences 写一次要序列化整个 XML。用文件存，坏了大不了重建。
 *
 * 排序规则：**用得多的在前；次数相同按最近用的倒序**。所以「最近/常用」是同一个列表，
 * 不会出现「刚用过的沉到底下」这种事。
 *
 * 容量 60：候选行最多用 24 个，展开面板第一屏也用不完，再多就是垃圾。
 */
object EmojiUsage {
    private const val FILE_NAME = "emoji_usage.json"
    private const val CAPACITY = 60

    private val json = Json

    private data class Entry(
        val count: Int,
        val last: Long,
    )

    private val lock = Any()

    /** emoji → (次数, 最后使用时间)。**只在内存里维护，改动时整体落盘。** */
    private var entries: MutableMap<String, Entry> = mutableMapOf()

    @Volatile
    private var loaded = false

    private fun file(): File = File(TrimeApplication.getInstance().directBootAwareContext.filesDir, FILE_NAME)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            loaded = true
            runCatching {
                val f = file()
                if (!f.isFile) return@runCatching
                val root = json.parseToJsonElement(f.readText()).let { it as JsonObject }
                for ((emoji, value) in root) {
                    val obj = value as? JsonObject ?: continue
                    val count = obj["c"]?.toString()?.toIntOrNull() ?: continue
                    val last = obj["t"]?.toString()?.toLongOrNull() ?: 0L
                    entries[emoji] = Entry(count, last)
                }
            }.onFailure { Timber.w(it, "emoji 使用记录读取失败，从空开始") }
        }
    }

    /**
     * 记一次使用。
     *
     * 落盘在锁内同步做——一次写入只有几 KB，比丢记录强。输入法进程被杀之前
     * 没有可靠的回调，异步写有丢数据的风险。
     */
    fun record(emoji: String) {
        if (emoji.isEmpty()) return
        ensureLoaded()
        synchronized(lock) {
            val prev = entries[emoji]
            entries[emoji] =
                Entry(
                    count = (prev?.count ?: 0) + 1,
                    last = System.currentTimeMillis(),
                )
            trim()
            persist()
        }
    }

    /** 最近 / 最常用的一批，给候选行和展开面板用。 */
    fun recent(limit: Int = EmojiIndex.STRIP_LIMIT): List<String> {
        ensureLoaded()
        synchronized(lock) {
            return entries.entries
                .sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.count }.thenByDescending { it.value.last })
                .take(limit)
                .map { it.key }
        }
    }

    /** 只在超出容量时丢掉末尾那几个用得最少的。 */
    private fun trim() {
        if (entries.size <= CAPACITY) return
        val drop =
            entries.entries
                .sortedWith(compareBy<Map.Entry<String, Entry>> { it.value.count }.thenBy { it.value.last })
                .take(entries.size - CAPACITY)
                .map { it.key }
        drop.forEach { entries.remove(it) }
    }

    private fun persist() {
        runCatching {
            val root = buildJsonObject {
                entries.forEach { (emoji, e) ->
                    put(
                        emoji,
                        buildJsonObject {
                            put("c", e.count)
                            put("t", e.last)
                        },
                    )
                }
            }
            // 先写临时文件再改名：写到一半进程被杀，不会留下半个 JSON 把下次读崩。
            val tmp = File(file().parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file())) {
                file().writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Timber.w(it, "emoji 使用记录写入失败") }
    }

    /** 测试用：清空内存态。 */
    internal fun resetForTest() {
        synchronized(lock) {
            entries = mutableMapOf()
            loaded = false
        }
    }
}
