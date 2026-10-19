/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ui.jackvoice.JvColors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 语音面板。所有位置都是从 Typeless 的截图里量出来的（面板按 320dp 计）：
 *
 *   「点击说话」        中心 108dp
 *   中间那颗大按钮      中心 159dp（空闲胶囊 148×56 / 录音圆直径 123、光晕 150）
 *   流式预览气泡        顶端 242dp、最多两行（只在录音/处理时出现）
 *   「再次点击以完成」   顶端 294dp（只在录音且还没有预览时出现）
 *   底部键区            与拼音/EN 共用四行网格：标点 / 空格 / 动作键（15% / 70% / 15%），
 *                       动作键上方一格是 ⌫，再上一格是 @；键面内缩 3.5×6dp、圆角 13dp
 *
 * 录音与处理态**整条底栏收起**——和 Typeless 一样，录音时画面只剩那颗大圆。
 */
class VoicePanelView(
    context: Context,
) : FrameLayout(context) {
    var onOrbTap: (() -> Unit)? = null

    /**
     * 左下角：点按循环三档，长按弹出三选一菜单直接跳。
     * 三档都在客户端生效，窗口只负责把新档位落盘。
     */
    var onPunctuationModeChange: ((PunctuationMode) -> Unit)? = null
    var onSpaceTap: (() -> Unit)? = null
    var onSpaceSlide: ((dx: Int, dy: Int) -> Unit)? = null
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
            setTextColor(JvColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

    private val hintView =
        TextView(context).apply {
            setTextColor(JvColors.textSecondary)
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
            setTextColor(JvColors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 2
            minHeight = dp(42)
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            background = roundedDrawable(JvColors.keyBg, dp(16))
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

    /**
     * 空格长按进入触控板时的提示。和打字键盘的提示同一套外观，
     * 不接收触摸，拖动事件仍由空格键的 [GestureFrame] 处理。
     */
    private val trackpadHint =
        TextView(context).apply {
            setText(R.string.keyboard_trackpad_hint)
            setTextColor(JvColors.trackpadHintText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = roundedDrawable(JvColors.trackpadHintBg, dp(18))
            elevation = dp(6).toFloat()
            isClickable = false
            isFocusable = false
            isLongClickable = false
            isSoundEffectsEnabled = false
            visibility = GONE
            alpha = 0f
        }

    /** 需要跟着外观模式重新取色的键（键面 + 文字），刷新时统一过一遍。 */
    private val themedKeys = mutableListOf<Pair<View, TextView?>>()

    private val actionKey = keyLabel("") { onActionTap?.invoke() }

    // ── 左下角「标点」键 ────────────────────────────────────────────────

    private val punctuationValue =
        TextView(context).apply {
            text = PunctuationMode.Full.label
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(JvColors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }

    private var punctuationMode = PunctuationMode.Full

    private val punctuationKey =
        FrameLayout(context).apply {
            background = roundedDrawable(JvColors.keyBg, dp(KEY_CORNER_DP))
            isClickable = true
            isLongClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                cyclePunctuation()
            }
            setOnLongClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                showPunctuationMenu()
                true
            }
            addView(
                punctuationValue,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }

    private data class PunctuationMenuRow(
        val mode: PunctuationMode,
        val view: LinearLayout,
        val check: TextView,
        val title: TextView,
        val hint: TextView,
    )

    private val punctuationMenuRows = mutableListOf<PunctuationMenuRow>()

    private val punctuationMenu = buildPunctuationMenu().apply { visibility = GONE }

    /**
     * 切换档位时在键上方冒一下的提示。Toast 在输入法窗口里会被键盘盖住，
     * 所以用面板内的一小块气泡，位置就在标点键正上方。
     */
    private val punctuationNotice =
        TextView(context).apply {
            setTextColor(JvColors.trackpadHintText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = roundedDrawable(JvColors.trackpadHintBg, dp(12))
            visibility = GONE
            alpha = 0f
            isClickable = false
        }

    private val hidePunctuationNotice =
        Runnable {
            punctuationNotice
                .animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction { punctuationNotice.visibility = GONE }
                .start()
        }

    private val punctuationScrim =
        View(context).apply {
            setBackgroundColor(0x2E000000)
            isClickable = true
            setOnClickListener { hidePunctuationMenu() }
            visibility = GONE
        }

    /**
     * 底行与打字键盘同一套坐标：标点在 123 的位置、空格在中缝、动作键在右下角，
     * ⌫ 和 @ 顺着右列往上排。切到拼音/EN 时右下角那颗键不跳位置。
     */
    private val bottomBar =
        VoiceKeyBar(context).apply {
            addKey(punctuationKey, row = 2, column = 0)
            addKey(spaceKey { onSpaceTap?.invoke() }, row = 2, column = 1)
            addKey(actionKey, row = 2, column = 2)
            addKey(repeatKey("\u232B") { onBackspaceTap?.invoke() }, row = 1, column = 2)
            addKey(keyLabel("@", textSizeSp = 20f) { onAtTap?.invoke() }, row = 0, column = 2)
        }

    init {
        setBackgroundColor(JvColors.keyboardBg)

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
            trackpadHint,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(90)
            },
        )
        addView(
            bottomBar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(KEY_ROW_DP * 3)).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(BOTTOM_SAFE_DP)
            },
        )
        // 长按标点键时盖在面板上：先铺一层可点的半透明遮罩，再把菜单卡片放在上面。
        addView(
            punctuationNotice,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(KEY_SIDE_PADDING_DP + 4)
                bottomMargin = dp(BOTTOM_SAFE_DP + KEY_ROW_DP + 8)
            },
        )
        addView(punctuationScrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            punctuationMenu,
            LayoutParams(dp(236), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = dp(KEY_SIDE_PADDING_DP + 4)
                bottomMargin = dp(BOTTOM_SAFE_DP + KEY_ROW_DP + 8)
            },
        )

        setState(VoiceState.Idle)
    }

    fun setState(state: VoiceState) {
        if (state != VoiceState.Idle) {
            resetTrackpad()
            hidePunctuationMenuNow()
        }
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

    /** 窗口展开 / 切回面板时同步当前档位；键面文字和强调色一起更新。 */
    fun setPunctuationMode(mode: PunctuationMode) {
        punctuationMode = mode
        punctuationValue.text = mode.label
        punctuationValue.setTextColor(
            if (mode == PunctuationMode.Full) JvColors.text else JvColors.accent,
        )
        punctuationKey.contentDescription = "标点：${mode.label}。点按切换，长按直选"
    }

    private fun cyclePunctuation() {
        val next = punctuationMode.next()
        setPunctuationMode(next)
        onPunctuationModeChange?.invoke(next)
        announcePunctuation(next)
    }

    private fun selectPunctuation(mode: PunctuationMode) {
        setPunctuationMode(mode)
        onPunctuationModeChange?.invoke(mode)
        hidePunctuationMenu()
        announcePunctuation(mode)
    }

    private fun announcePunctuation(mode: PunctuationMode) {
        punctuationNotice.removeCallbacks(hidePunctuationNotice)
        punctuationNotice.animate().cancel()
        punctuationNotice.text = mode.label
        punctuationNotice.visibility = VISIBLE
        punctuationNotice.alpha = 1f
        punctuationNotice.postDelayed(hidePunctuationNotice, 1100)
    }

    /**
     * 长按菜单：三档全列出来，点哪档直接跳，不用循环。
     * 菜单是一个盖在面板里的卡片，不新开 PopupWindow——输入法窗口里再弹窗口容易拿不到 token。
     */
    private fun buildPunctuationMenu(): LinearLayout {
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(JvColors.keyBg, dp(18))
                elevation = dp(10).toFloat()
                setPadding(dp(8), dp(8), dp(8), dp(8))
                // 点卡片空白处不穿透到后面的遮罩，只有卡片外才关闭。
                isClickable = true
            }
        for (mode in PunctuationMode.entries) {
            val check =
                TextView(context).apply {
                    text = "✓"
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(JvColors.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    alpha = 0f
                }
            val title =
                TextView(context).apply {
                    text = mode.label
                    includeFontPadding = false
                    setTextColor(JvColors.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }
            val hint =
                TextView(context).apply {
                    text = mode.hint
                    includeFontPadding = false
                    setTextColor(JvColors.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                }
            val labels =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(hint)
                }
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), 0, dp(10), 0)
                    isClickable = true
                    background = ripple()
                    contentDescription = "${mode.label}，${mode.hint}"
                    setOnClickListener {
                        InputFeedbackManager.keyPressVibrate(this)
                        selectPunctuation(mode)
                    }
                    addView(check, LinearLayout.LayoutParams(dp(30), LayoutParams.MATCH_PARENT))
                    addView(labels, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
            punctuationMenuRows += PunctuationMenuRow(mode, row, check, title, hint)
            card.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        }
        return card
    }

    private fun showPunctuationMenu() {
        if (state != VoiceState.Idle) return
        punctuationNotice.removeCallbacks(hidePunctuationNotice)
        punctuationNotice.animate().cancel()
        punctuationNotice.visibility = GONE
        updatePunctuationChecks()
        punctuationScrim.visibility = VISIBLE
        punctuationScrim.alpha = 0f
        punctuationMenu.visibility = VISIBLE
        punctuationMenu.alpha = 0f
        punctuationMenu.translationY = dp(10).toFloat()
        punctuationScrim.animate().alpha(1f).setDuration(120).start()
        punctuationMenu.animate().alpha(1f).translationY(0f).setDuration(140).start()
    }

    private fun hidePunctuationMenu() {
        if (punctuationMenu.visibility != VISIBLE) return
        punctuationMenu
            .animate()
            .alpha(0f)
            .translationY(dp(8).toFloat())
            .setDuration(100)
            .withEndAction { punctuationMenu.visibility = GONE }
            .start()
        punctuationScrim
            .animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction { punctuationScrim.visibility = GONE }
            .start()
    }

    private fun hidePunctuationMenuNow() {
        punctuationScrim.animate().cancel()
        punctuationMenu.animate().cancel()
        punctuationScrim.visibility = GONE
        punctuationMenu.visibility = GONE
        punctuationNotice.removeCallbacks(hidePunctuationNotice)
        punctuationNotice.animate().cancel()
        punctuationNotice.visibility = GONE
    }

    private fun updatePunctuationChecks() {
        for (row in punctuationMenuRows) {
            row.check.alpha = if (row.mode == punctuationMode) 1f else 0f
        }
    }

    private fun ripple(): Drawable {
        val tint = (JvColors.text and 0x00FFFFFF) or 0x1F000000
        return RippleDrawable(ColorStateList.valueOf(tint), null, null)
    }

    /** 外观模式变化后重新取色，不重建视图；录音动画和当前状态都不受影响。 */
    fun refreshColors() {
        setBackgroundColor(JvColors.keyboardBg)
        toolbar.refreshColors()
        statusView.setTextColor(JvColors.textSecondary)
        hintView.setTextColor(JvColors.textSecondary)
        previewView.setTextColor(JvColors.text)
        previewView.background = roundedDrawable(JvColors.keyBg, dp(16))
        trackpadHint.setTextColor(JvColors.trackpadHintText)
        trackpadHint.background = roundedDrawable(JvColors.trackpadHintBg, dp(18))
        themedKeys.forEach { (key, label) ->
            key.background = roundedDrawable(JvColors.keyBg, dp(KEY_CORNER_DP))
            label?.setTextColor(JvColors.text)
        }
        punctuationKey.background = roundedDrawable(JvColors.keyBg, dp(KEY_CORNER_DP))
        punctuationValue.setTextColor(
            if (punctuationMode == PunctuationMode.Full) JvColors.text else JvColors.accent,
        )
        punctuationMenu.background = roundedDrawable(JvColors.keyBg, dp(18))
        punctuationNotice.setTextColor(JvColors.trackpadHintText)
        punctuationNotice.background = roundedDrawable(JvColors.trackpadHintBg, dp(12))
        for (row in punctuationMenuRows) {
            row.view.background = ripple()
            row.check.setTextColor(JvColors.accent)
            row.title.setTextColor(JvColors.text)
            row.hint.setTextColor(JvColors.textSecondary)
        }
        orb.refreshColors()
    }

    fun showMessage(message: String) {
        statusView.visibility = VISIBLE
        statusView.text = message
        hintView.visibility = GONE
        clearPreview()
    }

    /**
     * 底部键面：白底、13dp 圆角，和打字键盘的 `KeyView` 用同一套配色。
     *
     * 注意：回调必须**在点击那一刻**才去读属性，不能在构造时把值传进来——
     * 这些回调是 `onCreateView` 之后才被 VoiceWindow 赋上的，构造时全是 null，
     * 传值捕获会把它们永久钉死成 null（左下角 / ⌫ / @ 三个键点了没反应就是这个原因）。
     */
    private fun keyLabel(
        label: String,
        textSizeSp: Float = 14f,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(JvColors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        background = roundedDrawable(JvColors.keyBg, dp(KEY_CORNER_DP))
        isClickable = true
        setOnClickListener {
            InputFeedbackManager.keyPressVibrate(this)
            onClick()
        }
        themedKeys += this to this
    }

    /**
     * 空格键：和拼音/EN 一样，长按进入触控板。
     *
     * 轻点仍然按一次空格；长按后拖动走 [onSpaceSlide]，松手不再补空格。
     * [GestureFrame] 已经处理好长按延迟、步进和取消，这里只负责接回调。
     */
    private fun spaceKey(onTap: () -> Unit): FrameLayout {
        val label =
            TextView(context).apply {
                text = "空格"
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(JvColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        val key =
            GestureFrame(context).apply {
                background = roundedDrawable(JvColors.keyBg, dp(KEY_CORNER_DP))
                isClickable = true
                isTrackpad = true
                onClick = onTap
                onSlide = { dx, dy, _, _ ->
                    if (dx != 0 || dy != 0) onSpaceSlide?.invoke(dx, dy)
                }
                onTrackpadState = { active -> setTrackpadActive(active) }
                addView(
                    label,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            }
        themedKeys += key to label
        return key
    }

    /** 长按空格：底栏淡出，浮出「移动光标」。和打字键盘的触控板提示一致。 */
    private fun setTrackpadActive(active: Boolean) {
        bottomBar.animate().cancel()
        trackpadHint.animate().cancel()
        if (active) {
            bottomBar.animate().alpha(0.22f).setDuration(120).start()
            statusView.visibility = GONE
            trackpadHint.visibility = VISIBLE
            trackpadHint.animate().alpha(1f).setDuration(90).start()
        } else {
            bottomBar.animate().alpha(1f).setDuration(120).start()
            statusView.visibility = if (state == VoiceState.Idle) VISIBLE else GONE
            trackpadHint.animate().alpha(0f).setDuration(90).withEndAction {
                if (trackpadHint.alpha == 0f) trackpadHint.visibility = GONE
            }.start()
        }
    }

    /** 切走或开始录音时把触控板状态立即收干净，避免底栏停在半透明。 */
    private fun resetTrackpad() {
        bottomBar.animate().cancel()
        bottomBar.alpha = 1f
        trackpadHint.animate().cancel()
        trackpadHint.alpha = 0f
        trackpadHint.visibility = GONE
        statusView.visibility = if (state == VoiceState.Idle) VISIBLE else GONE
    }

    override fun onDetachedFromWindow() {
        resetTrackpad()
        hidePunctuationMenuNow()
        super.onDetachedFromWindow()
    }

    /**
     * 按住能连删的键。
     *
     * 语音面板这颗 ⌫ 原来只挂了 `setOnClickListener`，按多久都只删一个字；
     * 拼音/英文键盘的 ⌫ 是 `KeyView`，继承 [GestureFrame] 的 `isRepeatable`。
     * 这里直接复用同一个 [GestureFrame]：长按延迟、连发间隔、震动都跟打字键盘一致，
     * 不再手写一套定时器。
     */
    private fun repeatKey(
        glyph: String,
        onRepeat: () -> Unit,
    ): FrameLayout {
        val label =
            TextView(context).apply {
                text = glyph
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(JvColors.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
        val key =
            GestureFrame(context).apply {
                background = roundedDrawable(JvColors.keyBg, dp(KEY_CORNER_DP))
                isClickable = true
                isRepeatable = true
                onClick = onRepeat
                addView(
                    label,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
                )
            }
        themedKeys += key to label
        return key
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

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 语音面板底部的三行键区。
     *
     * 几何直接抄 [KeyboardWindow] 的按键区：左右各留 6dp、列宽 15%/70%/15%、
     * 行高 59dp（236dp 的四等分）、键面四周再内缩 3.5dp/6dp。
     * 这样语音、拼音、EN 三屏的右下角动作键在同一个像素上。
     */
    private class KeyCell(
        val view: View,
        val row: Int,
        val column: Int,
    )

    private inner class VoiceKeyBar(
        context: Context,
    ) : ViewGroup(context) {
        private val cells = mutableListOf<KeyCell>()

        init {
            setPadding(dp(KEY_SIDE_PADDING_DP), 0, dp(KEY_SIDE_PADDING_DP), 0)
        }

        fun addKey(
            content: View,
            row: Int,
            column: Int,
        ) {
            val slot =
                FrameLayout(context).apply {
                    setPadding(dp(KEY_GAP_X_DP), dp(KEY_GAP_Y_DP), dp(KEY_GAP_X_DP), dp(KEY_GAP_Y_DP))
                    addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }
            cells += KeyCell(slot, row, column)
            addView(slot)
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val innerWidth = width - paddingLeft - paddingRight
            val side = (innerWidth * KEY_COLUMN_PERCENT).toInt()
            val center = innerWidth - side * 2
            val rowHeight = dp(KEY_ROW_DP)
            for (cell in cells) {
                val cellWidth = if (cell.column == 1) center else side
                cell.view.measure(
                    MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(width, rowHeight * 3)
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            val innerWidth = right - left - paddingLeft - paddingRight
            val side = (innerWidth * KEY_COLUMN_PERCENT).toInt()
            val center = innerWidth - side * 2
            val rowHeight = dp(KEY_ROW_DP)
            val col0 = paddingLeft
            val col1 = col0 + side
            val col2 = col1 + center
            for (cell in cells) {
                val x =
                    when (cell.column) {
                        0 -> col0
                        1 -> col1
                        else -> col2
                    }
                val cellWidth = if (cell.column == 1) center else side
                val y = cell.row * rowHeight
                cell.view.layout(x, y, x + cellWidth, y + rowHeight)
            }
        }
    }

    companion object {
        /** 面板高度（dp）。Typeless 的语音键盘区约 320dp，比 Trime 默认键盘（250dp）高。 */
        const val PANEL_HEIGHT_DP = 320

        /** 中间大按钮的方形画布：要能同时容下 148×56 的胶囊和直径 150 的录音圆+光晕。 */
        private const val ORB_BOX = 160

        /** 大按钮的中心距面板顶部。 */
        private const val ORB_CENTER = 159

        /** 预览气泡最多留多少字；再多就取最后一段，两行放得下。 */
        private const val PREVIEW_MAX_CHARS = 30

        /** 与打字键盘的四行网格对齐：236dp / 4 = 59dp；底行下面再留 20dp 给手势条。 */
        private const val KEY_ROW_DP = 59
        private const val KEY_SIDE_PADDING_DP = 6
        private const val KEY_COLUMN_PERCENT = 0.15f
        private const val KEY_GAP_X_DP = 3.5f
        private const val KEY_GAP_Y_DP = 6f
        private const val KEY_CORNER_DP = 13
        private const val BOTTOM_SAFE_DP = 20
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
            color = JvColors.orbBg
        }

    private val white =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = JvColors.orbFg
        }

    private val halo =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = JvColors.orbHalo
        }

    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = JvColors.orbFg
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = JvColors.thinkingText
            textAlign = Paint.Align.CENTER
        }

    /** 外观模式变化后重新取色；正在录音的波形按新底色继续画。 */
    fun refreshColors() {
        fill.color = JvColors.orbBg
        white.color = JvColors.orbFg
        halo.color = JvColors.orbHalo
        stroke.color = JvColors.orbFg
        textPaint.color = JvColors.thinkingText
        invalidate()
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
        fill.color = if (withMic) JvColors.orbBg else JvColors.thinkingBg
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
        white.color = JvColors.thinkingProgress
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
        white.color = JvColors.orbFg
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2, top, cx + bodyW / 2, top + bodyH),
            bodyW / 2,
            bodyW / 2,
            white,
        )
        val r = s * 0.40f
        val cradleCy = top + s * 0.43f
        stroke.color = JvColors.orbFg
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
        fill.color = JvColors.orbBg
        canvas.drawCircle(cx, cy, r, fill)

        val t = (System.currentTimeMillis() - startAt) / 1000f
        val gap = dp(14f)
        val maxH = dp(64f)
        val minH = dp(5f)
        val weights = floatArrayOf(0.42f, 0.72f, 1f, 0.66f, 0.38f)
        white.color = JvColors.orbFg
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
    }
}
