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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ime.voice.VoiceDictionary
import com.osfans.trime.ime.voice.VoiceHistory
import com.osfans.trime.ime.voice.VoicePrefs
import com.osfans.trime.ime.voice.VoiceStats
import com.osfans.trime.ui.main.MainActivity
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
class JackVoiceActivity : AppCompatActivity() {
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

    /** 词典页当前筛选：只看我的 / 看全部。 */
    private var onlyMine = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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
        select(Tab.Home)
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

    private fun weight() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

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
    private fun isImeEnabled(): Boolean =
        runCatching {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.any { it.packageName == packageName } == true
        }.getOrDefault(false)

    private fun isImeDefault(): Boolean =
        runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.startsWith(packageName) == true
        }.getOrDefault(false)

    private fun hasMic(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
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
        onClick: () -> Unit,
    ): TextView =
        Jv.text(this, label, 14f, Jv.TEXT2).apply {
            setPadding(Jv.dp(this@JackVoiceActivity, 10), Jv.dp(this@JackVoiceActivity, 6), Jv.dp(this@JackVoiceActivity, 10), Jv.dp(this@JackVoiceActivity, 6))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ── 词典 ────────────────────────────────────────────────────────────

    private fun dictionaryPage(): View {
        val col = Jv.column(this)
        col.addView(Jv.pageTitle(this, "词典"))

        val builtinCount = VoiceDictionary.builtinHotwords(this).size
        val mine = VoiceDictionary.userHotwords(this)
        col.addView(
            Jv.text(
                this,
                "内置 $builtinCount 条热词 + ${VoiceDictionary.builtinRules(this).size} 条替换规则，" +
                    "识别时一并送给引擎。下面也可以自己加。",
                13f,
                Jv.TEXT2,
            ).apply {
                setPadding(Jv.dp(this@JackVoiceActivity, 20), 0, Jv.dp(this@JackVoiceActivity, 20), Jv.dp(this@JackVoiceActivity, 12))
            },
        )

        val seg =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = Jv.round(Jv.CARD, Jv.dp(this@JackVoiceActivity, 12))
                setPadding(Jv.dp(this@JackVoiceActivity, 4), Jv.dp(this@JackVoiceActivity, 4), Jv.dp(this@JackVoiceActivity, 4), Jv.dp(this@JackVoiceActivity, 4))
            }
        seg.addView(segment("全部", !onlyMine) { onlyMine = false; select(Tab.Dictionary) }, weight())
        seg.addView(segment("我的（${mine.size}）", onlyMine) { onlyMine = true; select(Tab.Dictionary) }, weight())
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

        val words = if (onlyMine) mine else VoiceDictionary.hotwords(this)
        col.addView(Jv.sectionLabel(this, "热词 ${words.size} 条"))
        val card = Jv.card(this)
        words.forEachIndexed { i, w ->
            val userAdded = w in mine
            card.addView(
                Jv.row(
                    this,
                    w,
                    if (userAdded) "手动添加" else "内置",
                ) {
                    if (userAdded) {
                        VoiceDictionary.removeHotword(this, w)
                        select(Tab.Dictionary)
                    }
                },
            )
            if (i != words.lastIndex) card.addView(Jv.divider(this))
        }
        col.addView(card)
        col.addView(Jv.primaryButton(this, "添加词条") { addWordDialog() })
        return Jv.page(col)
    }

    private fun segment(
        label: String,
        active: Boolean,
        onClick: () -> Unit,
    ): TextView =
        Jv.text(this, label, 14f, if (active) Jv.TEXT else Jv.TEXT2, bold = active).apply {
            gravity = Gravity.CENTER
            setPadding(0, Jv.dp(this@JackVoiceActivity, 8), 0, Jv.dp(this@JackVoiceActivity, 8))
            if (active) background = Jv.round(0xFFFFFFFF.toInt(), Jv.dp(this@JackVoiceActivity, 9))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun addWordDialog() {
        val input =
            EditText(this).apply {
                hint = "比如：达芬奇、AgenBoard"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
            }
        AlertDialog
            .Builder(this)
            .setTitle("添加词条")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                VoiceDictionary.addHotword(this, input.text.toString())
                select(Tab.Dictionary)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── 设置 ────────────────────────────────────────────────────────────

    private fun settingsPage(): View {
        val col = Jv.column(this)
        col.addView(Jv.pageTitle(this, "设置"))

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
                "把 ${VoiceDictionary.hotwords(this).size} 条热词一起送给识别引擎",
                trailing = if (VoicePrefs.hotwordsEnabled(this)) "开" else "关",
            ) {
                VoicePrefs.setHotwordsEnabled(this, !VoicePrefs.hotwordsEnabled(this))
                select(Tab.Settings)
            },
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

    private fun maskKey(key: String): String =
        if (key.length <= 8) "已填写" else key.take(4) + "…" + key.takeLast(4)

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
