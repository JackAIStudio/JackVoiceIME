/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本地听写历史。存在应用私有目录里的一个 JSON 文件，不上云。
 *
 * 对齐 Typeless 的「历史记录」页：按时间倒序、可复制、可删除。
 */
object VoiceHistory {
    data class Entry(
        val text: String,
        val atMillis: Long,
        val durationMs: Long,
    )

    private const val FILE_NAME = "voice_history.json"
    private const val MAX_ENTRIES = 500

    private val cache = CopyOnWriteArrayList<Entry>()

    @Volatile
    private var loaded = false

    @Synchronized
    fun list(ctx: Context): List<Entry> {
        ensureLoaded(ctx)
        return cache.toList()
    }

    @Synchronized
    fun append(
        ctx: Context,
        text: String,
        durationMs: Long,
    ) {
        if (text.isBlank()) return
        ensureLoaded(ctx)
        cache.add(0, Entry(text, System.currentTimeMillis(), durationMs))
        while (cache.size > MAX_ENTRIES) cache.removeAt(cache.size - 1)
        persist(ctx)
    }

    @Synchronized
    fun delete(
        ctx: Context,
        entry: Entry,
    ) {
        ensureLoaded(ctx)
        cache.remove(entry)
        persist(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        ensureLoaded(ctx)
        cache.clear()
        persist(ctx)
    }

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cache.add(
                    Entry(
                        o.optString("text"),
                        o.optLong("at"),
                        o.optLong("dur"),
                    ),
                )
            }
        }.onFailure { Timber.w(it, "读取听写历史失败") }
    }

    private fun persist(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            cache.forEach {
                arr.put(
                    JSONObject()
                        .put("text", it.text)
                        .put("at", it.atMillis)
                        .put("dur", it.durationMs),
                )
            }
            file(ctx).writeText(arr.toString())
        }.onFailure { Timber.w(it, "写入听写历史失败") }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)
}

/** 由历史记录算出来的统计，对齐 Typeless 首页那张卡。 */
object VoiceStats {
    data class Snapshot(
        val words: Int,
        val totalMs: Long,
        val avgWordsPerMinute: Int,
        val savedMs: Long,
        val activeDays: Int,
        val streakDays: Int,
        val longestStreakDays: Int,
    )

    /** 打字速度按 40 字/分钟估，用来算「节省的时间」。 */
    private const val TYPING_WPM = 40

    fun compute(entries: List<VoiceHistory.Entry>): Snapshot {
        if (entries.isEmpty()) return Snapshot(0, 0L, 0, 0L, 0, 0, 0)

        val words = entries.sumOf { it.text.length }
        val totalMs = entries.sumOf { it.durationMs }
        val minutes = totalMs / 60000.0
        val avg = if (minutes > 0.05) (words / minutes).toInt() else 0

        val typingMs = words.toDouble() / TYPING_WPM * 60000.0
        val saved = (typingMs - totalMs).toLong().coerceAtLeast(0L)

        val days = entries.map { dayKey(it.atMillis) }.toSortedSet()
        val longest = longestRun(days)
        val streak = currentRun(days)

        return Snapshot(
            words = words,
            totalMs = totalMs,
            avgWordsPerMinute = avg,
            savedMs = saved,
            activeDays = days.size,
            streakDays = streak,
            longestStreakDays = longest,
        )
    }

    private fun dayKey(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun longestRun(days: Set<Long>): Int {
        if (days.isEmpty()) return 0
        val day = 24L * 60 * 60 * 1000
        var best = 1
        var run = 1
        val sorted = days.toList()
        for (i in 1 until sorted.size) {
            run = if (sorted[i] - sorted[i - 1] == day) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** 当前连续天数：从今天或昨天往回数。 */
    private fun currentRun(days: Set<Long>): Int {
        if (days.isEmpty()) return 0
        val day = 24L * 60 * 60 * 1000
        val today = dayKey(System.currentTimeMillis())
        var cursor = if (days.contains(today)) today else today - day
        if (!days.contains(cursor)) return 0
        var run = 0
        while (days.contains(cursor)) {
            run++
            cursor -= day
        }
        return run
    }
}
