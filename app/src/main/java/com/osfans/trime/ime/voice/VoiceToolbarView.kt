/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.CandidateProto

/** 顶栏切换器里的三个位置。 */
enum class VoiceTab { Voice, Pinyin, English }

/**
 * 键盘顶部的工具条：左边品牌，右边 `[🎙|拼|EN]` 切换胶囊。
 *
 * 尺寸逐项量自 Typeless 截图：
 *   容器   142×37dp 白色全圆角，内缩 3dp
 *   活跃格 浅灰胶囊，四角全圆——**必须内缩在容器里**，否则两块圆角怼在一起像个葫芦
 *   图标   20dp 五根竖条，条宽 0.115、间距 0.14（间距做宽了整颗图标就散）
 *   文字   15sp，选中 #111、未选 #8A8A8A
 *
 * **这一行同时也是候选行**（Typeless 就是这样）：一输入中文，品牌和胶囊让位给候选词，
 * 候选消失再换回来。所以键盘高度从头到尾不变，不会出现两条横栏叠在一起。
 */
class VoiceToolbarView(
    context: Context,
) : FrameLayout(context) {
    var onVoiceTap: (() -> Unit)? = null
    var onPinyinTap: (() -> Unit)? = null
    var onEnglishTap: (() -> Unit)? = null

    /** 点候选词，参数是它在全量候选列表里的下标。 */
    var onCandidatePick: ((Int) -> Unit)? = null

    private val brand =
        TextView(context).apply {
            text = "JackVoice"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

    private val waveCell = cell(WaveGlyphView(context)) { onVoiceTap?.invoke() }
    private val pinyinCell = cell(label("拼")) { onPinyinTap?.invoke() }
    private val englishCell = cell(label("EN")) { onEnglishTap?.invoke() }

    private val cells = listOf(waveCell, pinyinCell, englishCell)

    private val switcher =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(WHITE, dp(19))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(waveCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(pinyinCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(englishCell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

    private val candidates =
        CandidateStripView(context).apply {
            visibility = GONE
            onPick = { index -> onCandidatePick?.invoke(index) }
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
            LayoutParams(dp(142), dp(38)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dp(15)
            },
        )
        addView(
            candidates,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        setActive(VoiceTab.Voice)
    }

    fun setActive(tab: VoiceTab) {
        cells.forEachIndexed { i, cell ->
            val on = i == tab.ordinal
            cell.background = if (on) rounded(ACTIVE, dp(15)) else null
            (cell.getChildAt(0) as? TextView)?.setTextColor(if (on) TEXT else TEXT2)
        }
    }

    /** 正在拼写：候选顶替品牌与胶囊。 */
    fun setComposing(value: Boolean) {
        if (composing == value) return
        composing = value
        brand.visibility = if (value) GONE else VISIBLE
        switcher.visibility = if (value) GONE else VISIBLE
        candidates.visibility = if (value) VISIBLE else GONE
        if (!value) candidates.clear()
    }

    fun updateCandidates(
        items: Array<CandidateProto>,
        highlighted: Int,
    ) {
        candidates.update(items, highlighted)
    }

    private fun cell(
        content: View,
        onClick: () -> Unit,
    ): FrameLayout =
        FrameLayout(context).apply {
            isClickable = true
            setOnClickListener { onClick() }
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

/** 切换器里的语音标识：五根圆头竖条的小波形，比例照 Typeless 量。 */
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
        val h = minOf(width, height).toFloat()
        val weights = floatArrayOf(0.35f, 0.75f, 1f, 0.72f, 0.32f)
        paint.strokeWidth = h * 0.115f
        val gap = h * 0.14f
        for (i in weights.indices) {
            val bh = h * weights[i]
            val x = cx + (i - 2) * (paint.strokeWidth + gap)
            canvas.drawLine(x, cy - bh / 2, x, cy + bh / 2, paint)
        }
    }
}
