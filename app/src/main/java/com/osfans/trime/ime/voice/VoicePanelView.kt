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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class VoiceState { Idle, Recording, Thinking }

/**
 * 语音面板。所有位置都是从 Typeless 的截图里量出来的（面板按 320dp 计）：
 *
 *   「点击说话」        中心 108dp
 *   中间那颗大按钮      中心 159dp（空闲胶囊 148×56 / 录音圆直径 123、光晕 150）
 *   流式预览气泡        顶端 242dp、最多两行（只在录音/处理时出现）
 *   「再次点击以完成」   顶端 294dp（只在录音且还没有预览时出现）
 *   底部工具栏          左圆 x=45dp、右圆距右 42dp（⌫ 在 @ 上方 61dp）、中间胶囊 112×46
 *
 * 录音与处理态**整条底栏收起**——和 Typeless 一样，录音时画面只剩那颗大圆。
 */
class VoicePanelView(
    context: Context,
) : FrameLayout(context) {
    var onOrbTap: (() -> Unit)? = null

    /** 左下角：弹出系统输入法选择器（初期还要用别的输入法打字）。 */
    var onImeSwitchTap: (() -> Unit)? = null
    var onBackspaceTap: (() -> Unit)? = null
    var onAtTap: (() -> Unit)? = null
    var onActionTap: (() -> Unit)? = null

    /** 顶部切换胶囊，语音面板与打字键盘共用同一个组件。 */
    val toolbar =
        VoiceToolbarView(context).apply {
            onVoiceTap = { /* 已经在语音面板上 */ }
        }

    private var state = VoiceState.Idle

    private val statusView =
        TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

    private val hintView =
        TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }

    /**
     * 流式识别预览：说话时把一遍结果实时显示在面板里，用户不用等上屏才知道听到了什么。
     *
     * 位置在录音圆和「再次点击以完成」之间。只留两行，太长就**保留最后一段**——
     * 边说话边滚，用户关心的是刚说出口的那几个字，不是开头。
     */
    private val previewView =
        TextView(context).apply {
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
            minHeight = dp(42)
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = roundedDrawable(WHITE_KEY, dp(16))
            // 气泡跟着文字走，但最多占屏幕宽度减去两侧留白，不能顶到屏幕边。
            maxWidth = resources.displayMetrics.widthPixels - dp(80)
            visibility = GONE
        }

    private val orb =
        VoiceOrbView(context).apply {
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onOrbTap?.invoke()
            }
        }

    private val actionKey = pillKey("") { onActionTap?.invoke() }

    private val bottomBar =
        FrameLayout(context).apply {
            addView(
                globeKey { onImeSwitchTap?.invoke() },
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
                circleKey("@", GRAY_KEY) { onAtTap?.invoke() },
                LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = dp(18)
                    bottomMargin = dp(16)
                },
            )
            addView(
                repeatCircleKey("\u232B", GRAY_KEY) { onBackspaceTap?.invoke() },
                LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = dp(18)
                    bottomMargin = dp(77)
                },
            )
        }

    init {
        setBackgroundColor(BACKGROUND)

        addView(
            toolbar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(VoiceToolbarView.HEIGHT_DP)).apply {
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
            previewView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(242)
            },
        )
        addView(
            hintView,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(18)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(294)
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
        this.state = state
        orb.state = state
        when (state) {
            VoiceState.Idle -> {
                statusView.visibility = VISIBLE
                statusView.text = "点击说话"
                hintView.visibility = GONE
                bottomBar.visibility = VISIBLE
                clearPreview()
                orb.level = 0f
            }

            VoiceState.Recording -> {
                statusView.visibility = GONE
                hintView.visibility = VISIBLE
                hintView.text = "再次点击以完成"
                bottomBar.visibility = GONE
                // 新一次录音从空白开始，别把上一次的预览留在屏幕上。
                clearPreview()
                orb.level = 0f
            }

            VoiceState.Thinking -> {
                statusView.visibility = GONE
                hintView.visibility = GONE
                bottomBar.visibility = GONE
                orb.level = 0f
            }
        }
    }

    /** 实时音量（0..1）。只有录音时波形才跟着走，其他状态一律落地。 */
    fun setLevel(level: Float) {
        orb.level = if (state == VoiceState.Recording) level.coerceIn(0f, 1f) else 0f
    }

    /** 流式识别的一段预览。空串就收起气泡；太长的只留最后一段。 */
    fun setPreview(text: String) {
        if (state == VoiceState.Idle) return
        val trimmed = text.trim()
        val shown =
            if (trimmed.length > PREVIEW_MAX_CHARS) {
                "…" + trimmed.takeLast(PREVIEW_MAX_CHARS - 1)
            } else {
                trimmed
            }
        previewView.text = shown
        val visible = shown.isNotEmpty()
        previewView.visibility = if (visible) VISIBLE else GONE
        // 有实时文字时把「再次点击以完成」让出来：两行预览更重要，而且这一格
        // 就在它正上方，同时显示会挤在一起。
        if (visible) hintView.visibility = GONE
    }

    private fun clearPreview() {
        previewView.text = ""
        previewView.visibility = GONE
    }

    fun setActionLabel(label: String) {
        actionKey.text = label
    }

    fun showMessage(message: String) {
        statusView.visibility = VISIBLE
        statusView.text = message
        hintView.visibility = GONE
        clearPreview()
    }

    /**
     * 底部圆形小键。
     *
     * 注意：回调必须**在点击那一刻**才去读属性，不能在构造时把值传进来——
     * 这些回调是 `onCreateView` 之后才被 VoiceWindow 赋上的，构造时全是 null，
     * 传值捕获会把它们永久钉死成 null（地球 / ⌫ / @ 三个键点了没反应就是这个原因）。
     */
    private fun circleKey(
        glyph: String,
        color: Int,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = glyph
        gravity = Gravity.CENTER
        setTextColor(ICON_DARK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        background = circleDrawable(color)
        isClickable = true
        setOnClickListener {
            InputFeedbackManager.keyPressVibrate(this)
            onClick()
        }
    }

    private fun pillKey(
        label: String,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(ACTION_LABEL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = roundedDrawable(WHITE_KEY, dp(23))
        isClickable = true
        setOnClickListener {
            InputFeedbackManager.keyPressVibrate(this)
            onClick()
        }
    }

    /**
     * 按住能连删的圆形键。
     *
     * 语音面板这颗 ⌫ 原来只挂了 `setOnClickListener`，按多久都只删一个字；
     * 拼音/英文键盘的 ⌫ 是 `KeyView`，继承 [GestureFrame] 的 `isRepeatable`。
     * 这里直接复用同一个 [GestureFrame]：长按延迟、连发间隔、震动都跟打字键盘一致，
     * 不再手写一套定时器。
     */
    private fun repeatCircleKey(
        glyph: String,
        color: Int,
        onRepeat: () -> Unit,
    ): FrameLayout = GestureFrame(context).apply {
        background = circleDrawable(color)
        isClickable = true
        isRepeatable = true
        onClick = onRepeat
        addView(
            TextView(context).apply {
                text = glyph
                gravity = Gravity.CENTER
                setTextColor(ICON_DARK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** 左下角那颗「切换输入法」：用画的地球，避免 emoji 字体的色块。回调同上，点击时才读。 */
    private fun globeKey(onClick: () -> Unit): FrameLayout = FrameLayout(context).apply {
        background = circleDrawable(WHITE_KEY)
        isClickable = true
        setOnClickListener {
            InputFeedbackManager.keyPressVibrate(this)
            onClick()
        }
        addView(
            GlobeGlyphView(context),
            LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER },
        )
    }

    private fun circleDrawable(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color)
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

        /** 预览气泡最多留多少字；再多就取最后一段，两行放得下。 */
        private const val PREVIEW_MAX_CHARS = 30

        private const val BACKGROUND = 0xFFF1F1F1.toInt()
        private const val WHITE_KEY = 0xFFFFFFFF.toInt()
        private const val GRAY_KEY = 0xFFE4E4E4.toInt()
        private const val ICON_DARK = 0xFF3A3A3A.toInt()
        private const val ACTION_LABEL = 0xFF111111.toInt()
        private const val TEXT_PRIMARY = 0xFF111111.toInt()
        private const val TEXT_SECONDARY = 0xFF8A8A8A.toInt()
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
            if (value == VoiceState.Recording) {
                startAt = System.currentTimeMillis()
                displayedLevel = 0f
                lastFrameNanos = 0L
            }
            invalidate()
        }

    /** 实时音量 0..1，由 [VoicePanelView.setLevel] 喂进来。 */
    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (state == VoiceState.Recording) invalidate()
        }

    private var startAt = 0L

    /** 画出来的音量：对收到的 level 做快起慢落，10Hz 的采样看起来才不跳。 */
    private var displayedLevel = 0f
    private var lastFrameNanos = 0L

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
            VoiceState.Recording -> drawRecording(canvas, cx, cy)
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
    ) {
        // 快起慢落：说话时 50ms 内顶起来，停嘴后慢慢落回小点。
        val now = System.nanoTime()
        val dt =
            if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            }
        lastFrameNanos = now
        val speed = if (level > displayedLevel) ATTACK_PER_SEC else RELEASE_PER_SEC
        displayedLevel += (level - displayedLevel) * (1f - exp(-speed * dt))
        if (abs(level - displayedLevel) < 0.004f) displayedLevel = level

        val r = dp(CIRCLE_R)
        // 开方只影响观感：正常说话也明显能顶起来，静音仍然是 0。
        val visual = sqrt(displayedLevel)
        canvas.drawCircle(cx, cy, dp(CIRCLE_R + HALO_GAP) + dp(3f) * visual, halo)
        fill.color = ORB
        canvas.drawCircle(cx, cy, r, fill)

        val t = (System.currentTimeMillis() - startAt) / 1000f
        val gap = dp(14f)
        val maxH = dp(64f)
        val minH = dp(5f)
        val weights = floatArrayOf(0.42f, 0.72f, 1f, 0.66f, 0.38f)
        white.strokeWidth = dp(7f)
        white.strokeCap = Paint.Cap.ROUND
        for (i in weights.indices) {
            // 起伏只在有声时出现；静音时 displayedLevel = 0，五根都停在最小高度，
            // 不会像以前那样不管说不说话都自顾自地跳。
            val wobble = 1f + 0.16f * sin(t * 7.0 + i * 1.15).toFloat() * visual
            val full = maxH * weights[i] * wobble
            val bh = minH + (full - minH) * visual
            val x = cx + (i - 2) * gap
            canvas.drawLine(x, cy - bh / 2, x, cy + bh / 2, white)
        }
        // 有声音（或正在回落）才继续要帧；静音时停在静止的小点上，不白烧电。
        if (level > 0.001f || displayedLevel > 0.001f) postInvalidateOnAnimation()
    }

    companion object {
        /** 空闲/处理中胶囊：与 Typeless 同为 145×60（实测它的黑体高 60dp、宽 135dp）。 */
        private const val PILL_W = 145f
        private const val PILL_H = 60f

        /** 录音黑圆直径 128dp，外面套一圈 14dp 的光晕。 */
        private const val CIRCLE_R = 64f
        private const val HALO_GAP = 14f

        /** 音量的起落速度（每秒逼近比例）。起得快才跟得上说话，落得慢一点不闪。 */
        private const val ATTACK_PER_SEC = 22f
        private const val RELEASE_PER_SEC = 5f

        private const val ORB = 0xFF111111.toInt()
        private const val HALO = 0xFFE8E8E8.toInt()
        private const val THINKING_BG = 0xFF2E2E2E.toInt()
        private const val THINKING_PROGRESS = 0xFF5C5C5C.toInt()
    }
}

/** 简笔地球：外圈 + 一道竖椭圆 + 一条横线。用来表示「切换输入法」。 */
class GlobeGlyphView(
    context: Context,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF3A3A3A.toInt()
            strokeCap = Paint.Cap.ROUND
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - paint.strokeWidth
        paint.strokeWidth = r * 0.16f
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawLine(cx - r, cy, cx + r, cy, paint)
        val rect = android.graphics.RectF(cx - r * 0.46f, cy - r, cx + r * 0.46f, cy + r)
        canvas.drawArc(rect, 0f, 360f, false, paint)
    }
}
