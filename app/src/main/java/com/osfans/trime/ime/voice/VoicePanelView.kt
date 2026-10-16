/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

enum class VoiceState { Idle, Recording, Thinking }

/**
 * 语音面板。所有位置都是从 Typeless 的截图里量出来的（面板按 320dp 计）：
 *
 *   「点击说话」        中心 108dp
 *   中间那颗大按钮      中心 159dp（空闲胶囊 148×56 / 录音圆直径 123、光晕 150）
 *   「再次点击以完成」   中心 279dp（只在录音时出现）
 *   底部工具栏          左圆 x=45dp、右圆距右 42dp（⌫ 在 @ 上方 61dp）、中间胶囊 112×46
 *
 * 录音与处理态**整条底栏收起**——和 Typeless 一样，录音时画面只剩那颗大圆。
 */
class VoicePanelView(
    context: Context,
) : FrameLayout(context) {
    var onOrbTap: (() -> Unit)? = null
    var onKeyboardTap: (() -> Unit)? = null
    var onBackspaceTap: (() -> Unit)? = null
    var onAtTap: (() -> Unit)? = null
    var onActionTap: (() -> Unit)? = null
    var onPinyinTab: (() -> Unit)? = null
    var onEnglishTab: (() -> Unit)? = null

    /** 右侧键盘切换器里当前高亮的那一格。 */
    private val tabVoice = tabCell(WaveGlyphView(context), active = true)
    private val tabPinyin = tabCell(labelCell("\u62fc")) { onPinyinTab?.invoke() }
    private val tabEnglish = tabCell(labelCell("EN")) { onEnglishTab?.invoke() }

    private val statusView =
        TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

    private val hintView =
        TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }

    private val orb =
        VoiceOrbView(context).apply {
            isClickable = true
            setOnClickListener { onOrbTap?.invoke() }
        }

    private val actionKey = pillKey("") { onActionTap?.invoke() }

    private val bottomBar =
        FrameLayout(context).apply {
            addView(
                circleKey("\u2328", WHITE_KEY, onKeyboardTap),
                LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    marginStart = dp(22)
                    bottomMargin = dp(16)
                },
            )
            addView(
                actionKey,
                LayoutParams(dp(112), dp(46)).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(17)
                },
            )
            addView(
                circleKey("@", GRAY_KEY, onAtTap),
                LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = dp(18)
                    bottomMargin = dp(16)
                },
            )
            addView(
                circleKey("\u232B", GRAY_KEY, onBackspaceTap),
                LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = dp(18)
                    bottomMargin = dp(77)
                },
            )
        }

    init {
        setBackgroundColor(BACKGROUND)

        // ── 顶部工具条：左品牌、右键盘切换器 [🎙|拼|EN] ─────────────────────
        // 位置按 Typeless 量得：整行中心距面板顶 33dp，切换器 140×30dp、距右 15dp。
        val switcher =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundedDrawable(WHITE_KEY, dp(15))
                val cell =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                addView(tabVoice, cell)
                addView(tabPinyin, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                addView(tabEnglish, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        val toolbar =
            FrameLayout(context).apply {
                addView(
                    TextView(context).apply {
                        text = BRAND
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        marginStart = dp(18)
                    },
                )
                addView(
                    switcher,
                    LayoutParams(dp(140), dp(30)).apply {
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        marginEnd = dp(15)
                    },
                )
            }
        addView(
            toolbar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                gravity = Gravity.TOP
                topMargin = dp(11)
            },
        )

        addView(
            statusView,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(22)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(97)
            },
        )
        addView(
            orb,
            LayoutParams(dp(ORB_BOX), dp(ORB_BOX)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(ORB_CENTER - ORB_BOX / 2)
            },
        )
        addView(
            hintView,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(20)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(269)
            },
        )
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(125)).apply {
                gravity = Gravity.BOTTOM
            },
        )

        setState(VoiceState.Idle)
    }

    fun setState(state: VoiceState) {
        orb.state = state
        when (state) {
            VoiceState.Idle -> {
                statusView.visibility = VISIBLE
                statusView.text = "点击说话"
                hintView.visibility = GONE
                bottomBar.visibility = VISIBLE
            }

            VoiceState.Recording -> {
                statusView.visibility = GONE
                hintView.visibility = VISIBLE
                hintView.text = "再次点击以完成"
                bottomBar.visibility = GONE
            }

            VoiceState.Thinking -> {
                statusView.visibility = GONE
                hintView.visibility = GONE
                bottomBar.visibility = GONE
            }
        }
    }

    fun setActionLabel(label: String) {
        actionKey.text = label
    }

    fun showMessage(message: String) {
        statusView.visibility = VISIBLE
        statusView.text = message
        hintView.visibility = GONE
    }

    private fun circleKey(
        glyph: String,
        color: Int,
        onClick: (() -> Unit)?,
    ): TextView =
        TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(ICON_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            background = circleDrawable(color)
            isClickable = true
            setOnClickListener { onClick?.invoke() }
        }

    private fun pillKey(
        label: String,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ACTION_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = roundedDrawable(WHITE_KEY, dp(23))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun circleDrawable(color: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }

    /** 切换器里的一格；active 的那格铺一层浅灰底，跟 Typeless 一样。 */
    private fun tabCell(
        content: View,
        active: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): FrameLayout =
        FrameLayout(context).apply {
            if (active) background = roundedDrawable(TAB_ACTIVE, dp(15))
            isClickable = true
            setOnClickListener { onClick?.invoke() }
            addView(
                content,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    marginStart = dp(9)
                    marginEnd = dp(9)
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                },
            )
        }

    private fun labelCell(text: String): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

    private fun roundedDrawable(
        color: Int,
        radius: Int,
    ) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 面板高度（dp）。Typeless 的语音键盘区约 320dp，比 Trime 默认键盘（250dp）高。 */
        const val PANEL_HEIGHT_DP = 320

        /** 中间大按钮的方形画布：要能同时容下 148×56 的胶囊和直径 150 的录音圆+光晕。 */
        private const val ORB_BOX = 160

        /** 大按钮的中心距面板顶部。 */
        private const val ORB_CENTER = 159

        private const val BACKGROUND = 0xFFF1F1F1.toInt()
        private const val WHITE_KEY = 0xFFFFFFFF.toInt()
        private const val GRAY_KEY = 0xFFE4E4E4.toInt()
        private const val ICON_DARK = 0xFF3A3A3A.toInt()
        private const val ACTION_LABEL = 0xFF9A9A9A.toInt()
        private const val TEXT_PRIMARY = 0xFF111111.toInt()
        private const val TEXT_SECONDARY = 0xFF8A8A8A.toInt()
        private const val TAB_ACTIVE = 0xFFEDEDED.toInt()

        /** 顶部左侧品牌名（Typeless 那里是它的 logo）。 */
        private const val BRAND = "JackVoice"
    }
}

