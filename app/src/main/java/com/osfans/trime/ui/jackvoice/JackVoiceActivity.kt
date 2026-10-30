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
import android.os.Looper
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
import com.osfans.trime.ime.voice.AsrEngine
import com.osfans.trime.ime.voice.GlossarySyncClient
import com.osfans.trime.ime.voice.GlossarySyncManager
import com.osfans.trime.ime.voice.HotwordTableSync
import com.osfans.trime.ime.voice.LocalModelManager
import com.osfans.trime.ime.voice.MuteMode
import com.osfans.trime.ime.voice.PunctuationMode
import com.osfans.trime.ime.voice.VoiceAudio
import com.osfans.trime.ime.voice.VoiceAudioPlayer
import com.osfans.trime.ime.voice.VoiceDictionary
import com.osfans.trime.ime.voice.VoiceFailure
import com.osfans.trime.ime.voice.VoiceHistory
import com.osfans.trime.ime.voice.VoicePrefs
import com.osfans.trime.ime.voice.VoiceResidentService
import com.osfans.trime.ime.voice.VoiceStats
import com.osfans.trime.ime.voice.VoiceTranscriber
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
        // 切到别的 Tab 时历史卡片会被重建：播放器回调要跟着换一批控件。
        VoiceAudioPlayer.addListener(audioPlayerListener)
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
        // 用户在这里改过 API Key / 引擎 / 开关，回到前台时重新对一次条件：
        // 之前因为「缺 Key / 模型没下好」而没挂出来的常驻通知，现在该出来了。
        runCatching { VoiceResidentService.ensure(this) }
    }

    override fun onPause() {
        syncTicker?.cancel()
        syncTicker = null
        // 离开 App（或切走）就别在后台接着放录音。
        VoiceAudioPlayer.stop()
        super.onPause()
    }

    override fun onDestroy() {
        GlossarySyncManager.removeListener(this)
        VoiceAudioPlayer.removeListener(audioPlayerListener)
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
        // 卡片一换就没人管那条播放中的录音了：先停掉，别让声音留在后台。
        if (tab != Tab.History && VoiceAudioPlayer.playingEntryId != null) VoiceAudioPlayer.stop()
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
        if (VoicePrefs.engine(this) == AsrEngine.Local) {
            if (!LocalModelManager.isReady(this)) missing += "下载本地模型"
        } else if (VoicePrefs.apiKey(this).isBlank()) {
            missing += "填 API Key"
        }
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

    /** 正在重新识别的那条（界面上只标它一个，别让用户猜是哪条）。 */
    private var retryingEntryId: String? = null

    /**
     * 把识别回调切回主线程。
     *
     * [VoiceTranscriber] 的回调来自 OkHttp 线程，而 [select] 直接增删 View——
     * 在后台线程碰 View 要么抛 `CalledFromWrongThreadException`，
     * 要么在「刚好没触发检查」的时候悄悄不刷新（实测就是后者：
     * 文字已经写进历史，卡片却还停在「正在重新识别…」）。
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            runOnUiThread(block)
        }
    }

    /** 每条记录卡片上的播放/进度控件，播放器回调统一刷新它们。 */
    private val playControls = mutableListOf<PlayControl>()

    private class PlayControl(
        val entryId: String,
        val icon: android.widget.ImageView,
        val progress: android.widget.ProgressBar,
        val duration: TextView,
    )

    private val audioPlayerListener =
        object : VoiceAudioPlayer.Listener {
            override fun onPlayerChanged(
                playingEntryId: String?,
                progress: Float,
            ) {
                val ctx = this@JackVoiceActivity
                playControls.forEach { c ->
                    val playing = c.entryId == playingEntryId
                    c.icon.setImageResource(
                        if (playing) R.drawable.ic_baseline_pause_24 else R.drawable.ic_baseline_play_arrow_24,
                    )
                    c.icon.setColorFilter(if (playing) Jv.ACCENT else Jv.TEXT2)
                    c.progress.progress = (progress * 1000).toInt()
                    c.progress.visibility = if (playing) View.VISIBLE else View.GONE
                    val entry = VoiceHistory.find(ctx, c.entryId)
                    val ms = entry?.durationMs ?: 0L
                    c.duration.text =
                        if (playing) {
                            String.format(Locale.CHINA, "%d:%02d", (ms * progress / 1000).toInt() / 60, (ms * progress / 1000).toInt() % 60)
                        } else {
                            formatSeconds(ms)
                        }
                }
            }
        }

    private fun formatSeconds(ms: Long): String {
        if (ms <= 0) return ""
        val total = (ms / 1000).coerceAtLeast(1).toInt()
        return if (total >= 60) "${total / 60}:${String.format(Locale.CHINA, "%02d", total % 60)}" else "${total}s"
    }

    private fun historyPage(): View {
        playControls.clear()
        val col = Jv.column(this)
        col.addView(Jv.pageTitle(this, "历史记录"))

        val all = VoiceHistory.list(this)
        val pending = all.count { it.canRetry }
        val recorded = all.count { it.hasAudio }

        // 顶部只留一句最有用的：有多少条还没转出文字。
        if (pending > 0) {
            val notice = Jv.card(this, Jv.CARD, 16)
            notice.addView(
                Jv.text(this, "$pending 段录音还没转成文字", 16f, Jv.TEXT, bold = true).apply {
                    setPadding(Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 16), Jv.dp(this@JackVoiceActivity, 18), 0)
                },
            )
            notice.addView(
                Jv.text(this, "声音都在这台手机里，点「重新转写」就能再识别一次。", 13f, Jv.TEXT2).apply {
                    setPadding(Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 6), Jv.dp(this@JackVoiceActivity, 18), Jv.dp(this@JackVoiceActivity, 16))
                },
            )
            col.addView(notice)
        }

        if (all.isEmpty()) {
            col.addView(Jv.sectionLabel(this, "还没有记录"))
            val empty = Jv.card(this)
            empty.addView(
                Jv.text(this, "说一句试试——文字上屏后就会出现在这里，原始录音也一起留着。", 14f, Jv.TEXT2).apply {
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
        all.forEach { e ->
            val day = dayFmt.format(Date(e.atMillis))
            if (day != lastDay) {
                lastDay = day
                col.addView(Jv.sectionLabel(this, day))
            }
            col.addView(entryCard(e, timeFmt))
        }

        col.addView(
            Jv.text(this, "全部记录都存在本机，共 ${all.size} 条，其中 $recorded 条带录音，占用 ${formatMb(VoiceAudio.totalBytes(this))}。", 12f, Jv.TEXT2).apply {
                setPadding(Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 14), Jv.dp(this@JackVoiceActivity, 20), 0)
            },
        )
        return Jv.page(col)
    }

    /**
     * 一条历史记录。
     *
     * 三样东西必须一眼看见：**这段说的什么字**、**能不能听**、**为什么没有字**。
     * 失败条目不是「空卡片」——它是一段还在的声音，配上失败原因和重试。
     */
    private fun entryCard(
        e: VoiceHistory.Entry,
        timeFmt: SimpleDateFormat,
    ): View {
        val card = Jv.card(this, Jv.CARD, 16)
        val pad16 = Jv.dp(this@JackVoiceActivity, 16)

        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad16, Jv.dp(this@JackVoiceActivity, 14), pad16, 0)
            }
        header.addView(
            Jv.text(this, timeFmt.format(Date(e.atMillis)), 13f, Jv.TEXT2),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        // 时长 + 播放键：有录音才给听。
        if (e.hasAudio) {
            val durationText =
                Jv.text(this, formatSeconds(e.durationMs), 13f, Jv.TEXT2).apply {
                    setPadding(0, 0, Jv.dp(this@JackVoiceActivity, 10), 0)
                }
            header.addView(durationText)

            val icon =
                android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_baseline_play_arrow_24)
                    setColorFilter(Jv.TEXT2)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    val p = Jv.dp(this@JackVoiceActivity, 8)
                    setPadding(p, p, p, p)
                    isClickable = true
                    contentDescription = "试听这段录音"
                    setOnClickListener { VoiceAudioPlayer.toggle(this@JackVoiceActivity, e) }
                }
            val progress =
                android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = 0
                    visibility = View.GONE
                    progressTintList = android.content.res.ColorStateList.valueOf(Jv.ACCENT)
                }
            val playBox =
                FrameLayout(this).apply {
                    background = Jv.round(Jv.CARD_STRONG, Jv.dp(this@JackVoiceActivity, 17))
                    val size = Jv.dp(this@JackVoiceActivity, 34)
                    addView(icon, FrameLayout.LayoutParams(size, size))
                    addView(
                        progress,
                        FrameLayout.LayoutParams(size, Jv.dp(this@JackVoiceActivity, 3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                            bottomMargin = Jv.dp(this@JackVoiceActivity, 4)
                        },
                    )
                    setOnClickListener { VoiceAudioPlayer.toggle(this@JackVoiceActivity, e) }
                }
            header.addView(
                playBox,
                LinearLayout.LayoutParams(Jv.dp(this@JackVoiceActivity, 34), Jv.dp(this@JackVoiceActivity, 34)),
            )
            playControls += PlayControl(e.id, icon, progress, durationText)
        } else {
            val duration = formatSeconds(e.durationMs)
            if (duration.isNotEmpty()) header.addView(Jv.text(this, duration, 13f, Jv.TEXT2))
        }
        card.addView(header)

        if (e.hasText) {
            card.addView(
                Jv.text(this, e.text, 16f, Jv.TEXT).apply {
                    setPadding(pad16, Jv.dp(this@JackVoiceActivity, 8), pad16, 0)
                    setLineSpacing(Jv.dp(this@JackVoiceActivity, 5).toFloat(), 1f)
                },
            )
        } else {
            // 没有字：**一句话说清状态**，别再叠第二句解释。
            //
            // 以前这里是「状态行 + 蓝色提示行」两行，两行都在讲同一件事
            // （「这段里没听到人声」+「声音还留着，可以点右上角听」），
            // 卡片被撑得很高，真正要紧的「能不能重来」反而要往下找。
            // 现在状态行只讲发生了什么，可重试时把那句话交给下面的按钮。
            val statusLine =
                when (e.status) {
                    VoiceHistory.Status.Transcribing -> "正在识别…"
                    VoiceHistory.Status.Empty -> "这段里没听到人声"
                    VoiceHistory.Status.NoNetwork -> "当时网络没连上"
                    VoiceHistory.Status.Recording -> "上次听写被中断了"
                    // Failed 的 error 可能还是原始异常串（老记录、或者服务端回了别的错）：
                    // 走同一套判定，别把 `connection closed` 这种话摆到用户面前。
                    else -> VoiceFailure.describeForHistory(e.error)
                }
            card.addView(
                Jv.text(this, statusLine, 15f, Jv.TEXT).apply {
                    setPadding(pad16, Jv.dp(this@JackVoiceActivity, 8), pad16, 0)
                },
            )
            // 第二行只在**确实还能重来**时出现，而且说的是下一步，不是复述状态。
            // Empty 时什么都不加：服务端说没人说话，再怂恿重试是骗人。
            if (e.canRetry) {
                card.addView(
                    Jv.text(this, "声音还在，可以再识别一次", 13f, Jv.ACCENT).apply {
                        setPadding(pad16, Jv.dp(this@JackVoiceActivity, 6), pad16, 0)
                    },
                )
            }
        }

        // 操作区：主操作靠左、删除永远贴右。
        //
        // 以前全部 `gravity = END`，于是「只有删除」的卡片上那颗键孤零零挂在最右边，
        // 而「重新转写 + 删除」时两个键又挤在一起、分不出哪个是主操作。
        // 现在重试/复制靠左起头，删除用 weight=1 的占位顶到最右。
        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad16, Jv.dp(this@JackVoiceActivity, 8), pad16, Jv.dp(this@JackVoiceActivity, 12))
            }
        if (retryingEntryId == e.id) {
            // 正在跑的那条，把「正在处理」写在它自己身上——不写在别处让人猜。
            actions.addView(
                Jv.text(this, "正在重新识别…", 14f, Jv.ACCENT).apply {
                    setPadding(Jv.dp(this@JackVoiceActivity, 10), Jv.dp(this@JackVoiceActivity, 8), 0, Jv.dp(this@JackVoiceActivity, 8))
                },
            )
        } else if (e.canRetry) {
            actions.addView(
                iconText("重新转写", Jv.ACCENT) { retryEntry(e) },
            )
        }
        if (e.hasText) {
            actions.addView(
                iconText("复制") {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("JackVoice", e.text))
                    toast("已复制")
                },
            )
        }
        // 占位：把删除推到最右，哪怕这一行只有它一个。
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(
            iconText("删除") {
                if (VoiceAudioPlayer.playingEntryId == e.id) VoiceAudioPlayer.stop()
                VoiceHistory.delete(this, e)
                select(Tab.History)
            },
        )
        card.addView(actions)
        return card
    }

    /** 用本地录音补出这一条的文字。识别在主界面里也能跑，不必回键盘。 */
    private fun retryEntry(entry: VoiceHistory.Entry) {
        // 先看有没有在跑别的：直接告诉用户是哪一条，别让他猜。
        val busy = VoiceTranscriber.busyEntryId
        if (busy != null && busy != entry.id) {
            val other = VoiceHistory.find(this, busy)
            val at = other?.let { SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it.atMillis)) }
            toast(if (at != null) "$at 那条正在重新识别，完成后再点这条" else "另一条正在重新识别，请稍等")
            return
        }
        retryingEntryId = entry.id
        select(Tab.History)
        val started =
            VoiceTranscriber.transcribe(
                this,
                entry.id,
                object : VoiceTranscriber.Callback {
                    override fun onDone(
                        entryId: String,
                        text: String,
                    ) {
                        // 回调在 OkHttp 线程：先切回主线程再碰状态和 View。
                        onMain {
                            retryingEntryId = null
                            VoiceHistory.markCompleted(this@JackVoiceActivity, entryId, text, 0L)
                            // 历史页**只把文字补进记录**，不往输入框里塞：
                            // 这里没有「用户正在哪个输入框里说话」这个上下文，
                            // 贸然 commitText 会落到他完全没在看的那个 App 里。
                            // 文字就在下面这张卡片上，要复制有「复制」。
                            toast("已补上 ${text.length} 个字")
                            select(Tab.History)
                        }
                    }

                    override fun onFailed(
                        entryId: String,
                        message: String,
                        retryable: Boolean,
                    ) {
                        onMain {
                            retryingEntryId = null
                            // 与语音面板共用同一个判断：只有「服务端说没人说话」才收按钮。
                            // 以前这里是 if (retryable) NoNetwork else Empty —— 一个布尔
                            // 把「网络抖动」和「这段真的没人说话」混成同一件事，
                            // 断一次网按钮就再也不出现了。
                            VoiceHistory.markFailed(
                                this@JackVoiceActivity,
                                entryId,
                                VoiceHistory.statusAfterRetryFailure(message, retryable),
                                message,
                            )
                            toast("重新识别失败：${VoiceFailure.describe(message)}")
                            select(Tab.History)
                        }
                    }
                },
            )
        if (!started) {
            retryingEntryId = null
            select(Tab.History)
        }
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

    /** 设置页里切引擎；本地模型没装好就先下载，不把用户丢进一个跑不起来的模式。 */
    private fun toggleAsrEngine() {
        if (VoicePrefs.engine(this) == AsrEngine.Local) {
            VoicePrefs.setEngine(this, AsrEngine.Cloud)
            toast("已切到云端识别")
            select(Tab.Settings)
            return
        }
        if (!LocalModelManager.isReady(this)) {
            downloadLocalModel()
            return
        }
        VoicePrefs.setEngine(this, AsrEngine.Local)
        toast("已切到本地识别")
        select(Tab.Settings)
    }

    private fun downloadLocalModel() {
        val status = LocalModelManager.status(this)
        when {
            LocalModelManager.isReady(this) -> toast("本地模型已安装，可在键盘左上角切到本地")
            status.state == LocalModelManager.State.Downloading -> toast("本地模型正在下载 ${status.progress}%")
            else -> {
                LocalModelManager.ensure(this)
                toast("开始下载本地模型（约 128MB），完成后在键盘左上角切到本地")
            }
        }
    }

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
        val engine = VoicePrefs.engine(this)
        voice.addView(
            Jv.row(
                this,
                "识别引擎",
                "云端实时预览；本地离线识别；键盘左上角也能切",
                trailing = engine.label,
                chevron = true,
            ) { toggleAsrEngine() },
        )
        voice.addView(Jv.divider(this))
        val modelStatus = LocalModelManager.status(this)
        val modelReady = LocalModelManager.isReady(this)
        voice.addView(
            Jv.row(
                this,
                "本地模型",
                if (modelReady) {
                    "X-ASR 480ms，已安装，可离线识别"
                } else {
                    "X-ASR 480ms，约 128MB，下载后可离线识别"
                },
                trailing =
                    when (modelStatus.state) {
                        LocalModelManager.State.Downloading -> "${modelStatus.progress}%"
                        LocalModelManager.State.Ready -> "已安装"
                        LocalModelManager.State.Failed -> "重试"
                        LocalModelManager.State.Missing -> "下载"
                    },
                chevron = true,
            ) { downloadLocalModel() },
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
        val punctuation = VoicePrefs.punctuationMode(this)
        voice.addView(
            Jv.row(
                this,
                "标点",
                punctuation.hint,
                trailing = punctuation.label,
                chevron = true,
            ) { choosePunctuationMode() },
        )
        voice.addView(Jv.divider(this))
        voice.addView(
            Jv.row(
                this,
                "说话时按住其他应用的声音",
                VoicePrefs.muteMode(this).hint,
                trailing = VoicePrefs.muteMode(this).label,
                chevron = true,
            ) { chooseMuteMode() },
        )
        voice.addView(Jv.divider(this))
        val resident = VoicePrefs.residentEnabled(this)
        voice.addView(
            Jv.row(
                this,
                getString(R.string.voice_resident_setting_title),
                if (resident) {
                    getString(R.string.voice_resident_setting_hint_on)
                } else {
                    getString(R.string.voice_resident_setting_hint_off)
                },
                trailing = if (resident) "开" else "关",
            ) {
                VoicePrefs.setResidentEnabled(this, !resident)
                // 立刻生效：开关一拨，通知栏里那条就跟着出现 / 消失，
                // 不用等下一次录音或重启输入法。
                if (resident) {
                    VoiceResidentService.stop(this)
                } else {
                    VoiceResidentService.ensure(this)
                }
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
            Jv.row(
                this,
                "听写记录",
                "共 ${VoiceHistory.list(this).size} 条（录音 ${formatMb(VoiceAudio.totalBytes(this))}），清空",
                chevron = true,
            ) {
                AlertDialog
                    .Builder(this)
                    .setTitle("清空听写记录？")
                    .setMessage("记录和本机保存的原始录音会一起删掉，不可恢复。")
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

    private fun formatMb(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
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

    private fun chooseMuteMode() {
        val modes = MuteMode.entries
        AlertDialog
            .Builder(this)
            .setTitle("说话时按住其他应用的声音")
            .setSingleChoiceItems(
                modes.map { "${it.label}：${it.hint}" }.toTypedArray(),
                VoicePrefs.muteMode(this).ordinal,
            ) { dialog, which ->
                VoicePrefs.setMuteMode(this, modes[which])
                dialog.dismiss()
                select(Tab.Settings)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun choosePunctuationMode() {
        val modes = PunctuationMode.entries
        AlertDialog
            .Builder(this)
            .setTitle("标点")
            .setSingleChoiceItems(
                modes.map { it.label }.toTypedArray(),
                VoicePrefs.punctuationMode(this).ordinal,
            ) { dialog, which ->
                VoicePrefs.setPunctuationMode(this, modes[which])
                dialog.dismiss()
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
