/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.WeakHashMap

/**
 * 词库自动同步：本地改完先落盘，随后拉云端、三方合并、必要时推送。
 *
 * 触发点刻意选在“用户正在管理词库”和“语音面板刚打开”这两类时刻；
 * 不在每次键盘弹出时都联网，避免输入法频繁唤醒时做无谓请求。
 */
object GlossarySyncManager {
    data class Report(
        val hotwordCount: Int,
        val replacementCount: Int,
        val pulled: Boolean,
        val pushed: Boolean,
        val conflicts: Int,
        val hotwordsChanged: Boolean,
        val syncedAt: Long,
        val error: String? = null,
        val skipped: Boolean = false,
    )

    data class Status(
        val configured: Boolean,
        val autoSync: Boolean,
        val lastSyncedAt: Long,
        val localChangedAt: Long,
        val lastError: String?,
        val conflicts: Int,
    )

    fun interface Listener {
        fun onGlossarySync(report: Report)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val listeners = Collections.newSetFromMap(WeakHashMap<Listener, Boolean>())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastAttemptAt = 0L

    private var worker: Job? = null
    @Volatile
    private var pending = false

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** 本地增删改之后调用；短延迟合并连续编辑，避免一次改三条就推三次。 */
    fun onLocalChange(ctx: Context) {
        if (!VoicePrefs.glossaryAutoSync(ctx)) return
        schedule(ctx.applicationContext, delayMs = 800)
    }

    /** 主界面回到前台：静默拉一次，远端没变时几乎无感。 */
    fun onForeground(ctx: Context) {
        if (!VoicePrefs.glossaryAutoSync(ctx)) return
        schedule(ctx.applicationContext, delayMs = 0)
    }

    /** 语音面板展开：节流后检查一次，下一轮听写就能用上另一端的改动。 */
    fun onVoicePanelShown(ctx: Context) {
        if (!VoicePrefs.glossaryAutoSync(ctx)) return
        if (System.currentTimeMillis() - lastAttemptAt < IME_SYNC_INTERVAL_MS) return
        schedule(ctx.applicationContext, delayMs = 0)
    }

    fun status(ctx: Context): Status =
        Status(
            configured = VoicePrefs.tosAccessKey(ctx).isNotBlank() && VoicePrefs.tosSecretKey(ctx).isNotBlank(),
            autoSync = VoicePrefs.glossaryAutoSync(ctx),
            lastSyncedAt = VoicePrefs.glossaryLastSyncedAt(ctx),
            localChangedAt = VoicePrefs.glossaryLocalChangedAt(ctx),
            lastError = VoicePrefs.glossaryLastError(ctx),
            conflicts = VoicePrefs.glossaryLastConflicts(ctx),
        )

    fun summary(ctx: Context): String {
        val status = status(ctx)
        if (!status.configured) return "未配置 TOS 凭据"
        val error = status.lastError
        if (!error.isNullOrBlank()) return "同步失败：${error.take(48)}"
        if (status.localChangedAt > status.lastSyncedAt) return "有未同步改动"
        if (status.lastSyncedAt <= 0L) return "等待首次同步"
        val base = "已同步 · ${relativeTime(status.lastSyncedAt)}"
        return if (status.conflicts > 0) "$base · 合并 ${status.conflicts} 条冲突" else base
    }

    suspend fun sync(
        ctx: Context,
        manual: Boolean = false,
    ): Report = withContext(Dispatchers.IO) { safeSync(ctx.applicationContext, manual = manual) }

    suspend fun forcePull(ctx: Context): Report = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        syncMutex.withLock {
            val config = configOrNull(app) ?: return@withLock skipped(app, "还没有填写 TOS AccessKey / SecretKey")
            try {
                val remote = GlossarySyncClient.pull(config)
                val snapshot = VoiceDictionary.cloudSnapshot(remote.glossary)
                    ?: return@withLock fail(app, "云端词库格式不是 ${VoiceDictionary.CLOUD_FORMAT}")
                val before = VoiceDictionary.localSnapshot(app)
                VoiceDictionary.replaceAll(app, snapshot.hotwords, snapshot.rules)
                finish(app, remote.glossary, conflicts = 0)
                val changed = before.hotwords != snapshot.hotwords
                if (changed) syncHotwordTable(app)
                report(app, pulled = true, pushed = false, conflicts = 0, hotwordsChanged = changed).also {
                    notify(it)
                }
            } catch (e: Exception) {
                fail(app, e.message ?: e.toString()).also { notify(it) }
            }
        }
    }

