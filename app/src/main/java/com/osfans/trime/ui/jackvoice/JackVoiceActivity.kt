/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.jackvoice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.AppearanceManager
import com.osfans.trime.ime.voice.GlossarySyncClient
import com.osfans.trime.ime.voice.GlossarySyncManager
import com.osfans.trime.ime.voice.HotwordTableSync
import com.osfans.trime.ime.voice.VoiceDictionary
import com.osfans.trime.ime.voice.VoiceHistory
import com.osfans.trime.ime.voice.VoicePrefs
import com.osfans.trime.ime.voice.VoiceStats
import com.osfans.trime.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * JackVoice 主界面。对标 Typeless 的四个 Tab：首页 / 历史记录 / 词典 / 设置。
 *
 * 全部用原生 View 手搭——Trime 没有引 Material 组件库，为这一个界面也不值得引。
 * 配色、圆角、留白都按 Typeless 的量级来。
 *
 * Trime 自己的 Rime 设置保留在「设置 → 输入法高级设置」里，没有被替换掉。
 */
class JackVoiceActivity :
    AppCompatActivity(),
    GlossarySyncManager.Listener {
    private enum class Tab(
        val label: String,
        val icon: Int,
    ) {
        Home("首页", R.drawable.ic_jv_home),
        History("历史记录", R.drawable.ic_baseline_list_alt_24),
        Dictionary("词典", R.drawable.ic_baseline_book_24),
        Settings("设置", R.drawable.ic_baseline_settings_24),
    }

    private lateinit var content: FrameLayout
    private lateinit var barItems: List<Pair<LinearLayout, Pair<android.widget.ImageView, TextView>>>

    private enum class DictionaryTab { Hotwords, Replacements }

    private var dictionaryTab = DictionaryTab.Hotwords
    private var currentTab = Tab.Home
    private var syncTicker: Job? = null

    private companion object {
        const val STATE_TAB = "jackvoice_tab"
        const val STATE_DICTIONARY_TAB = "jackvoice_dictionary_tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 外观模式可能刚在设置里改过；系统栏图标要跟当前底色反着来，
        // 否则浅色下是白字、深色下是黑字，顶部那一条会看不见。
        applySystemBarAppearance()

        currentTab =
            savedInstanceState
                ?.getInt(STATE_TAB, Tab.Home.ordinal)
                ?.let { Tab.entries.getOrNull(it) }
                ?: Tab.Home
        dictionaryTab =
            savedInstanceState
                ?.getInt(STATE_DICTIONARY_TAB, DictionaryTab.Hotwords.ordinal)
                ?.let { DictionaryTab.entries.getOrNull(it) }
                ?: DictionaryTab.Hotwords

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Jv.BG)
            }
        content = FrameLayout(this)
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val bar = buildBar()
        root.addView(
            bar.first,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Jv.dp(this, 62)),
        )
        barItems = bar.second

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(top = bars.top)
            bar.first.updatePadding(bottom = bars.bottom)
            insets
        }

        setContentView(root)
        GlossarySyncManager.addListener(this)
        select(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_TAB, currentTab.ordinal)
        outState.putInt(STATE_DICTIONARY_TAB, dictionaryTab.ordinal)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        GlossarySyncManager.onForeground(this)
        syncTicker?.cancel()
        syncTicker = lifecycleScope.launch {
            while (true) {
                delay(30_000)
                GlossarySyncManager.onForeground(this@JackVoiceActivity)
            }
        }
    }

    override fun onPause() {
        syncTicker?.cancel()
        syncTicker = null
        super.onPause()
    }

    override fun onDestroy() {
        GlossarySyncManager.removeListener(this)
        super.onDestroy()
    }

    private fun applySystemBarAppearance() {
        val light = !AppearanceManager.isNight()
        WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onGlossarySync(report: GlossarySyncManager.Report) {
        if (currentTab == Tab.Dictionary || currentTab == Tab.Settings) {
            select(currentTab)
        }
    }

    // ── 底部导航 ────────────────────────────────────────────────────────

    private fun buildBar(): Pair<LinearLayout, List<Pair<LinearLayout, Pair<android.widget.ImageView, TextView>>>> {
        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Jv.BG)
            }
        val items = mutableListOf<Pair<LinearLayout, Pair<android.widget.ImageView, TextView>>>()
        Tab.entries.forEach { tab ->
            val icon = Jv.icon(this, tab.icon, Jv.TEXT2)
            val label = Jv.text(this, tab.label, 11f, Jv.TEXT2)
            val cell =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { select(tab) }
                    addView(icon, LinearLayout.LayoutParams(Jv.dp(this@JackVoiceActivity, 22), Jv.dp(this@JackVoiceActivity, 22)))
                    addView(
                        label,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = Jv.dp(this@JackVoiceActivity, 4) },
                    )
                }
            bar.addView(
                cell,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            items.add(cell to (icon to label))
        }
        return bar to items
    }

    private fun select(tab: Tab) {
        currentTab = tab
        Tab.entries.forEachIndexed { i, t ->
            val (icon, label) = barItems[i].second
            val on = t == tab
            icon.setColorFilter(if (on) Jv.TEXT else Jv.TEXT2)
            label.setTextColor(if (on) Jv.TEXT else Jv.TEXT2)
            barItems[i].first.isSelected = on
        }
        val page =
            when (tab) {
                Tab.Home -> homePage()
                Tab.History -> historyPage()
                Tab.Dictionary -> dictionaryPage()
                Tab.Settings -> settingsPage()
            }
        content.removeAllViews()
        content.addView(
            page,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    // ── 首页 ────────────────────────────────────────────────────────────

    private fun homePage(): View {
        val col = Jv.column(this)

        val brand =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 6))
            }
        brand.addView(
            Jv.text(this, "JackVoice", 26f, Jv.TEXT, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        col.addView(brand)

        col.addView(heroCard())

        val stats = VoiceStats.compute(VoiceHistory.list(this))
        val words = Jv.humanCount(stats.words)
        val saved = Jv.humanDuration(stats.savedMs)
        val total = Jv.humanDuration(stats.totalMs)

        val statsCard = Jv.card(this)
        val row1 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        row1.addView(Jv.statCell(this, words.first, words.second, "听写的字数"), weight())
        row1.addView(Jv.statCell(this, saved.first, saved.second, "节省的时间"), weight())
        statsCard.addView(row1)
        val row2 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        row2.addView(Jv.statCell(this, "${stats.avgWordsPerMinute}", "字/分", "平均听写速度"), weight())
        row2.addView(Jv.statCell(this, total.first, total.second, "总听写时间"), weight())
        statsCard.addView(row2)
        col.addView(statsCard)

        val streakCard = Jv.card(this)
        val streakRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        streakRow.addView(Jv.statCell(this, "${stats.activeDays}", "天", "活跃天数"), weight())
        streakRow.addView(Jv.statCell(this, "${stats.streakDays}", "天", "当前连续"), weight())
        streakRow.addView(Jv.statCell(this, "${stats.longestStreakDays}", "天", "最长连续"), weight())
        streakCard.addView(streakRow)
        col.addView(streakCard)

        return Jv.page(col)
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** 首页顶部那张「键盘状态」卡，替代 Typeless 的桌面端推广位。 */
    private fun heroCard(): View {
        val ready = isImeEnabled() && isImeDefault() && hasMic()
        val card = Jv.card(this, Jv.CARD, 20)
        card.addView(
            Jv.text(
                this,
                if (ready) "跨应用语音听写已就绪" else "还差几步就能用了",
                19f,
                Jv.TEXT,
                bold = true,
            ).apply { setPadding(Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 18), 0) },
        )
        card.addView(
            Jv.text(this, statusLine(), 13f, Jv.TEXT2).apply {
                setPadding(Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 8), Jv.dp(this@JackVoiceActivity, 18), 0)
            },
        )
        card.addView(
            Jv.primaryButton(
                this,
                if (ready) "去系统设置里切换输入法" else "一键检查并开启",
            ) {
                if (ready) {
                    runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                } else {
                    runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
                }
            },
        )
        card.setPadding(0, 0, 0, Jv.dp(this, 18))
        return card
    }

    private fun statusLine(): String {
        val missing = mutableListOf<String>()
        if (!isImeEnabled()) missing += "启用输入法"
        if (!isImeDefault()) missing += "设为默认输入法"
        if (!hasMic()) missing += "麦克风权限"
        if (VoicePrefs.apiKey(this).isBlank()) missing += "填 API Key"
        return if (missing.isEmpty()) {
            "打字用「拼」「EN」，说话点中间的大圆，文字会自己落进当前输入框。"
        } else {
            "还缺：" + missing.joinToString("、")
        }
    }

    /**
     * 注意：targetSdk 34 起读 `Settings.Secure.ENABLED_INPUT_METHODS` 会抛 SecurityException，
     * 必须走 InputMethodManager 的正式 API。
     */
    private fun isImeEnabled(): Boolean = runCatching {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { it.packageName == packageName } == true
    }.getOrDefault(false)

    private fun isImeDefault(): Boolean = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
    }.getOrDefault(false)

    private fun hasMic(): Boolean = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── 历史记录 ────────────────────────────────────────────────────────

    private fun historyPage(): View {
        val col = Jv.column(this)
        col.addView(Jv.pageTitle(this, "历史记录"))

        val info = Jv.card(this)
        info.addView(Jv.row(this, "保留历史", "永久（本地，不上云）"))
        info.addView(Jv.divider(this))
        info.addView(
            Jv.row(
                this,
                "您的数据保持私密",
                "听写记录只存在这台设备上，不会上传。",
            ),
        )
        col.addView(info)

        val entries = VoiceHistory.list(this)
        if (entries.isEmpty()) {
            col.addView(Jv.sectionLabel(this, "还没有记录"))
            val empty = Jv.card(this)
            empty.addView(
                Jv.text(this, "说一句试试——文字上屏后就会出现在这里。", 14f, Jv.TEXT2).apply {
                    setPadding(Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 18))
                },
            )
            col.addView(empty)
            return Jv.page(col)
        }

        // 按日期分组
        val dayFmt = SimpleDateFormat("yyyy 年 M 月 d 日", Locale.CHINA)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        var lastDay = ""
        entries.forEach { e ->
            val day = dayFmt.format(Date(e.atMillis))
            if (day != lastDay) {
                lastDay = day
                col.addView(Jv.sectionLabel(this, day))
            } else {
                col.addView(Jv.sectionLabel(this, ""))
            }
            val card = Jv.card(this, Jv.CARD, 16)
            card.addView(
                Jv.text(this, timeFmt.format(Date(e.atMillis)), 13f, Jv.TEXT2).apply {
                    setPadding(Jv.dp(this@JackVoiceActivity, 16), Jv.dp(this@JackVoiceActivity, 14), Jv.dp(this@JackVoiceActivity, 16), 0)
                },
            )
            card.addView(
                Jv.text(this, e.text, 16f, Jv.TEXT).apply {
                    setPadding(Jv.dp(this@JackVoiceActivity, 16), Jv.dp(this@JackVoiceActivity, 8), Jv.dp(this@JackVoiceActivity, 16), 0)
                    setLineSpacing(Jv.dp(this@JackVoiceActivity, 5).toFloat(), 1f)
                },
            )
            val actions =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(Jv.dp(this@JackVoiceActivity, 16), Jv.dp(this@JackVoiceActivity, 10), Jv.dp(this@JackVoiceActivity, 16), Jv.dp(this@JackVoiceActivity, 14))
                }
            actions.addView(
                iconText("复制") {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("JackVoice", e.text))
                    toast("已复制")
                },
            )
            actions.addView(
                iconText("删除") {
                    VoiceHistory.delete(this, e)
                    select(Tab.History)
                },
            )
            card.addView(actions)
            col.addView(card)
        }
        return Jv.page(col)
    }

    private fun iconText(
        label: String,
        color: Int = Jv.TEXT2,
        onClick: () -> Unit,
    ): TextView = Jv.text(this, label, 14f, color).apply {
        setPadding(Jv.dp(this@JackVoiceActivity, 10), Jv.dp(this@JackVoiceActivity, 6), Jv.dp(this@JackVoiceActivity, 10), Jv.dp(this@JackVoiceActivity, 6))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /**
     * 热词改动后同步整张云端热词表；没配置表 ID 时只保存在本地。
     * 自动同步失败不拦本地修改，只提示一次。
     */
    private fun syncHotwords(manual: Boolean = false) {
        if (manual) toast("正在同步热词表…")
        lifecycleScope.launch {
            val result = HotwordTableSync.sync(this@JackVoiceActivity)
            result
                .onSuccess { outcome ->
                    when (outcome) {
                        is HotwordTableSync.Outcome.Synced -> {
                            Timber.i("热词表同步成功：${outcome.count} 条")
                            if (manual) toast("已同步 ${outcome.count} 条热词到云端")
                        }

                        is HotwordTableSync.Outcome.Skipped -> if (manual) toast(outcome.reason)
                    }
                }.onFailure {
                    Timber.e(it, "热词表同步失败")
                    toast("热词已保存在本地；云端同步失败：${it.message ?: "未知错误"}")
                }
        }
    }

    private fun tosConfig(): GlossarySyncClient.Config? {
        val ak = VoicePrefs.tosAccessKey(this)
        val sk = VoicePrefs.tosSecretKey(this)
        if (ak.isBlank() || sk.isBlank()) {
            toast("先填写 TOS AccessKey 和 SecretKey")
            return null
        }
        return GlossarySyncClient.Config(
            accessKeyId = ak,
            secretAccessKey = sk,
            bucket = VoicePrefs.tosBucket(this),
            key = "glossary.json",
            region = VoicePrefs.tosRegion(this),
            endpoint = VoicePrefs.tosEndpoint(this),
        )
    }

    private fun cloudCounts(glossary: org.json.JSONObject): Pair<Int, Int> {
        val hotwords =
            glossary.optJSONArray("hotwords")?.let { arr ->
                (0 until arr.length()).count { i ->
                    val item = arr.optJSONObject(i) ?: return@count false
                    item.optBoolean("isEnabled", true) && item.optString("term").isNotBlank()
                }
            } ?: 0
        val rules =
            glossary.optJSONArray("replacements")?.let { arr ->
                (0 until arr.length()).count { i ->
                    val item = arr.optJSONObject(i) ?: return@count false
                    item.optBoolean("isEnabled", true) &&
                        item.optString("from").isNotBlank() &&
                        item.optString("to").isNotBlank()
                }
            } ?: 0
        return hotwords to rules
    }

    private fun pullGlossaryFromTos() {
        val config = tosConfig() ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { GlossarySyncClient.pull(config) } }
            result
                .onSuccess { remote ->
                    val (hotwords, rules) = cloudCounts(remote.glossary)
                    AlertDialog
                        .Builder(this@JackVoiceActivity)
                        .setTitle("从 TOS 拉取词库？")
                        .setMessage("会用云端词库覆盖本机：$hotwords 条热词、$rules 条替换词。")
                        .setPositiveButton("覆盖") { _, _ ->
                            lifecycleScope.launch {
                                val report = GlossarySyncManager.forcePull(this@JackVoiceActivity)
                                if (report.error != null) {
                                    showSyncError("TOS 拉取失败", report.error)
                                } else {
                                    toast("已拉取 ${report.hotwordCount} 条热词、${report.replacementCount} 条替换词")
                                    select(Tab.Settings)
                                }
                            }
                        }.setNegativeButton(android.R.string.cancel, null)
                        .show()
                }.onFailure {
                    Timber.e(it, "TOS 拉取失败")
                    showSyncError("TOS 拉取失败", it.message ?: it.toString())
                }
        }
    }

    private fun pushGlossaryToTos() {
        tosConfig() ?: return
        val hotwords = VoiceDictionary.hotwords(this).size
        val rules = VoiceDictionary.rules(this).size
        AlertDialog
            .Builder(this)
            .setTitle("推送到 TOS？")
            .setMessage("会用本机 $hotwords 条热词、$rules 条替换词覆盖云端 glossary.json。")
            .setPositiveButton("推送") { _, _ ->
                lifecycleScope.launch {
                    val report = GlossarySyncManager.forcePush(this@JackVoiceActivity)
                    if (report.error != null) {
                        showSyncError("TOS 推送失败", report.error)
                    } else {
                        toast("已推送 ${report.hotwordCount} 条热词、${report.replacementCount} 条替换词")
                        select(Tab.Settings)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun syncGlossaryNow() {
        lifecycleScope.launch {
            toast("正在同步词库…")
            val report = GlossarySyncManager.sync(this@JackVoiceActivity, manual = true)
            if (report.error != null) {
                showSyncError("词库同步失败", report.error)
            } else if (report.skipped) {
                toast("先填写 TOS AccessKey 和 SecretKey")
            } else {
                val conflictNote = if (report.conflicts > 0) "，合并了 ${report.conflicts} 条冲突" else ""
                toast("已同步 ${report.hotwordCount} 条热词、${report.replacementCount} 条替换词$conflictNote")
                select(currentTab)
            }
        }
    }

    private fun showSyncError(
        title: String,
        message: String,
    ) {
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("好", null)
            .show()
    }

    // ── 词典 ────────────────────────────────────────────────────────────

    private fun dictionaryPage(): View {
        val col = Jv.column(this)
        col.addView(Jv.pageTitle(this, "词典"))

        val hotwords = VoiceDictionary.hotwords(this)
        val rules = VoiceDictionary.rules(this)

        col.addView(
            Jv.text(
                this,
                "热词负责听准，替换词只在终稿后本地生效。当前 ${hotwords.size} 条热词、${rules.size} 条替换规则。",
                13f,
                Jv.TEXT2,
            ).apply {
                setPadding(Jv.dp(this@JackVoiceActivity, 20), 0, Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 12))
            },
        )

        col.addView(
            Jv.text(
                this,
                "云同步：${GlossarySyncManager.summary(this)}",
                12f,
                Jv.TEXT2,
            ).apply {
                setPadding(Jv.dp(this@JackVoiceActivity, 20), 0, Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 10))
            },
        )

        val seg =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Jv.round(Jv.CARD, Jv.dp(this@JackVoiceActivity, 12))
                setPadding(Jv.dp(this@JackVoiceActivity, 4), Jv.dp(this@JackVoiceActivity, 4), Jv.dp(this@JackVoiceActivity, 4), Jv.dp(this@JackVoiceActivity, 4))
            }
        seg.addView(
            segment("热词 ${hotwords.size}", dictionaryTab == DictionaryTab.Hotwords) {
                dictionaryTab = DictionaryTab.Hotwords
                select(Tab.Dictionary)
            },
            weight(),
        )
        seg.addView(
            segment("替换词 ${rules.size}", dictionaryTab == DictionaryTab.Replacements) {
                dictionaryTab = DictionaryTab.Replacements
                select(Tab.Dictionary)
            },
            weight(),
        )
        col.addView(
            seg,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = Jv.dp(this@JackVoiceActivity, 16)
                marginEnd = Jv.dp(this@JackVoiceActivity, 16)
            },
        )

        when (dictionaryTab) {
            DictionaryTab.Hotwords -> {
                if (hotwords.isEmpty()) {
                    col.addView(dictionaryEmpty("还没有热词"))
                } else {
                    val card = Jv.card(this)
                    hotwords.forEachIndexed { i, w ->
                        card.addView(hotwordRow(w))
                        if (i != hotwords.lastIndex) card.addView(Jv.divider(this))
                    }
                    col.addView(card)
                }
                col.addView(Jv.primaryButton(this, "添加热词") { addWordDialog() })
            }

            DictionaryTab.Replacements -> {
                col.addView(
                    Jv.text(
                        this,
                        "英文忽略大小写、按整词匹配；空格和拼写要一致，同一位置长规则优先。",
                        12f,
                        Jv.TEXT2,
                    ).apply {
                        setPadding(Jv.dp(this@JackVoiceActivity, 24), 0, Jv.dp(this@JackVoiceActivity, 24), Jv.dp(this@JackVoiceActivity, 8))
                    },
                )
                if (rules.isEmpty()) {
                    col.addView(dictionaryEmpty("还没有替换词"))
                } else {
                    val card = Jv.card(this)
                    rules.forEachIndexed { i, rule ->
                        card.addView(ruleRow(rule))
                        if (i != rules.lastIndex) card.addView(Jv.divider(this))
                    }
                    col.addView(card)
                }
                col.addView(Jv.primaryButton(this, "添加替换词") { editRuleDialog(null) })
            }
        }
        return Jv.page(col)
    }

    private fun dictionaryEmpty(text: String): TextView = Jv.text(this, text, 14f, Jv.TEXT2).apply {
        setPadding(
            Jv.dp(this@JackVoiceActivity, 20),
            Jv.dp(this@JackVoiceActivity, 10),
            Jv.dp(this@JackVoiceActivity, 20),
            Jv.dp(this@JackVoiceActivity, 10),
        )
    }

    private fun segment(
        label: String,
        active: Boolean,
        onClick: () -> Unit,
    ): TextView = Jv.text(this, label, 14f, if (active) Jv.TEXT else Jv.TEXT2, bold = active).apply {
        gravity = Gravity.CENTER
        setPadding(0, Jv.dp(this@JackVoiceActivity, 8), 0, Jv.dp(this@JackVoiceActivity, 8))
        if (active) background = Jv.round(Jv.SEGMENT, Jv.dp(this@JackVoiceActivity, 9))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun hotwordRow(word: String): View {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 12), Jv.dp(this@JackVoiceActivity, 12), Jv.dp(this@JackVoiceActivity, 12))
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        column.addView(Jv.text(this, word, 16f))
        column.addView(
            Jv.text(this, "听准时优先识别", 12f, Jv.TEXT2).apply {
                setPadding(0, Jv.dp(this@JackVoiceActivity, 5), 0, 0)
            },
        )
        box.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(iconText("删除", Jv.DANGER) { confirmRemoveHotword(word) })
        return box
    }

    private fun ruleRow(rule: VoiceDictionary.Rule): View {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 12), Jv.dp(this@JackVoiceActivity, 8), Jv.dp(this@JackVoiceActivity, 12))
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        column.addView(Jv.text(this, "${rule.from}  →  ${rule.to}", 15f))
        column.addView(
            Jv.text(this, "终稿后本地替换", 12f, Jv.TEXT2).apply {
                setPadding(0, Jv.dp(this@JackVoiceActivity, 5), 0, 0)
            },
        )
        box.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(iconText("编辑") { editRuleDialog(rule) })
        box.addView(iconText("删除", Jv.DANGER) { confirmRemoveRule(rule) })
        return box
    }

    private fun confirmRemoveHotword(word: String) {
        val lastOne = VoiceDictionary.hotwords(this).size <= 1
        val hasTable = VoicePrefs.boostingTableId(this).isNotBlank()
        val syncNote =
            when {
                lastOne && hasTable ->
                    "这是最后一条热词；火山热词表不接受空文件，云端旧表不会被清空。手机和 Mac 的词库会自动同步。"

                hasTable -> "火山识别热词表会一起更新，手机和 Mac 的词库会自动同步。"

                else -> "手机和 Mac 的词库会自动同步。"
            }
        AlertDialog
            .Builder(this)
            .setTitle("删除热词？")
            .setMessage("「$word」会从词库中删除，之后可以重新添加。$syncNote")
            .setPositiveButton("删除") { _, _ ->
                VoiceDictionary.removeHotword(this, word)
                syncHotwords()
                GlossarySyncManager.onLocalChange(this)
                select(Tab.Dictionary)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveRule(rule: VoiceDictionary.Rule) {
        AlertDialog
            .Builder(this)
            .setTitle("删除替换词？")
            .setMessage("「${rule.from} → ${rule.to}」会从词库中删除。")
            .setPositiveButton("删除") { _, _ ->
                VoiceDictionary.removeRule(this, rule.from)
                GlossarySyncManager.onLocalChange(this)
                select(Tab.Dictionary)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addWordDialog() {
        val input =
            EditText(this).apply {
                hint = "比如：达芬奇、AgenBoard"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle("添加热词")
                .setMessage(
                    "会按火山识别形保存：空格和标点去掉，例如 B roll → Broll。" +
                        if (VoicePrefs.boostingTableId(this).isNotBlank()) "保存后会同步到云端热词表。" else "",
                )
                .setView(input)
                .setPositiveButton("添加", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (VoiceDictionary.addHotword(this, input.text.toString())) {
                    dialog.dismiss()
                    syncHotwords()
                    GlossarySyncManager.onLocalChange(this)
                    select(Tab.Dictionary)
                } else {
                    input.error = "只能包含文字和数字，最长 32 个字符"
                }
            }
        }
        dialog.show()
    }

    private fun editRuleDialog(rule: VoiceDictionary.Rule?) {
        val fromInput =
            EditText(this).apply {
                hint = "听写里实际出现的内容，例如 Broll"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setText(rule?.from.orEmpty())
            }
        val toInput =
            EditText(this).apply {
                hint = "你希望的写法，例如 B roll"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setText(rule?.to.orEmpty())
            }
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 8), Jv.dp(this@JackVoiceActivity, 20), 0)
                addView(Jv.text(this@JackVoiceActivity, "识别结果", 12f, Jv.TEXT2))
                addView(fromInput)
                addView(
                    Jv.text(this@JackVoiceActivity, "替换为", 12f, Jv.TEXT2).apply {
                        setPadding(0, Jv.dp(this@JackVoiceActivity, 12), 0, 0)
                    },
                )
                addView(toInput)
                addView(
                    Jv.text(
                        this@JackVoiceActivity,
                        "英文忽略大小写，空格和拼写要一致；同一位置更长的规则优先。",
                        12f,
                        Jv.TEXT2,
                    ).apply {
                        setPadding(0, Jv.dp(this@JackVoiceActivity, 10), 0, 0)
                    },
                )
            }
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle(if (rule == null) "添加替换词" else "编辑替换词")
                .setView(box)
                .setPositiveButton(if (rule == null) "添加" else "保存", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = fromInput.text.toString().trim()
                val to = toInput.text.toString().trim()
                if (from.isEmpty()) {
                    fromInput.error = "不能为空"
                    return@setOnClickListener
                }
                if (to.isEmpty()) {
                    toInput.error = "不能为空"
                    return@setOnClickListener
                }
                val ok =
                    if (rule == null) {
                        VoiceDictionary.addRule(this, from, to)
                    } else {
                        VoiceDictionary.updateRule(this, rule.from, from, to)
                    }
                if (ok) {
                    dialog.dismiss()
                    GlossarySyncManager.onLocalChange(this)
                    select(Tab.Dictionary)
                }
            }
        }
        dialog.show()
    }

    // ── 设置 ────────────────────────────────────────────────────────────

    private fun settingsPage(): View {
        val col = Jv.column(this)
        col.addView(Jv.pageTitle(this, "设置"))

        col.addView(Jv.sectionLabel(this, "外观"))
        val appearance = Jv.card(this)
        appearance.addView(
            Jv.row(
                this,
                "深色模式",
                "跟随系统，或固定浅色 / 深色",
                trailing = appearanceLabel(),
                chevron = true,
            ) { showAppearanceDialog() },
        )
        col.addView(appearance)

        col.addView(Jv.sectionLabel(this, "词库云同步"))
        val cloud = Jv.card(this)
        val autoSync = VoicePrefs.glossaryAutoSync(this)
        cloud.addView(
            Jv.row(
                this,
                "词库同步",
                GlossarySyncManager.summary(this),
                trailing = if (autoSync) "自动" else "手动",
                chevron = true,
            ) { syncGlossaryNow() },
        )
        cloud.addView(Jv.divider(this))
        cloud.addView(
            Jv.row(
                this,
                "自动同步",
                "本机改动后自动推送；主界面和语音面板打开时自动拉取",
                trailing = if (autoSync) "开" else "关",
            ) {
                VoicePrefs.setGlossaryAutoSync(this, !autoSync)
                select(Tab.Settings)
            },
        )
        cloud.addView(Jv.divider(this))
        val tosAk = VoicePrefs.tosAccessKey(this)
        cloud.addView(
            Jv.row(
                this,
                "TOS AccessKey",
                if (tosAk.isBlank()) "未填写" else maskKey(tosAk),
                chevron = true,
            ) { editTosAccessKeyDialog() },
        )
        cloud.addView(Jv.divider(this))
        cloud.addView(
            Jv.row(
                this,
                "TOS SecretKey",
                if (VoicePrefs.tosSecretKey(this).isBlank()) "未填写" else "已填写",
                chevron = true,
            ) { editTosSecretKeyDialog() },
        )
        cloud.addView(Jv.divider(this))
        cloud.addView(
            Jv.row(
                this,
                "从 TOS 拉取词库",
                "用云端 glossary.json 覆盖本机",
                trailing = "拉取",
                chevron = true,
            ) { pullGlossaryFromTos() },
        )
        cloud.addView(Jv.divider(this))
        cloud.addView(
            Jv.row(
                this,
                "推送到 TOS",
                "用本机词库覆盖云端 glossary.json",
                trailing = "推送",
                chevron = true,
            ) { pushGlossaryToTos() },
        )
        col.addView(cloud)

        col.addView(Jv.sectionLabel(this, "语音识别"))
        val voice = Jv.card(this)
        val key = VoicePrefs.apiKey(this)
        voice.addView(
            Jv.row(
                this,
                "火山 API Key",
                if (key.isBlank()) "还没填，填了才能识别" else maskKey(key),
                trailing = null,
                chevron = true,
            ) { editKeyDialog() },
        )
        voice.addView(Jv.divider(this))
        voice.addView(
            Jv.row(
                this,
                "热词增强",
                "开启后优先用云端热词表；未配置时直传本地热词",
                trailing = if (VoicePrefs.hotwordsEnabled(this)) "开" else "关",
            ) {
                VoicePrefs.setHotwordsEnabled(this, !VoicePrefs.hotwordsEnabled(this))
                select(Tab.Settings)
            },
        )
        voice.addView(Jv.divider(this))
        val boostingTableId = VoicePrefs.boostingTableId(this)
        voice.addView(
            Jv.row(
                this,
                "识别热词表（火山）",
                if (boostingTableId.isBlank()) {
                    "未设置：本地热词按 100 字符预算直传"
                } else {
                    "使用云端热词表，优先于本地直传"
                },
                trailing = if (boostingTableId.isBlank()) "未设置" else "已配置",
                chevron = true,
            ) { editBoostingTableDialog() },
        )
        voice.addView(Jv.divider(this))
        voice.addView(
            Jv.row(
                this,
                "同步识别热词表",
                "把当前 ${VoiceDictionary.hotwords(this).size} 条热词整体覆盖到云端",
                trailing = "同步",
                chevron = true,
            ) { syncHotwords(manual = true) },
        )
        voice.addView(Jv.divider(this))
        voice.addView(
            Jv.row(
                this,
                "替换词",
                "终稿后本地执行；通过 TOS 在手机和 Mac 间同步",
                trailing = "${VoiceDictionary.rules(this).size} 条",
                chevron = true,
            ) { select(Tab.Dictionary) },
        )
        voice.addView(Jv.divider(this))
        voice.addView(
            Jv.row(
                this,
                "麦克风权限",
                if (hasMic()) "已授权" else "未授权，点这里去开",
                chevron = !hasMic(),
            ) {
                if (!hasMic()) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:$packageName")),
                    )
                }
            },
        )
        col.addView(voice)

        col.addView(Jv.sectionLabel(this, "键盘"))
        val kb = Jv.card(this)
        kb.addView(
            Jv.row(this, "输入法设置", "启用 / 切换输入法", chevron = true) {
                runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            },
        )
        kb.addView(Jv.divider(this))
        kb.addView(
            Jv.row(this, "输入法高级设置", "方案、主题、按键、词库（同文原生设置）", chevron = true) {
                startActivity(Intent(this, MainActivity::class.java))
            },
        )
        col.addView(kb)

        col.addView(Jv.sectionLabel(this, "关于"))
        val about = Jv.card(this)
        about.addView(Jv.row(this, "版本", BuildConfig.VERSION_NAME))
        about.addView(Jv.divider(this))
        about.addView(
            Jv.row(this, "听写记录", "共 ${VoiceHistory.list(this).size} 条，清空", chevron = true) {
                AlertDialog
                    .Builder(this)
                    .setTitle("清空听写记录？")
                    .setMessage("只会删掉这台设备上的记录，不可恢复。")
                    .setPositiveButton("清空") { _, _ ->
                        VoiceHistory.clear(this)
                        select(Tab.Settings)
                    }.setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        )
        col.addView(about)

        return Jv.page(col)
    }

    private fun appearanceLabel(): String = when (AppearanceManager.mode()) {
        AppPrefs.Advanced.UiMode.AUTO -> "自动"
        AppPrefs.Advanced.UiMode.LIGHT -> "浅色"
        AppPrefs.Advanced.UiMode.DARK -> "深色"
    }

    /**
     * 外观只写一个偏好（`ui_mode`），由 [AppearanceManager] 同时驱动
     * AppCompat 的 DayNight 和输入法自己的配色。改完 AppCompat 会重建 Activity，
     * 当前 Tab 由 onSaveInstanceState 带过去。
     */
    private fun showAppearanceDialog() {
        val modes = AppPrefs.Advanced.UiMode.entries
        val labels = arrayOf("自动（跟随系统）", "浅色", "深色")
        AlertDialog
            .Builder(this)
            .setTitle("外观")
            .setSingleChoiceItems(labels, modes.indexOf(AppearanceManager.mode())) { dialog, which ->
                dialog.dismiss()
                AppPrefs.defaultInstance().advanced.uiMode.setValue(modes[which])
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun maskKey(key: String): String = if (key.length <= 8) "已填写" else key.take(4) + "…" + key.takeLast(4)

    private fun editBoostingTableDialog() {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(VoicePrefs.boostingTableId(this@JackVoiceActivity))
                hint = "留空则使用请求级热词直传"
                setSingleLine()
                typeface = Typeface.MONOSPACE
            }
        AlertDialog
            .Builder(this)
            .setTitle("云端热词表 ID")
            .setMessage(
                "填桌面版 JackVoice settings.json 里的 volcBoostingTableId，两边就会用同一张热词表。" +
                    "留空则把本地热词直传给识别请求（最多 100 字符）。",
            )
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                VoicePrefs.setBoostingTableId(this, input.text.toString())
                select(Tab.Settings)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editTosAccessKeyDialog() {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(VoicePrefs.tosAccessKey(this@JackVoiceActivity))
                hint = "IAM 用户 jackvoice-sync 的 AccessKey ID"
                setSingleLine()
                typeface = Typeface.MONOSPACE
            }
        AlertDialog
            .Builder(this)
            .setTitle("TOS AccessKey")
            .setMessage("只用于读写 jackvoice-glossary 桶里的 glossary.json。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                VoicePrefs.setTosAccessKey(this, input.text.toString())
                select(Tab.Settings)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editTosSecretKeyDialog() {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(VoicePrefs.tosSecretKey(this@JackVoiceActivity))
                hint = "AccessKey Secret"
                setSingleLine()
                typeface = Typeface.MONOSPACE
            }
        AlertDialog
            .Builder(this)
            .setTitle("TOS SecretKey")
            .setMessage("只保存在本机应用私有存储里。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                VoicePrefs.setTosSecretKey(this, input.text.toString())
                select(Tab.Settings)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editKeyDialog() {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setText(VoicePrefs.apiKey(this@JackVoiceActivity))
                hint = "火山引擎控制台里的 API Key"
                setSingleLine()
                typeface = Typeface.MONOSPACE
            }
        AlertDialog
            .Builder(this)
            .setTitle("火山 API Key")
            .setMessage("和 Xime 插件、JackVoice 独立应用共用同一把，填一次即可。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                VoicePrefs.setApiKey(this, input.text.toString())
                select(Tab.Settings)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
