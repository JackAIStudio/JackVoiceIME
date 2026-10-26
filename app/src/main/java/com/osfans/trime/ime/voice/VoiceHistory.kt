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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 本地听写历史。存在应用私有目录里的一个 JSON 文件，不上云。
 *
 * ## 万物留痕
 *
 * 一条记录**先记下「说了这段声音」，再谈识别**。所以录音一开始就 [begin] 建条目，
 * 音频收尾后挂上 [Entry.audio]，识别失败只是把 [Entry.status] 标成 Failed，
 * 记录和 WAV 都还在。用户随时能在历史页点「重新转写」把它补出来。
 *
 * 只有三种情况会真的丢掉一段声音：
 *  1. 用户点了「取消」——那是明确不要了；
 *  2. 一个字节的音频都没录到（麦克风起不来）；
 *  3. 用户手动删掉这条记录 / 清空历史。
 *
 * ## 状态
 *
 * ```
 * Recording ──收尾──> Transcribing ──终稿──> Completed
 *                              └──失败/超时──> Failed ──重试──> Transcribing
 *                              └──没听到声音──> Empty
 * ```
 */
object VoiceHistory {
    enum class Status(
        val id: String,
    ) {
        /** 正在录。正常结束或被系统打断时，这条会被接上后续状态。 */
        Recording("recording"),

        /** 音频已落盘，正在等识别结果。 */
        Transcribing("transcribing"),

        Completed("completed"),

        /** 识别失败，但音频还在。历史页给「重新转写」。 */
        Failed("failed"),

        /** 连接建不起来，一遍结果都没有。音频还在，值得重试。 */
        NoNetwork("no_network"),

        /** 服务端说这段里没有人说话。 */
        Empty("empty"),
    }

    data class Entry(
        val id: String,
        val text: String,
        val atMillis: Long,
        val durationMs: Long,
        val fileName: String? = null,
        val status: Status = Status.Completed,
        val error: String = "",
        /** 用户手动重试过几次。只做展示与退避，不自动重试。 */
        val retryCount: Int = 0,
    ) {
        /** 有一条能拿来重新识别的本地录音。 */
        val hasAudio: Boolean get() = !fileName.isNullOrEmpty()

        /**
         * 可以点「重新转写」：有音频，且不是正在处理中。
         *
         * [Status.Empty] 排除在外——服务端已经明确说这段里没人说话，
         * 再点几次也一样，按钮一直亮着只会骗用户点。
         *
         * 注意 [Status.Empty] **只能由「服务端说没人说话」产生**。
         * 以前重新识别失败时不管什么原因都写 Empty，一次断网就把整条录音
         * 永久判死（按钮再也不出现），这是真踩过的坑。
         */
        val canRetry: Boolean
            get() = hasAudio &&
                status != Status.Transcribing &&
                status != Status.Recording &&
                status != Status.Completed &&
                status != Status.Empty

        /** 上屏过的正文。失败条目没有文字，不当成一次有效听写。 */
        val hasText: Boolean get() = text.isNotBlank()

        val failed: Boolean
            get() = status == Status.Failed || status == Status.NoNetwork
    }

    private const val FILE_NAME = "voice_history.json"
    private const val MAX_ENTRIES = 500

    /**
     * 一次「重新识别」失败之后，这条记录该记成什么状态。
     *
     * 判据是**这段声音还值不值得再试**，不是这次为什么失败：
     *
     * | 情况 | 状态 | 按钮 |
     * |---|---|---|
     * | 服务端说这段没人说话 | [Status.Empty] | 收起——再点也是白点 |
     * | 网络类 / 连接被掐 / 超时 | [Status.NoNetwork] | 留着——声音在，稍后再试 |
     * | 其余（服务端报错等） | [Status.Failed] | 留着 |
     *
     * **只有 Empty 会让按钮消失。** 语音面板和历史页都走这一个判断，
     * 别再各写一份 if——两处不一致正是之前按钮时而消失的原因。
     *
     * @param retryable 调用方认为「稍后还能再试」。它只影响网络类的归类，
     *                  不会把一个有声音的条目变成 Empty。
     */
    fun statusAfterRetryFailure(
        message: String,
        retryable: Boolean,
    ): Status =
        when {
            // 「没听到人声」是唯一该收掉按钮的理由，而且必须是服务端下的结论。
            isNoSpeech(message) -> Status.Empty
            VoiceFailure.isNetwork(message) -> Status.NoNetwork
            retryable -> Status.NoNetwork
            else -> Status.Failed
        }

