/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ui.jackvoice.JvColors
import com.osfans.trime.util.AppUtils

/** 顶栏切换器里的几个位置。Hide 不是「语言」，只是收起键盘。 */
enum class VoiceTab { Voice, Pinyin, English, Hide }

/**
 * 键盘顶部的工具条：左边品牌，右边 `[🎙|拼|EN|⌄]` 切换胶囊。
 *
 * 尺寸逐项量自 Typeless 截图：
 *   容器   142×36dp 白色全圆角，内缩 3dp（加「收起键盘」这格以后加宽到 176dp）
 *   活跃格 浅灰胶囊，四角全圆——**必须内缩在容器里**，否则两块圆角怼在一起像个葫芦
 *   图标   五根竖条的小波形，整体控制在 18dp 内（撑满整格就太粗太高了）
 *   文字   15sp，选中 #111、未选 #8A8A8A
 *
 * **输入中文时这一行让给候选**：品牌和胶囊收起，换成「候选 + 展开箭头」；
 * 拼写单独占键盘上沿外面那条（[ComposingBarView]）。
 */
class VoiceToolbarView(
    context: Context,
) : FrameLayout(context) {
    var onVoiceTap: (() -> Unit)? = null
    var onPinyinTap: (() -> Unit)? = null
    var onEnglishTap: (() -> Unit)? = null
    var onHideTap: (() -> Unit)? = null

    /** 点左侧品牌字样：打开应用主页面（默认调用 AppUtils.launchMainActivity）。 */
    var onBrandTap: (() -> Unit)? = null

    /** 点候选词，参数是它在全量候选列表里的下标。 */
    var onCandidatePick: ((Int) -> Unit)? = null

    /** 点右边的展开箭头：展开或收起整页候选。 */
    var onExpandTap: (() -> Unit)? = null

    /** 长按候选：只有 emoji 用得到，弹肤色 / 性别变体。 */
    var onCandidateLongPick: ((index: Int, anchor: View) -> Unit)? = null

    /** 点候选行里的 ✕：把已打的搜索词一次清掉。 */
    var onClearQuery: (() -> Unit)? = null

    /** 点候选行里的语言小键：切到另一种语言（中文 ↔ 英文）。 */
    var onLanguageSwitchTap: (() -> Unit)? = null

    /** 点候选行里的红色波形：回语音面板（听写在后台跑着时）。 */
    var onVoiceFromComposing: (() -> Unit)? = null

    private val brand =
        TextView(context).apply {
            text = "JackVoice"
            setTextColor(JvColors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            val out = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
                setBackgroundResource(out.resourceId)
            }
            setPadding(dp(4), 0, dp(12), 0)
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onBrandTap?.invoke() ?: AppUtils.launchMainActivity(context)
            }
        }

    private val waveGlyph = WaveGlyphView(context)
    private val composingWaveGlyph = WaveGlyphView(context).apply { voiceState = VoiceState.Recording }
    private val chevronGlyph = ChevronGlyphView(context)
    private val hideGlyph = HideKeyboardGlyphView(context)

    private val waveCell = cell(waveGlyph) { onVoiceTap?.invoke() }
    private val pinyinCell = cell(label("拼")) { onPinyinTap?.invoke() }
    private val englishCell = cell(label("EN")) { onEnglishTap?.invoke() }
    private val hideCell = cell(hideGlyph) { onHideTap?.invoke() }

    private val cells = listOf(waveCell, pinyinCell, englishCell)

    private var activeTab = VoiceTab.Voice

    private val switcher =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(JvColors.keyBg, dp(18))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(waveCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(pinyinCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(englishCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(hideCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

    private val candidates =
        CandidateStripView(context).apply {
            onPick = { index -> onCandidatePick?.invoke(index) }
            onLongPick = { index, anchor -> onCandidateLongPick?.invoke(index, anchor) }
        }

    /**
     * 表情搜索「没找到」时顶上来的那行字。
     *
     * 和 [candidates] 叠在同一格里（互斥显示）：有结果时给候选条，没结果时给这行提示。
     */
    private val emojiHint =
        TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(JvColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(12), 0)
            visibility = View.GONE
        }

    private val expandButton =
        FrameLayout(context).apply {
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onExpandTap?.invoke()
            }
            addView(
                chevronGlyph,
                LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER },
            )
        }

    /**
     * 候选行里的语言切换小键（显示**目标**语言）：
     * 中文候选时显示「EN」，英文候选时显示「拼」。
     *
     * 候选一出现就把整行顶栏占满了，「拼/EN」胶囊也跟着收起来了——英文候选
     * 几乎常驻，用户想切回中文时会发现没有按钮可点，点到的反而是候选词。
     * 所以在候选行里留这一格，输入中也能随时切换。
     */
    private val langSwitch =
        TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(JvColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onLanguageSwitchTap?.invoke()
            }
        }

    /**
     * 候选行里的「后台听写还在跑」入口。
     *
     * 候选一出现，「品牌 + 胶囊」整行让给候选，那颗 🎙 也就看不见了——录音中
     * 打拼音时用户想停下来说完了，会找不到回语音面板的入口。这一格只在听写
     * 非空闲时出现（红色波形），点它直接回语音面板。
     */
    private val composingVoice =
        FrameLayout(context).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onVoiceFromComposing?.invoke()
            }
            addView(
                composingWaveGlyph,
                LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER },
            )
        }

    /**
     * 候选行里的 ✕：清空 emoji 搜索词。
     *
     * 没有它的时候，打错了只能一个字一个字退格——搜索词长到十几个字母时非常烦。
     * 只在 emoji 模式下显示（`emojiCustomMode`），别的时候这一格不占地方。
     */
    private val clearQuery =
        TextView(context).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(JvColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isClickable = true
            visibility = View.GONE
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onClearQuery?.invoke()
            }
        }

    /** 当前候选行是不是 emoji 来源：决定 ✕ 要不要露出来。 */
    private var emojiCustomMode = false

    private val composingRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            addView(
                // 候选条和「没搜到」提示叠在同一格：同一时刻只有一个可见。
                FrameLayout(context).apply {
                    addView(
                        candidates,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                    )
                    addView(
                        emojiHint,
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                    )
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                composingVoice,
                LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT),
            )
            addView(
                clearQuery,
                LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.MATCH_PARENT),
            )
            addView(
                langSwitch,
                LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.MATCH_PARENT),
            )
            addView(
                expandButton,
                LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT),
            )
        }

    private var composing = false

    init {
        addView(
            brand,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = dp(14)
            },
        )
        addView(
            switcher,
            LayoutParams(dp(176), dp(40)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dp(15)
            },
        )
        addView(
            composingRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        setActive(VoiceTab.Voice)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        updateHideTouchDelegate()
    }

    /**
     * 「收起键盘」是顶栏最右一格，视觉上只有 42×34dp，手指擦到胶囊边缘或右侧
     * 留白就容易点空。这里把可点范围放大到整个顶栏右上角（含上下边距和右侧
     * 15dp 留白），视觉不变，也不侵占左边的「EN」。
     *
     * 候选行模式下这一格被 GONE 掉了，右上角换成「展开候选」；这时必须撤掉
     * 代理，否则会把展开键的点击抢走。
     */
    private fun updateHideTouchDelegate() {
        if (switcher.visibility != View.VISIBLE || hideCell.width == 0 || width == 0 || height == 0) {
            touchDelegate = null
            return
        }
        val cellLeft = switcher.left + hideCell.left
        touchDelegate =
            if (cellLeft in 1 until width) {
                TouchDelegate(Rect(cellLeft, 0, width, height), hideCell)
            } else {
                null
            }
    }

    /** 正在拼写：候选顶替品牌与胶囊。 */
    fun setComposing(value: Boolean) {
        if (composing == value) return
        composing = value
        brand.visibility = if (value) View.GONE else View.VISIBLE
        switcher.visibility = if (value) View.GONE else View.VISIBLE
        composingRow.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) {
            candidates.clear()
            emojiHint.visibility = View.GONE
            setExpanded(false)
        }
        // 候选行的展开键和新露出来的收起格都会占右上角，切换后重算一次代理。
        post { updateHideTouchDelegate() }
    }

    /**
     * 候选行进入 / 离开 emoji 来源。
     *
     * 只有 emoji 模式才需要 ✕ 和「没找到」提示，中文/英文候选不该出现这两个东西。
     */
    fun setEmojiCandidateMode(emoji: Boolean) {
        emojiCustomMode = emoji
        clearQuery.visibility = if (emoji) View.VISIBLE else View.GONE
        if (!emoji) emojiHint.visibility = View.GONE
    }

    fun updateCandidates(
        items: Array<CandidateProto>,
        highlighted: Int,
    ) {
        candidates.update(items, highlighted)
        emojiHint.visibility = View.GONE
    }

    /**
     * 表情搜索没有结果。
     *
     * 候选行必须留着——它是「我正在搜」的唯一反馈。把它撤掉退回品牌行的话，
     * 用户看不到自己打了什么，只会以为键盘没反应。
     */
    fun showEmojiNoResult(query: String) {
        candidates.clear()
        // 查询词太长时只截前 8 个字符：整串丢给 TextView 会被它自己按像素截断，
        // 断在哪一格不可控，看着像乱码。这里主动截断，省略号位置就是可预期的。
        val shown = if (query.length > NO_RESULT_QUERY_MAX) {
            query.take(NO_RESULT_QUERY_MAX) + "…"
        } else {
            query
        }
        emojiHint.text = context.getString(R.string.emoji_search_empty, shown)
        emojiHint.visibility = View.VISIBLE
    }

    /** 展开状态时箭头朝上，再点收起。 */
    fun setExpanded(expanded: Boolean) {
        chevronGlyph.pointsUp = expanded
    }

    fun setActive(tab: VoiceTab) {
        activeTab = tab
        cells.forEachIndexed { i, cell ->
            val on = i == tab.ordinal
            cell.background = if (on) rounded(JvColors.toolbarActive, dp(13)) else null
            (cell.getChildAt(0) as? TextView)?.setTextColor(
                if (on) JvColors.text else JvColors.textSecondary,
            )
        }
        // 候选行的小键显示「要切过去的语言」，别显示当前语言——中文时显示 EN。
        langSwitch.text = if (tab == VoiceTab.English) "拼" else "EN"
    }

    /** 外观模式切换后重新取一遍色，不重建控件。 */
    fun refreshColors() {
        brand.setTextColor(JvColors.text)
        switcher.background = rounded(JvColors.keyBg, dp(18))
        langSwitch.setTextColor(JvColors.textSecondary)
        setActive(activeTab)
        candidates.refreshColors()
        emojiHint.setTextColor(JvColors.textSecondary)
        clearQuery.setTextColor(JvColors.textSecondary)
        chevronGlyph.refreshColors()
        hideGlyph.refreshColors()
        waveGlyph.refreshColors()
        composingWaveGlyph.refreshColors()
    }

    /**
     * 后台听写的状态落在 🎙 这个小波形的颜色上：
     * 录音中红、等终稿橙、空闲黑。切到拼音键盘也能一眼看出还在录；
     * 候选行里那格红色波形也靠它显隐。
     */
    fun setVoiceState(state: VoiceState) {
        waveGlyph.voiceState = state
        composingWaveGlyph.voiceState = state
        composingVoice.visibility = if (state == VoiceState.Idle) View.GONE else View.VISIBLE
    }

    private fun cell(
        content: View,
        onClick: () -> Unit,
    ): FrameLayout = FrameLayout(context).apply {
        isClickable = true
        setOnClickListener {
            InputFeedbackManager.keyPressVibrate(this)
            onClick()
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(JvColors.textSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun rounded(
        color: Int,
        radius: Int,
    ) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 「没有匹配」提示里最多显示几个字符，超出用省略号。 */
        private const val NO_RESULT_QUERY_MAX = 8

        const val HEIGHT_DP = 44

        /** 顶栏距键盘区顶部的留白。语音面板和打字键盘用同一个值，切过去胶囊不会上下跳。 */
        const val TOP_MARGIN_DP = 11
    }
}

/** 切换器里的语音标识：五根圆头竖条的小波形，整体控制在 18dp 见方，别撑满整格。 */
class WaveGlyphView(
    context: Context,
) : View(context) {
    /** 听写状态：录音中变红、等终稿变橙，切到拼音键盘也能看出后台还在工作。 */
    var voiceState: VoiceState = VoiceState.Idle
        set(value) {
            if (field == value) return
            field = value
            paint.color = colorFor(value)
            invalidate()
        }

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorFor(VoiceState.Idle)
            strokeCap = Paint.Cap.ROUND
        }

    /** 外观模式切换后重新取色。 */
    fun refreshColors() {
        paint.color = colorFor(voiceState)
        invalidate()
    }

    private fun colorFor(state: VoiceState): Int = when (state) {
        VoiceState.Recording -> JvColors.recording
        VoiceState.Thinking -> JvColors.thinking
        VoiceState.Idle -> JvColors.text
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val h = minOf(dp(18f), minOf(width, height).toFloat())

        val weights = floatArrayOf(0.34f, 0.72f, 1f, 0.70f, 0.32f)
        paint.strokeWidth = h * 0.075f
        val gap = h * 0.155f
        for (i in weights.indices) {
            val bh = h * weights[i]
            val x = cx + (i - 2) * (paint.strokeWidth + gap)
            canvas.drawLine(x, cy - bh / 2, x, cy + bh / 2, paint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/** 候选展开箭头：画出来的，几何中心对齐，不用 font 里的 `⌄`（那个会偏下）。 */
class ChevronGlyphView(
    context: Context,
) : View(context) {
    var pointsUp: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = JvColors.textSecondary
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val path = Path()

    fun refreshColors() {
        paint.color = JvColors.textSecondary
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = w * 0.22f
        val half = h * 0.16f
        paint.strokeWidth = w * 0.09f
        path.reset()
        if (pointsUp) {
            path.moveTo(inset, h / 2 + half)
            path.lineTo(w / 2, h / 2 - half)
            path.lineTo(w - inset, h / 2 + half)
        } else {
            path.moveTo(inset, h / 2 - half)
            path.lineTo(w / 2, h / 2 + half)
            path.lineTo(w - inset, h / 2 - half)
        }
        canvas.drawPath(path, paint)
    }
}

/** 收起键盘：向下的折线 + 底下一条横线。 */
class HideKeyboardGlyphView(
    context: Context,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = JvColors.text
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val path = Path()

    fun refreshColors() {
        paint.color = JvColors.text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        paint.strokeWidth = s * 0.085f
        val halfW = s * 0.27f
        val chevronTop = cy - s * 0.26f
        val chevronBottom = cy + s * 0.02f
        path.reset()
        path.moveTo(cx - halfW, chevronTop)
        path.lineTo(cx, chevronBottom)
        path.lineTo(cx + halfW, chevronTop)
        canvas.drawPath(path, paint)
        canvas.drawLine(cx - halfW, cy + s * 0.28f, cx + halfW, cy + s * 0.28f, paint)
    }
}
