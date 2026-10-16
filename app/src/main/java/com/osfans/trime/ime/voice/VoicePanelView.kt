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
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

enum class VoiceState { Idle, Recording, Thinking }

/**
 * 语音面板，对齐 Typeless 的形态：
 * 中间一颗大按钮（空闲=胶囊麦克风，录音=圆+波形，处理中=进度胶囊），底部一行功能键。
 * 整块区域就是输入法的键盘区——不是悬浮窗，也不抢宿主输入框的焦点。
 */
class VoicePanelView(
    context: Context,
) : LinearLayout(context) {
    var onOrbTap: (() -> Unit)? = null
    var onKeyboardTap: (() -> Unit)? = null
    var onBackspaceTap: (() -> Unit)? = null
    var onActionTap: (() -> Unit)? = null

    private val statusView =
        TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }

    private val hintView =
        TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }

    private val orb =
        VoiceOrbView(context).apply {
            isClickable = true
            setOnClickListener { onOrbTap?.invoke() }
        }

    private val actionKey =
        pillKey("") { onActionTap?.invoke() }

    init {
        setBackgroundColor(BACKGROUND)
        orientation = VERTICAL

        val centerArea =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                addView(statusView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                addView(
                    orb,
                    LayoutParams(dp(124), dp(124)).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(8)
                    },
                )
                addView(hintView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
        addView(centerArea, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomBar =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(circleKey("\u2328") { onKeyboardTap?.invoke() }, LayoutParams(dp(48), dp(48)))
                addView(View(context), LayoutParams(0, 1, 1f))
                addView(
                    actionKey,
                    LayoutParams(dp(132), dp(48)).apply { marginEnd = dp(10) },
                )
                addView(circleKey("\u232B") { onBackspaceTap?.invoke() }, LayoutParams(dp(48), dp(48)))
            }
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(14)
                marginStart = dp(18)
                marginEnd = dp(18)
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
                actionKey.isEnabled = true
            }

            VoiceState.Recording -> {
                statusView.visibility = GONE
                hintView.visibility = VISIBLE
                hintView.text = "再次点击以完成"
                actionKey.isEnabled = false
            }

            VoiceState.Thinking -> {
                statusView.visibility = GONE
                hintView.visibility = GONE
                actionKey.isEnabled = false
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
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = roundedDrawable(KEY_BACKGROUND, dp(24))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun pillKey(
        label: String,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedDrawable(KEY_BACKGROUND, dp(24))
            isClickable = true
            setOnClickListener { onClick() }
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
        private const val BACKGROUND = 0xFFF1F1F1.toInt()
        private const val KEY_BACKGROUND = 0xFFFFFFFF.toInt()
        private const val TEXT_PRIMARY = 0xFF111111.toInt()
        private const val TEXT_SECONDARY = 0xFF8A8A8A.toInt()
    }
}

/**
 * 中间那颗大按钮。空闲是黑胶囊 + 麦克风；录音是黑圆 + 实时波形 + 外圈光晕；处理中是「Thinking」进度胶囊。
 * 所有尺寸都按视图实际大小换算，面板高矮变化时不会溢出。
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
            VoiceState.Idle -> drawIdle(canvas, cx, cy, unit)
            VoiceState.Recording -> drawRecording(canvas, cx, cy, unit)
            VoiceState.Thinking -> drawThinking(canvas, cx, cy, unit)
        }
    }

    private fun drawIdle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
    ) {
        val w = unit * 0.96f
        val h = unit * 0.40f
        fill.color = ORB
        canvas.drawRoundRect(RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), h / 2, h / 2, fill)
        drawMic(canvas, cx, cy, unit * 0.30f)
    }

    private fun drawRecording(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
    ) {
        val r = unit * 0.42f
        canvas.drawCircle(cx, cy, r * 1.12f, halo)
        fill.color = ORB
        canvas.drawCircle(cx, cy, r, fill)

        // 实时波形：五根竖条，中间高两边低，随时间起伏
        val t = (System.currentTimeMillis() - startAt) / 1000f
        val gap = unit * 0.086f
        val maxH = unit * 0.50f
        val weights = floatArrayOf(0.42f, 0.72f, 1f, 0.66f, 0.38f)
        white.strokeWidth = unit * 0.045f
        white.strokeCap = Paint.Cap.ROUND
        for (i in weights.indices) {
            val phase = sin(t * 6.0 + i * 0.9).toFloat()
            val scale = 0.45f + 0.55f * abs(phase)
            val h = maxH * weights[i] * scale
            val x = cx + (i - 2) * gap
            canvas.drawLine(x, cy - h / 2, x, cy + h / 2, white)
        }
        postInvalidateOnAnimation()
    }

    private fun drawThinking(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        unit: Float,
    ) {
        val w = unit * 0.96f
        val h = unit * 0.40f
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        fill.color = THINKING_BG
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)

        val t = (System.currentTimeMillis() % 1400L) / 1400f
        val sweep = w * 0.55f
        val sx = rect.left - sweep + (w + sweep) * t
        canvas.save()
        canvas.clipRect(sx, rect.top, sx + sweep, rect.bottom)
        white.color = THINKING_PROGRESS
        canvas.drawRoundRect(rect, h / 2, h / 2, white)
        canvas.restore()

        textPaint.textSize = unit * 0.145f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText("Thinking", cx, baseline, textPaint)
        postInvalidateOnAnimation()
    }

    private fun drawMic(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
    ) {
        val bodyW = size * 0.46f
        val bodyH = size * 0.72f
        val bodyTop = cy - size * 0.50f
        white.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2, bodyTop, cx + bodyW / 2, bodyTop + bodyH),
            bodyW / 2,
            bodyW / 2,
            white,
        )
        stroke.strokeWidth = size * 0.10f
        val cradle = size * 0.60f
        canvas.drawArc(
            RectF(cx - cradle, cy - size * 0.28f, cx + cradle, cy + size * 0.60f),
            0f,
            180f,
            false,
            stroke,
        )
        canvas.drawLine(cx, cy + size * 0.60f, cx, cy + size * 0.84f, stroke)
    }

    companion object {
        private const val ORB = 0xFF111111.toInt()
        private const val HALO = 0xFFE6E6E6.toInt()
        private const val THINKING_BG = 0xFF2E2E2E.toInt()
        private const val THINKING_PROGRESS = 0xFF5C5C5C.toInt()
    }
}
