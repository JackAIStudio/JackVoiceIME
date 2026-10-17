/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.keyboard.InputFeedbackManager

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

    /** 点候选词，参数是它在全量候选列表里的下标。 */
    var onCandidatePick: ((Int) -> Unit)? = null

    /** 点右边的展开箭头：展开或收起整页候选。 */
    var onExpandTap: (() -> Unit)? = null

    /** 点候选行里的语言小键：切到另一种语言（中文 ↔ 英文）。 */
    var onLanguageSwitchTap: (() -> Unit)? = null

    private val brand =
        TextView(context).apply {
            text = "JackVoice"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

    private val waveCell = cell(WaveGlyphView(context)) { onVoiceTap?.invoke() }
    private val pinyinCell = cell(label("拼")) { onPinyinTap?.invoke() }
    private val englishCell = cell(label("EN")) { onEnglishTap?.invoke() }
    private val hideCell = cell(HideKeyboardGlyphView(context)) { onHideTap?.invoke() }

    private val cells = listOf(waveCell, pinyinCell, englishCell)

    private val switcher =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(WHITE, dp(18))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(waveCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(pinyinCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(englishCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(hideCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

    private val candidates =
        CandidateStripView(context).apply {
            onPick = { index -> onCandidatePick?.invoke(index) }
        }

    private val expandButton =
        FrameLayout(context).apply {
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onExpandTap?.invoke()
            }
            addView(
                ChevronGlyphView(context),
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
            setTextColor(TEXT2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onLanguageSwitchTap?.invoke()
            }
        }

    private val composingRow =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            addView(
                candidates,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
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
                marginStart = dp(18)
            },
        )
        addView(
            switcher,
            LayoutParams(dp(176), dp(36)).apply {
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

    /** 正在拼写：候选顶替品牌与胶囊。 */
    fun setComposing(value: Boolean) {
        if (composing == value) return
        composing = value
        brand.visibility = if (value) View.GONE else View.VISIBLE
        switcher.visibility = if (value) View.GONE else View.VISIBLE
        composingRow.visibility = if (value) View.VISIBLE else View.GONE
        if (!value) {
            candidates.clear()
            setExpanded(false)
        }
    }

    fun updateCandidates(
        items: Array<CandidateProto>,
        highlighted: Int,
    ) {
        candidates.update(items, highlighted)
    }

    /** 展开状态时箭头朝上，再点收起。 */
    fun setExpanded(expanded: Boolean) {
        (expandButton.getChildAt(0) as? ChevronGlyphView)?.pointsUp = expanded
    }

    fun setActive(tab: VoiceTab) {
        cells.forEachIndexed { i, cell ->
            val on = i == tab.ordinal
            cell.background = if (on) rounded(ACTIVE, dp(13)) else null
            (cell.getChildAt(0) as? TextView)?.setTextColor(if (on) TEXT else TEXT2)
        }
        // 候选行的小键显示「要切过去的语言」，别显示当前语言——中文时显示 EN。
        langSwitch.text = if (tab == VoiceTab.English) "拼" else "EN"
    }

    private fun cell(
        content: View,
        onClick: () -> Unit,
    ): FrameLayout =
        FrameLayout(context).apply {
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onClick()
            }
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    private fun label(text: String): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(TEXT2)
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
        const val HEIGHT_DP = 44

        /** 顶栏距键盘区顶部的留白。语音面板和打字键盘用同一个值，切过去胶囊不会上下跳。 */
        const val TOP_MARGIN_DP = 11

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val ACTIVE = 0xFFEFEFEF.toInt()
        private const val TEXT = 0xFF111111.toInt()
        private const val TEXT2 = 0xFF8A8A8A.toInt()
    }
}

/** 切换器里的语音标识：五根圆头竖条的小波形，整体控制在 18dp 见方，别撑满整格。 */
class WaveGlyphView(
    context: Context,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            strokeCap = Paint.Cap.ROUND
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
            color = 0xFF8A8A8A.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val path = Path()

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
            color = 0xFF111111.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val path = Path()

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