    suspend fun forcePush(ctx: Context): Report = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        syncMutex.withLock {
            val config = configOrNull(app) ?: return@withLock skipped(app, "还没有填写 TOS AccessKey / SecretKey")
            repeat(MAX_ATTEMPTS) {
                val remote = GlossarySyncClient.pullOrNull(config)
                val local = VoiceDictionary.localSnapshot(app)
                val body = VoiceDictionary.toCloudGlossary(local.hotwords, local.rules, remote?.glossary, deviceName())
                try {
                    GlossarySyncClient.push(config, body, remote?.etag)
                    finish(app, body, conflicts = 0)
                    syncHotwordTable(app)
                    return@withLock report(
                        app,
                        pulled = false,
                        pushed = true,
                        conflicts = 0,
                        hotwordsChanged = true,
                    ).also { notify(it) }
                } catch (e: GlossarySyncClient.ConflictException) {
                    Timber.i("强制推送遇到 ETag 冲突，重拉重试")
                } catch (e: Exception) {
                    return@withLock fail(app, e.message ?: e.toString()).also { notify(it) }
                }
            }
            fail(app, "推送重试次数过多").also { notify(it) }
        }
    }

    private fun schedule(
        ctx: Context,
        delayMs: Long,
    ) {
        pending = true
        if (worker?.isActive == true) return
        worker =
            scope.launch {
                delay(delayMs)
                while (pending) {
                    pending = false
                    val report = safeSync(ctx, manual = false)
                    notify(report)
                }
            }
    }

    private suspend fun safeSync(
        ctx: Context,
        manual: Boolean,
    ): Report = runCatching { runSync(ctx, manual) }
        .getOrElse { fail(ctx, it.message ?: it.toString()) }

    private suspend fun runSync(
        ctx: Context,
        manual: Boolean,
    ): Report = syncMutex.withLock {
        lastAttemptAt = System.currentTimeMillis()
        val config = configOrNull(ctx) ?: return@withLock skipped(ctx, "还没有填写 TOS AccessKey / SecretKey")

        var lastError: String? = null
        repeat(MAX_ATTEMPTS) {
            val revisionBefore = VoicePrefs.glossaryLocalRevision(ctx)
            val local = VoiceDictionary.localSnapshot(ctx)
            if (VoicePrefs.glossaryLocalRevision(ctx) != revisionBefore) return@repeat

            val remote =
                try {
                    GlossarySyncClient.pullOrNull(config)
                } catch (e: Exception) {
                    return@withLock fail(ctx, e.message ?: e.toString())
                }

            if (remote == null) {
                return@withLock createRemote(ctx, config, local)
            }

            val remoteSnapshot = VoiceDictionary.cloudSnapshot(remote.glossary)
                ?: return@withLock fail(ctx, "云端词库格式不是 ${VoiceDictionary.CLOUD_FORMAT}")
            val baseJson = VoicePrefs.glossaryBase(ctx)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val base = baseJson?.let { VoiceDictionary.cloudSnapshot(it) }
            val merge =
                GlossaryMerge.merge(
                    base = base,
                    local = local,
                    remote = remoteSnapshot,
                    localChangedAt = VoicePrefs.glossaryLocalChangedAt(ctx),
                    remoteUpdatedAt = parseIso(remote.glossary.optString("updatedAt")),
                )

            if (VoicePrefs.glossaryLocalRevision(ctx) != revisionBefore) return@repeat
            if (merge.changedLocal) {
                VoiceDictionary.replaceAll(ctx, merge.snapshot.hotwords, merge.snapshot.rules)
            }

            val needsPush = !GlossaryMerge.same(merge.snapshot, remoteSnapshot)
            if (!needsPush) {
                finish(ctx, remote.glossary, merge.conflicts)
                val changed = !sameHotwords(local.hotwords, merge.snapshot.hotwords)
                if (changed) syncHotwordTable(ctx)
                return@withLock report(
                    ctx,
                    pulled = !GlossaryMerge.same(local, merge.snapshot),
                    pushed = false,
                    conflicts = merge.conflicts,
                    hotwordsChanged = changed,
                )
            }

            val body =
                VoiceDictionary.toCloudGlossary(
                    merge.snapshot.hotwords,
                    merge.snapshot.rules,
                    remote.glossary,
                    deviceName(),
                )
            try {
                GlossarySyncClient.push(config, body, remote.etag)
                finish(ctx, body, merge.conflicts)
                val changed = !sameHotwords(local.hotwords, merge.snapshot.hotwords)
                if (changed) syncHotwordTable(ctx)
                return@withLock report(
                    ctx,
                    pulled = !GlossaryMerge.same(local, merge.snapshot),
                    pushed = true,
                    conflicts = merge.conflicts,
                    hotwordsChanged = changed,
                )
            } catch (e: GlossarySyncClient.ConflictException) {
                lastError = "云端刚被另一台设备更新，正在重试"
                Timber.i("词库同步遇到 ETag 冲突，重拉重试")
            } catch (e: Exception) {
                return@withLock fail(ctx, e.message ?: e.toString())
            }
        }
        fail(ctx, lastError ?: "同步重试次数过多")
    }

    private suspend fun createRemote(
        ctx: Context,
        config: GlossarySyncClient.Config,
        local: VoiceDictionary.Snapshot,
    ): Report {
        return try {
            val body = VoiceDictionary.toCloudGlossary(local.hotwords, local.rules, null, deviceName())
            GlossarySyncClient.push(config, body, null)
            finish(ctx, body, conflicts = 0)
            syncHotwordTable(ctx)
            report(ctx, pulled = false, pushed = true, conflicts = 0, hotwordsChanged = true)
        } catch (e: Exception) {
            fail(ctx, e.message ?: e.toString())
        }
    }

    private fun finish(
        ctx: Context,
        body: JSONObject,
        conflicts: Int,
    ) {
        VoicePrefs.setGlossaryBase(ctx, body.toString())
        VoicePrefs.setGlossaryLastSyncedAt(ctx, System.currentTimeMillis())
        VoicePrefs.clearGlossaryLocalChange(ctx)
        VoicePrefs.setGlossaryLastError(ctx, null)
        VoicePrefs.setGlossaryLastConflicts(ctx, conflicts)
    }

    private fun fail(
        ctx: Context,
        message: String,
    ): Report {
        VoicePrefs.setGlossaryLastError(ctx, message)
        Timber.w("词库同步失败：%s", message)
        return report(ctx, pulled = false, pushed = false, conflicts = 0, hotwordsChanged = false, error = message)
    }

    private fun skipped(
        ctx: Context,
        message: String,
    ): Report = report(ctx, pulled = false, pushed = false, conflicts = 0, hotwordsChanged = false, skipped = true)

    private fun report(
        ctx: Context,
        pulled: Boolean,
        pushed: Boolean,
        conflicts: Int,
        hotwordsChanged: Boolean,
        error: String? = null,
        skipped: Boolean = false,
    ): Report {
        val local = VoiceDictionary.localSnapshot(ctx)
        return Report(
            hotwordCount = local.hotwords.size,
            replacementCount = local.rules.size,
            pulled = pulled,
            pushed = pushed,
            conflicts = conflicts,
            hotwordsChanged = hotwordsChanged,
            syncedAt = System.currentTimeMillis(),
            error = error,
            skipped = skipped,
        )
    }

    private fun notify(report: Report) {
        mainHandler.post {
            listeners.toList().forEach { runCatching { it.onGlossarySync(report) } }
        }
    }

    private fun configOrNull(ctx: Context): GlossarySyncClient.Config? {
        val ak = VoicePrefs.tosAccessKey(ctx)
        val sk = VoicePrefs.tosSecretKey(ctx)
        if (ak.isBlank() || sk.isBlank()) return null
        return GlossarySyncClient.Config(
            accessKeyId = ak,
            secretAccessKey = sk,
            bucket = VoicePrefs.tosBucket(ctx),
            key = "glossary.json",
            region = VoicePrefs.tosRegion(ctx),
            endpoint = VoicePrefs.tosEndpoint(ctx),
        )
    }

    private suspend fun syncHotwordTable(ctx: Context) {
        val result = HotwordTableSync.sync(ctx)
        result.onFailure { Timber.w(it, "热词表同步失败") }
    }

    private fun sameHotwords(
        a: List<String>,
        b: List<String>,
    ): Boolean = HotwordSanitizer.sanitize(a) == HotwordSanitizer.sanitize(b)

    private fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    private fun parseIso(raw: String): Long {
        if (raw.isBlank()) return 0L
        val patterns = arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
        for (pattern in patterns) {
            val parsed =
                runCatching {
                    SimpleDateFormat(pattern, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                        isLenient = false
                    }.parse(raw)
                }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun relativeTime(timestamp: Long): String {
        val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            else -> "${diff / 86_400_000L} 天前"
        }
    }

    private const val MAX_ATTEMPTS = 3
    private const val IME_SYNC_INTERVAL_MS = 30 * 1000L
}