    private fun isNoSpeech(message: String): Boolean =
        message.contains("没听到人声") ||
            message.contains("没有人说话") ||
            message.contains("没听到声音")

    private val cache = CopyOnWriteArrayList<Entry>()

    @Volatile
    private var loaded = false

    // ── 读 ──────────────────────────────────────────────────────────────

    @Synchronized
    fun list(ctx: Context): List<Entry> {
        ensureLoaded(ctx)
        return cache.toList()
    }

    @Synchronized
    fun find(
        ctx: Context,
        id: String,
    ): Entry? {
        ensureLoaded(ctx)
        return cache.firstOrNull { it.id == id }
    }

    /** 待补转写的条目（有音频、失败了）。不自动跑，只给界面显示数量。 */
    @Synchronized
    fun retryable(ctx: Context): List<Entry> {
        ensureLoaded(ctx)
        return cache.filter { it.canRetry }
    }

    @Synchronized
    fun size(ctx: Context): Int {
        ensureLoaded(ctx)
        return cache.size
    }

    // ── 写 ──────────────────────────────────────────────────────────────

    /** 录音一开始就建条目，**先落一条记录再谈识别**。 */
    @Synchronized
    fun begin(
        ctx: Context,
        id: String = UUID.randomUUID().toString(),
        atMillis: Long = System.currentTimeMillis(),
    ): String {
        ensureLoaded(ctx)
        cache.add(
            0,
            Entry(
                id = id,
                text = "",
                atMillis = atMillis,
                durationMs = 0L,
                status = Status.Recording,
            ),
        )
        while (cache.size > MAX_ENTRIES) {
            val dropped = cache.removeAt(cache.size - 1)
            dropped.fileName?.let { VoiceAudio.delete(ctx, it) }
        }
        persist(ctx)
        return id
    }

    /** 音频收尾：挂上文件名和时长，进入「等识别」。 */
    @Synchronized
    fun attachAudio(
        ctx: Context,
        id: String,
        artifact: VoiceAudio.Artifact,
    ) {
        update(ctx, id) {
            it.copy(
                fileName = artifact.fileName,
                durationMs = artifact.durationMs,
                status = Status.Transcribing,
            )
        }
    }

    @Synchronized
    fun markTranscribing(
        ctx: Context,
        id: String,
    ) {
        update(ctx, id) { it.copy(status = Status.Transcribing, error = "") }
    }

    @Synchronized
    fun markCompleted(
        ctx: Context,
        id: String,
        text: String,
        durationMs: Long,
    ) {
        update(ctx, id) {
            it.copy(
                text = text,
                durationMs = if (durationMs > 0) durationMs else it.durationMs,
                status = Status.Completed,
                error = "",
            )
        }
    }

    @Synchronized
    fun markFailed(
        ctx: Context,
        id: String,
        status: Status,
        error: String,
    ) {
        update(ctx, id) {
            it.copy(
                status = status,
                error = error.take(200),
                retryCount = it.retryCount + if (status == Status.Failed || status == Status.NoNetwork) 1 else 0,
            )
        }
    }

    /** 点「取消」：这条确实不要了，连音频一起删。 */
    @Synchronized
    fun discard(
        ctx: Context,
        id: String,
    ) {
        ensureLoaded(ctx)
        val entry = cache.firstOrNull { it.id == id } ?: return
        entry.fileName?.let { VoiceAudio.delete(ctx, it) }
        cache.remove(entry)
        persist(ctx)
    }