/**
 * 中间那颗大按钮。
 *
 * 空闲 = 黑胶囊 + 话筒；录音 = 黑圆 + 实时波形 + 光晕；处理中 = 进度胶囊。
 * 三种状态**共用同一个中心**，切换时不会跳动。
 *
 * 话筒比例按 Typeless 截图量得：本体 0.52×0.65 的全圆角胶囊，
 * 托是 0.40 半径的半圆（开口朝上、圆头线帽），线宽 0.12——托要够粗才不像「细弧配粗身」。
 */
class VoiceOrbView(
    context: Context,
) : View(context) {
    var state: VoiceState = VoiceState.Idle
        set(value) {
            field = value
            if (value == VoiceState.Recording) startAt = System.currentTimeMillis()
            invalidate()
        }

    private var startAt = 0L

    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ORB
        }

    private val white =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

    private val halo =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = HALO
        }

    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val unit = min(width, height).toFloat()
        when (state) {
            VoiceState.Idle -> drawPill(canvas, cx, cy, unit, PILL_W, PILL_H, true)
            VoiceState.Recording -> drawRecording(canvas, cx, cy, unit)
            VoiceState.Thinking -> drawPill(canvas, cx, cy, unit, PILL_W, PILL_H, false)
        }
    }

    /** 空闲/处理中共用的横胶囊；idle 时在里面画话筒。 */
    private fun drawPill(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
        wDp: Float,
        hDp: Float,
        withMic: Boolean,
    ) {
        val w = dp(wDp)
        val h = dp(hDp)
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = if (withMic) ORB else THINKING_BG
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)

        if (withMic) {
            drawMic(canvas, cx, cy, h * 0.60f)
            return
        }
        // 处理中：一道浅色进度从左扫到右
        val t = (System.currentTimeMillis() % 1400L) / 1400f
        val sweep = w * 0.55f
        val sx = rect.left - sweep + (w + sweep) * t
        canvas.save()
        canvas.clipRect(sx, rect.top, sx + sweep, rect.bottom)
        white.color = THINKING_PROGRESS
        canvas.drawRoundRect(rect, h / 2, h / 2, white)
        canvas.restore()
        textPaint.textSize = h * 0.30f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("Thinking", cx, baseline, textPaint)
        postInvalidateOnAnimation()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /**
     * 话筒：全圆角胶囊本体 + 开口朝上的半圆托 + 一截柄。
     * 托的圆心落在本体下部（0.43s 处），所以两臂从本体两侧环下来，正是 Typeless 的样子。
     */
    private fun drawMic(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        s: Float,
    ) {
        val bodyW = s * 0.52f
        val bodyH = s * 0.65f
        val top = cy - s * 0.465f
        white.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2, top, cx + bodyW / 2, top + bodyH),
            bodyW / 2,
            bodyW / 2,
            white,
        )
        val r = s * 0.40f
        val cradleCy = top + s * 0.43f
        stroke.strokeWidth = s * 0.12f
        canvas.drawArc(
            RectF(cx - r, cradleCy - r, cx + r, cradleCy + r),
            0f,
            180f,
            false,
            stroke,
        )
        canvas.drawLine(cx, cradleCy + r, cx, cradleCy + r + s * 0.10f, stroke)
    }

    /** 录音：直径 123dp 的黑圆 + 直径 150dp 的光晕 + 实时波形。 */
    private fun drawRecording(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
    ) {
        val r = dp(CIRCLE_R)
        canvas.drawCircle(cx, cy, dp(CIRCLE_R + HALO_GAP), halo)
        fill.color = ORB
        canvas.drawCircle(cx, cy, r, fill)

        val t = (System.currentTimeMillis() - startAt) / 1000f
        val gap = dp(14f)
        val maxH = dp(64f)
        val weights = floatArrayOf(0.42f, 0.72f, 1f, 0.66f, 0.38f)
        white.strokeWidth = dp(7f)
        white.strokeCap = Paint.Cap.ROUND
        for (i in weights.indices) {
            val phase = sin(t * 6.0 + i * 0.9).toFloat()
            val scale = 0.45f + 0.55f * abs(phase)
            val bh = maxH * weights[i] * scale
            val x = cx + (i - 2) * gap
            canvas.drawLine(x, cy - bh / 2, x, cy + bh / 2, white)
        }
        postInvalidateOnAnimation()
    }

    companion object {
        /** 空闲/处理中胶囊：与 Typeless 同为 145×60（实测它的黑体高 60dp、宽 135dp）。 */
        private const val PILL_W = 145f
        private const val PILL_H = 60f

        /** 录音黑圆直径 128dp，外面套一圈 14dp 的光晕。 */
        private const val CIRCLE_R = 64f
        private const val HALO_GAP = 14f

        private const val ORB = 0xFF111111.toInt()
        private const val HALO = 0xFFE8E8E8.toInt()
        private const val THINKING_BG = 0xFF2E2E2E.toInt()
        private const val THINKING_PROGRESS = 0xFF5C5C5C.toInt()
    }
}

/** 切换器里的语音标识：五根细竖条组成的小波形（对应 Typeless 那个波形图标）。 */
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
        val h = height.toFloat()
        val weights = floatArrayOf(0.45f, 0.80f, 1f, 0.70f, 0.40f)
        paint.strokeWidth = h * 0.13f
        val gap = h * 0.28f
        for (i in weights.indices) {
            val bh = h * weights[i]
            val x = cx + (i - 2) * gap
            canvas.drawLine(x, cy - bh / 2, x, cy + bh / 2, paint)
        }
    }
}
