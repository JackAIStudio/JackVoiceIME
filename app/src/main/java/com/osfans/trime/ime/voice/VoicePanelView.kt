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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
 *                       动作键上方一格是 ⌫；键面内缩 3.5×6dp、圆角 13dp
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
    var onActionTap: (() -> Unit)? = null
    var onRetryTap: (() -> Unit)? = null

    /** 重新识别时的「取消」：不能让人对着进度干等。 */
    var onRetryCancel: (() -> Unit)? = null

    /** 点左上角的「云端 / 本地」方块。窗口负责下载、落盘、提示。 */
    var onEngineTap: (() -> Unit)? = null

    /** 预览卡片上的「插入」/「丢弃」。 */
    var onPreviewCommit: (() -> Unit)? = null
    var onPreviewDiscard: (() -> Unit)? = null

    /** 顶部切换胶囊，语音面板与打字键盘共用同一个组件。 */
    val toolbar =
        VoiceToolbarView(context).apply {
            onVoiceTap = { /* 已经在语音面板上 */ }
        }

    private var state = VoiceState.Idle

    private var engine = AsrEngine.Cloud

    /**
     * 左上角的引擎方块。只做一件事：告诉用户这一轮会走云端还是本地，
     * 点一下交给 [VoiceWindow] 切换。不做下拉菜单——两颗状态不值得再开一层。
     *
     * 放左上角而不是右上角：右边要留给「拼 / EN / 收起」那一排胶囊，
     * 再加一颗会挤；左边品牌下面本来就是空的，56dp 方块正好落在
     * 麦克风圆左侧，不抢触摸。
     */
    private val engineView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(JvColors.text)
            background = roundedDrawable(JvColors.keyBg, dp(16))
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onEngineTap?.invoke()
            }
        }

    private val statusView =
        TextView(context).apply {
            setTextColor(JvColors.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

    /**
     * 空闲态中央那句提示。**面板中央只有 [statusView] 一个 TextView**，
     * 它显示哪一句只由 [renderCenterStatus] 决定。
     *
     * 以前这里和 `retryingView` 是两个叠在同一坐标上的 TextView，
     * `showMessage()`、`setRetrying()`、`setState()` 各写各的可见性，
     * 结果就是截图里「点击说话」「正在重新识别…」和提示文字糊成一团。
     */
    private var centerMessage: String? = null

    /** 重新识别是否真的在跑。和视图分开存，避免用 visibility 反推状态。 */
    private var retryingBusy = false

    private var retryingProgress = 0f

    /** 触控板提示是否正占着面板中央。 */
    private var trackpadActive = false

    /** 录音时网络断掉的原因；提示条恢复时要靠它决定说哪一句。 */
    private var asrDegraded = false

    private var asrNotice = ""

    private val clearCenterMessage =
        Runnable {
            centerMessage = null
            renderCenterStatus()
            refreshRecordingHint()
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

    /**
     * 上一轮识别失败时，右上角那颗「重新识别」键。
     *
     * 位置是键区第 0 行第 2 列——和中间那颗大胶囊同一水平高度、在它右边，
     * 也就是底栏本来就空着的那一格。**规格和 ⌫ 一样**：同一套键面底色、13dp 圆角、
     * 同一格内缩；图标用正文色，不额外上蓝色——整块面板本来就只有黑白灰。
     *
     * 录音已经在本地存着了，用户不用为此专门跑一趟历史页。
     * 只在 [VoiceState.Idle] 且确实有可重试的录音时显示。
     */
    private val retryIcon =
        ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_refresh_reversed_24)
            setColorFilter(JvColors.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(11)
            setPadding(pad, pad, pad, pad)
        }

    private val retryView =
        FrameLayout(context).apply {
            background = retryKeyBackground()
            isClickable = true
            visibility = GONE
            addView(
                retryIcon,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onRetryTap?.invoke()
            }
            contentDescription = "重新识别上一段录音"
        }

    private val orb =
        VoiceOrbView(context).apply {
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onOrbTap?.invoke()
            }
        }

    // ── 重新识别：进度与预览 ────────────────────────────────────────────

    /**
     * 重新识别没有进度、也没有终点的承诺；给一颗明确的「取消」，
     * 用户不用等 30 秒看门狗，也不用去猜麦克风为什么不见了。
     */
    private val retryCancelView =
        TextView(context).apply {
            text = "取消"
            setTextColor(JvColors.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(16), 0, dp(16), 0)
            background = roundedDrawable(JvColors.keyBg, dp(13))
            isClickable = true
            visibility = GONE
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onRetryCancel?.invoke()
            }
        }

    private val previewText =
        TextView(context).apply {
            setTextColor(JvColors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.START
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            // 一段话可能很长：卡片自己滚，别把面板撑破。
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * 预览卡片：**只读**的文字 + 两个决定。
     *
     * 不在卡片上编辑是有意的——改字该在输入框里改。在面板上做一套编辑，
     * 就要和 Rime 的组合输入抢状态，收益远小于成本。
     */
    private val previewCard =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(JvColors.keyBg, dp(18))
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(10))
            visibility = GONE
            isClickable = true

            addView(
                TextView(context).apply {
                    setTextColor(JvColors.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    includeFontPadding = false
                    text = "识别好了，要插入吗"
                },
            )
            // 文字自己滚，按钮钉在卡片底部：一段话再长也不能把按钮顶出可视区，
            // 更不能让卡片长到压住下面的键区。
            addView(
                ScrollView(context).apply {
                    isFillViewport = false
                    addView(previewText)
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(6)
                },
            )

            val buttons =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
            buttons.addView(previewButton("丢弃", JvColors.textSecondary) { onPreviewDiscard?.invoke() })
            buttons.addView(previewButton("插入", JvColors.accent) { onPreviewCommit?.invoke() })
            addView(
                buttons,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                },
            )
        }

    /** 卡片上的一个小按钮：和标点菜单里那套观感一致，不要用 Material 按钮。 */
    private fun previewButton(
        label: String,
        color: Int,
        onClick: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onClick()
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
            // 第 0 行右列一直是空的，正好和胶囊同高：重试键放这儿，
            // 规格与 ⌫ 一致，也不会像压在麦克风画布上那样被抢走点击。
            addKey(retryView, row = 0, column = 2)
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
            engineView,
            LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = dp(58)
                marginStart = dp(15)
            },
        )

        addView(
            statusView,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(22)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(97)
            },
        )
        // 进度和「点击说话」共用上面那一个 statusView，绝不能再叠第二个 TextView。
        addView(
            retryCancelView,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(126)
            },
        )

        addView(
            previewCard,
            // 预览是「决定时刻」：这期间麦克风和底栏都没有意义，
            // 让卡片占满顶栏以下、底部让位以上的整块面板。
            //
            // 中间那块（顶栏 51 到键区 123）只有 70dp，塞不下几行字，
            // 按钮还会被裁掉——试过，卡片矮得只剩标题和半截按钮。
            // 既然这一屏的**唯一任务**就是「要不要这段字」，就该给它整块地方。
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(PREVIEW_TOP_DP)
                bottomMargin = dp(BOTTOM_SAFE_DP)
                leftMargin = dp(KEY_SIDE_PADDING_DP + 4)
                rightMargin = dp(KEY_SIDE_PADDING_DP + 4)
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

        setEngine(AsrEngine.Cloud)
        setState(VoiceState.Idle)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 面板切到拼音键盘再切回来时，标点菜单/遮罩可能停在动画半路：
        // 一个 alpha=0 但仍是 VISIBLE 的遮罩会吃掉顶栏和整块面板的触摸。
        hidePunctuationMenuNow()
        // 每次挂上窗口都重新算一遍刷新键。
        //
        // 以前这颗键只在「状态变化」时由 VoiceWindow 推一次。可面板一直开着、
        // 状态没变（比如重新识别失败后仍是 Idle），就再没人推——键该出现时不出现
        // （实测历史里有 9 条可重试，面板上却没有入口）。挂在窗口上这个时机
        // 是可靠的：每次键盘展开、每次从拼音切回来都会走。
        onAttachedSync?.invoke()
    }

    /** 窗口挂上时请 VoiceWindow 重新同步一次（刷新键等）。 */
    var onAttachedSync: (() -> Unit)? = null

    fun setState(state: VoiceState) {
        if (state != VoiceState.Idle) {
            resetTrackpad()
            hidePunctuationMenuNow()
            // 录音 / 等终稿时用户已经表达了别的意图：预览卡片和进度都收掉。
            previewCard.visibility = GONE
            retryingBusy = false
            retryCancelView.visibility = GONE
            centerMessage = null
            removeCallbacks(clearCenterMessage)
        }
        this.state = state
        // 麦克风可能因为预览卡片让过位，任何状态切换都先把它要回来；
        // 真正该藏的时候（预览卡片开着）由 setPreviewText / setRetrying 再收。
        orb.visibility = VISIBLE
        shrinkOrbHitArea(state == VoiceState.Idle)
        orb.state = state
        when (state) {
            VoiceState.Idle -> {
                hintView.visibility = GONE
                bottomBar.visibility = VISIBLE
                clearPreview()
                orb.level = 0f
            }

            VoiceState.Recording -> {
                retryView.visibility = GONE
                hintView.visibility = VISIBLE
                bottomBar.visibility = GONE
                // 新一次录音从空白开始，别把上一次的预览留在屏幕上。
                clearPreview()
                orb.level = 0f
                refreshRecordingHint()
            }

            VoiceState.Thinking -> {
                retryView.visibility = GONE
                hintView.visibility = GONE
                bottomBar.visibility = GONE
                orb.level = 0f
            }
        }
        // 中央提示只有一个出口：状态、重识别、临时提示、触控板都汇到这里。
        renderCenterStatus()
        // 状态一变就重算一次刷新键：它同时受「状态」和「有没有可重试录音」两个条件约束，
        // 以前只有外部显式推的时候才更新，漏推一次就再也不出现。
        applyRetryVisibility()
    }

    /**
     * 有没有可以重试的录音。空闲时在麦克风下面亮出「重新识别这段录音」，
     * 录音 / 处理中一律收起来——那两种状态下这句话还没轮到最后一步。
     */
    fun setRetryAvailable(available: Boolean) {
        retryAvailable = available
        applyRetryVisibility()
    }

    /**
     * 这颗键显不显示，只取决于两件事：**有没有可重试的录音**、**面板是不是空闲**。
     *
     * 拆成 `applyRetryVisibility()` 是因为它以前只被外部在「状态变化」时推一次：
     * 面板一直开着、状态没变（比如重新识别失败之后又变回 Idle），
     * 就没人再推了——键该出现的时候不出现。现在状态一变、可用性一变都重新算一遍。
     */
    private fun applyRetryVisibility() {
        retryView.visibility = if (retryAvailable && state == VoiceState.Idle) VISIBLE else GONE
    }

    /** 有没有可重试的录音。 */
    private var retryAvailable = false

    /**
     * 重新识别的预览卡片：把「待插入的文字」摆在面板上，由用户决定要不要。
     *
     * 为什么要有这张卡片，而不是识别完直接上屏：重新转写是补救路径，
     * 等好几秒是常态，结果也未必是用户想要的。直接塞进输入框会打断
     * 已经打的内容，而且没法反悔。这里把决定权交回去，代价只是多点一下。
     *
     * @param text null 表示收起卡片（正在跑 / 已处理 / 用户丢弃）
     */
    fun setPreviewText(text: String?) {
        val shown = text?.trim().orEmpty()
        if (shown.isEmpty()) {
            previewCard.visibility = GONE
            // 卡片收起：空闲态那套（麦克风 + 底栏）摆回来。
            if (state == VoiceState.Idle) {
                orb.visibility = VISIBLE
                bottomBar.visibility = VISIBLE
                renderCenterStatus()
            }
            return
        }
        if (state != VoiceState.Idle) return
        previewText.text = shown
        previewCard.visibility = VISIBLE
        previewCard.scrollTo(0, 0)
        // 卡片占满整块面板，麦克风、提示、底栏统统让位：
        // 这一屏的唯一任务就是「要不要这段字」，别让别的东西分心或挡住。
        orb.visibility = GONE
        bottomBar.visibility = GONE
        renderCenterStatus()
    }

    /** 正在重新识别：面板上给一句进度，而不是让用户对着「点击说话」等。 */
    fun setRetrying(
        busy: Boolean,
        progress: Float = 0f,
    ) {
        if (state != VoiceState.Idle) return
        retryingBusy = busy
        retryingProgress = progress.coerceIn(0f, 1f)
        retryCancelView.visibility = if (busy) VISIBLE else GONE
        if (busy) {
            // 跑的时候把麦克风、提示、底栏都让开，只留进度。
            previewCard.visibility = GONE
            orb.visibility = GONE
            bottomBar.visibility = GONE
        } else {
            // 跑完了：以**预览卡片**为准决定谁露面。
            // 卡片开着就整块让给它，否则把空闲态那套摆回来。
            val hasPreview = previewCard.visibility == VISIBLE
            orb.visibility = if (hasPreview) GONE else VISIBLE
            bottomBar.visibility = if (hasPreview) GONE else VISIBLE
        }
        renderCenterStatus()
    }

    /**
     * 面板中央唯一的渲染入口。
     *
     * 优先级固定为：预览卡片 > 重新识别 > 触控板 > 临时提示 > 当前状态。
     * 同一时刻**只会有一个** `TextView` 可见，这样无论回调以什么顺序到达，
     * 都不会再出现两句话叠在一起。
     */
    private fun renderCenterStatus() {
        val text =
            VoiceCenterStatus.resolve(
                state = state,
                previewVisible = previewCard.visibility == VISIBLE,
                retrying = retryingBusy,
                retryProgress = retryingProgress,
                trackpadActive = trackpadActive,
                message = centerMessage,
            )
        statusView.text = text.orEmpty()
        statusView.visibility = if (text == null) GONE else VISIBLE
    }

    /**
     * 这一轮只录了音、没有识别（没网 / 连接中途断了）。
     *
     * 只改录音态的那句提示：**麦克风还在开着，别动状态、别收底栏**。
     * 用户得知道「字不会实时出来，但这段话会被存下来」，
     * 而不是对着一个不出字的面板猜是不是坏了。
     */
    fun setAsrDegraded(degraded: Boolean, notice: String = "") {
        asrDegraded = degraded
        asrNotice = notice
        if (state != VoiceState.Recording) return
        refreshRecordingHint()
    }

    /** 录音态下方那句提示。预览气泡出现时让它先收起，气泡消失后要能回来。 */
    private fun refreshRecordingHint() {
        if (state != VoiceState.Recording) return
        hintView.text =
            if (asrDegraded) {
                val reason = asrNotice.ifBlank { "网络没连上" }
                "$reason，先录着 · 再次点击完成"
            } else {
                "再次点击以完成"
            }
        hintView.visibility =
            if (previewView.visibility == VISIBLE || trackpadActive) GONE else VISIBLE
    }

    /**
     * 麦克风那块的 **View 边界** 跟着画出来的形状走。
     *
     * 画布一直是 160dp 见方（录音圆 + 光晕要那么大），但空闲时只画一颗 56dp 高的胶囊。
     * 边界不收，下面那颗「重新识别这段录音」就落在这块画布里，点上去触发的是录音
     * ——实测踩过一次。空闲时把边界收到胶囊本身，两个按钮就真的分开了。
     */
    private fun shrinkOrbHitArea(idle: Boolean) {
        val box = dp(ORB_BOX)
        val height = if (idle) dp(VoiceOrbView.PILL_H) else box
        val top = if (idle) dp(ORB_CENTER) - height / 2 else dp(ORB_CENTER - ORB_BOX / 2)
        val lp = orb.layoutParams as? LayoutParams ?: return
        if (lp.height == height && lp.topMargin == top) return
        lp.height = height
        lp.topMargin = top
        orb.layoutParams = lp
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
        // 就在它正上方，同时显示会挤在一起。预览清空后提示要自己回来，
        // 不能像以前那样一收就永远不显示。
        refreshRecordingHint()
    }

    private fun clearPreview() {
        previewView.text = ""
        previewView.visibility = GONE
    }

    fun setActionLabel(label: String) {
        actionKey.text = label
    }

    /** 窗口展开 / 切回面板时同步引擎。 */
    fun setEngine(engine: AsrEngine) {
        this.engine = engine
        refreshEngineLabel()
    }

    private fun refreshEngineLabel() {
        engineView.text = engine.label
        engineView.setTextColor(JvColors.text)
        engineView.contentDescription = "识别引擎：${engine.label}，点按切换"
        engineView.background = roundedDrawable(JvColors.keyBg, dp(16))
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
        // 上一次菜单可能还在收起动画里；先停掉，免得收尾回调把刚打开的菜单又设成 GONE。
        punctuationScrim.animate().cancel()
        punctuationMenu.animate().cancel()
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
        val menuVisible = punctuationMenu.visibility == VISIBLE
        val scrimVisible = punctuationScrim.visibility == VISIBLE
        // 菜单已经没了、遮罩却还留着，是动画被打断后的残留：它看不见但会吃掉
        // 整块面板的触摸（顶栏的「收起键盘」首当其冲）。这里一并收掉。
        if (!menuVisible && !scrimVisible) return
        if (menuVisible) {
            punctuationMenu
                .animate()
                .alpha(0f)
                .translationY(dp(8).toFloat())
                .setDuration(100)
                .withEndAction {
                    punctuationMenu.visibility = GONE
                    // 遮罩跟着菜单一起收：不再依赖它自己的动画回调是否被取消。
                    punctuationScrim.visibility = GONE
                }.start()
        }
        if (scrimVisible) {
            punctuationScrim
                .animate()
                .alpha(0f)
                .setDuration(100)
                .withEndAction { punctuationScrim.visibility = GONE }
                .start()
        }
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
        refreshEngineLabel()
        statusView.setTextColor(JvColors.textSecondary)
        retryCancelView.setTextColor(JvColors.accent)
        retryCancelView.background = roundedDrawable(JvColors.keyBg, dp(13))
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
        retryIcon.setColorFilter(JvColors.text)
        retryView.background = retryKeyBackground()
        orb.refreshColors()
    }

    /**
     * 一行临时提示。只写入 [centerMessage]，由 [renderCenterStatus] 统一决定
     * 它能不能露出来——重新识别、预览卡片、录音态都比它优先。
     *
     * 以前这里直接 `statusView.visibility = VISIBLE`，于是「正在重新识别…」刚
     * 显示出来就被这句提示按回同一层，两句话叠在一起；录音时还会把
     * 「再次点击以完成」收掉再也不还回来。现在提示会自己过期，
     * 过期后回到当前状态该显示的那一句。
     */
    fun showMessage(message: String) {
        removeCallbacks(clearCenterMessage)
        centerMessage = message
        if (state == VoiceState.Idle) {
            clearPreview()
        }
        renderCenterStatus()
        refreshRecordingHint()
        postDelayed(clearCenterMessage, MESSAGE_DURATION_MS)
    }

    /**
     * 底部键面：白底、13dp 圆角，和打字键盘的 `KeyView` 用同一套配色。
     *
     * 注意：回调必须**在点击那一刻**才去读属性，不能在构造时把值传进来——
     * 这些回调是 `onCreateView` 之后才被 VoiceWindow 赋上的，构造时全是 null，
     * 传值捕获会把它们永久钉死成 null（左下角 / ⌫ 两个键点了没反应就是这个原因）。
     */
    private fun keyLabel(
        label: String,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(JvColors.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
        trackpadActive = active
        bottomBar.animate().cancel()
        trackpadHint.animate().cancel()
        if (active) {
            bottomBar.animate().alpha(0.22f).setDuration(120).start()
            trackpadHint.visibility = VISIBLE
            trackpadHint.animate().alpha(1f).setDuration(90).start()
        } else {
            bottomBar.animate().alpha(1f).setDuration(120).start()
            trackpadHint.animate().alpha(0f).setDuration(90).withEndAction {
                if (trackpadHint.alpha == 0f) trackpadHint.visibility = GONE
            }.start()
        }
        renderCenterStatus()
        refreshRecordingHint()
    }

    /** 切走或开始录音时把触控板状态立即收干净，避免底栏停在半透明。 */
    private fun resetTrackpad() {
        trackpadActive = false
        bottomBar.animate().cancel()
        bottomBar.alpha = 1f
        trackpadHint.animate().cancel()
        trackpadHint.alpha = 0f
        trackpadHint.visibility = GONE
        renderCenterStatus()
        refreshRecordingHint()
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

    /**
     * 刷新键的键面。
     *
     * **不能只用 [JvColors.keyBg]**：浅色模式下它是纯白 `#FFFFFF`，
     * 而面板背景是 `#F1F1F1`——对比度只有 6%，肉眼几乎看不见。
     * 那颗键其实一直在、也一直能点（实测点它确实会触发重新识别），
     * 只是用户看不到，于是以为「根本没有重新转写按钮」。
     *
     * 这里补一圈描边把它从底色里托出来：浅色用淡灰边，深色用亮一点的边，
     * 两种模式下都还是黑白灰，不破坏面板的克制感。
     */
    private fun retryKeyBackground() =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(KEY_CORNER_DP).toFloat()
            setColor(JvColors.keyBg)
            setStroke(dp(1), JvColors.divider)
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

        /** 临时提示显示多久。过期后中央回到「点击说话」或当前进度。 */
        private const val MESSAGE_DURATION_MS = 2600L

        /**
         * 重新识别预览卡片的位置与高度（dp）。
         *
         * 面板 320dp = 顶栏区 + 键区。底栏占 KEY_ROW_DP*3 = 177dp，
         * 再加 BOTTOM_SAFE_DP 20dp，所以**键区上沿在 320-20-177 = 123dp**。
         * 卡片从顶栏下沿（11+36+4=51）一直铺到键区上沿之前，
         * 中间这 68dp 全是空白，正好给它用。
         *
         * 以前按 84dp 起算，只剩 35dp 高，长一点的文字就把卡片撑到键区上，
         * 压住 ⌫（实测那颗键点不动）。高度必须**封死**，文字多了在卡片里滚。
         */
        private const val PREVIEW_TOP_DP = 51
        private const val PREVIEW_MAX_HEIGHT_DP = 123 - PREVIEW_TOP_DP - 4

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
        const val PILL_W = 145f
        const val PILL_H = 60f

        /** 录音黑圆直径 128dp，外面套一圈 14dp 的光晕。 */
        private const val CIRCLE_R = 64f
        private const val HALO_GAP = 14f

        /** 音量的起落速度（每秒逼近比例）。起得快才跟得上说话，落得慢一点不闪。 */
        private const val ATTACK_PER_SEC = 22f
        private const val RELEASE_PER_SEC = 5f
    }
}