    @Synchronized
    fun delete(
        ctx: Context,
        entry: Entry,
    ) {
        ensureLoaded(ctx)
        entry.fileName?.let { VoiceAudio.delete(ctx, it) }
        cache.remove(entry)
        persist(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        ensureLoaded(ctx)
        cache.forEach { e -> e.fileName?.let { VoiceAudio.delete(ctx, it) } }
        cache.clear()
        persist(ctx)
    }

    /**
     * 启动时接手上次没走完的录音（`.part` 已由 [VoiceAudio.recoverInterrupted] 修好）。
     *
     * 库里已经有同名文件的条目就不重复建；剩下那些说明当时连记录都还没来得及写，
     * 补一条 [Status.Failed] 记录，让它出现在历史页里。
     */
    @Synchronized
    fun adoptOrphans(
        ctx: Context,
        recovered: List<String>,
    ) {
        if (recovered.isEmpty()) return
        ensureLoaded(ctx)
        val known = cache.mapNotNull { it.fileName }.toSet()
        var added = false
        for (name in recovered) {
            if (name in known) continue
            val artifact = VoiceAudio.artifactFor(ctx, name) ?: continue
            cache.add(
                0,
                Entry(
                    id = UUID.randomUUID().toString(),
                    text = "",
                    atMillis = System.currentTimeMillis(),
                    durationMs = artifact.durationMs,
                    fileName = name,
                    status = Status.Failed,
                    error = "上次听写被中断",
                ),
            )
            added = true
        }
        if (added) persist(ctx)
    }

    private fun update(
        ctx: Context,
        id: String,
        block: (Entry) -> Entry,
    ) {
        ensureLoaded(ctx)
        val index = cache.indexOfFirst { it.id == id }
        if (index < 0) return
        cache[index] = block(cache[index])
        persist(ctx)
    }

    // ── 落盘 ────────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) return
        var repaired = false
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val stored = Status.entries.firstOrNull { it.id == o.optString("status") } ?: Status.Completed
                val error = o.optString("error")
                // 修一批被误判成 Empty 的老记录。
                //
                // 以前「重新识别失败」不管什么原因都写 Empty，而 Empty 会让重试按钮
                // 永久消失。这里在读取时把「其实是网络问题」的那些改回来：
                // 音频还在、错误是网络类 → NoNetwork，按钮就重新出现。
                // 只动确实能判定的（错误串明确是网络类），其余原样保留。
                val status =
                    if (stored == Status.Empty && error.isNotBlank() && VoiceFailure.isNetwork(error)) {
                        repaired = true
                        Status.NoNetwork
                    } else {
                        stored
                    }
                cache.add(
                    Entry(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        text = o.optString("text"),
                        atMillis = o.optLong("at"),
                        durationMs = o.optLong("dur"),
                        fileName = o.optString("audio").takeIf { it.isNotBlank() },
                        // 老记录没有这两个字段：那时只有成功上屏的才会进历史。
                        status = status,
                        error = error,
                        retryCount = o.optInt("retry"),
                    ),
                )
            }
        }.onFailure { Timber.w(it, "读取听写历史失败") }
        if (repaired) {
            Timber.i("修正了被误判为「没听到人声」的历史记录")
            persist(ctx)
        }
    }

    private fun persist(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            cache.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("text", it.text)
                        .put("at", it.atMillis)
                        .put("dur", it.durationMs)
                        .put("audio", it.fileName.orEmpty())
                        .put("status", it.status.id)
                        .put("error", it.error)
                        .put("retry", it.retryCount),
                )
            }
            // 先写临时文件再改名：写到一半被杀不会把整份历史写成半截 JSON。
            val target = file(ctx)
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(arr.toString())
            if (!temp.renameTo(target)) {
                target.writeText(arr.toString())
                temp.delete()
            }
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
        /** 有录音、但还没能补出文字的条数。 */
        val pendingRetry: Int = 0,
    )

    /** 打字速度按 40 字/分钟估，用来算「节省的时间」。 */
    private const val TYPING_WPM = 40

    fun compute(entries: List<VoiceHistory.Entry>): Snapshot {
        val pending = entries.count { it.canRetry }
        if (entries.isEmpty()) return Snapshot(0, 0L, 0, 0L, 0, 0, 0, 0)

        // 失败条目没有文字，但这段声音确实花掉了时间：字数按 0 算，时长照算。
        val written = entries.filter { it.hasText }
        val words = written.sumOf { it.text.length }
        val totalMs = entries.sumOf { it.durationMs }
        val minutes = totalMs / 60000.0
        val avg = if (minutes > 0.05) (words / minutes).toInt() else 0

        val typingMs = words.toDouble() / TYPING_WPM * 60000.0
        val saved = (typingMs - totalMs).toLong().coerceAtLeast(0L)

        val days = written.map { dayKey(it.atMillis) }.toSortedSet()
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
            pendingRetry = pending,
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
