/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.voice.VoicePanelView
import com.osfans.trime.ime.voice.VoiceWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.allInstances
import org.kodein.di.bindInstance
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    scope: ThemeScope,
) : BaseInputView(service, rime, scope),
    DIAware {
    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job

    override val di = DI {
        bindInstance<InputView> { this@InputView }
        bindInstance<ContextThemeWrapper> { themedContext }
        bindInstance<ThemeScope> { scope }
        bindInstance<Theme> { scope.theme }
        bindInstance<TrimeInputMethodService> { service }
        bindInstance<RimeSession> { rime }
        bindSingleton { InputBroadcaster() }
        bindSingleton { PopupDelegate(di) }
        bindSingleton { EnterKeyDisplayDelegate(di) }
        bindSingleton { PreeditDelegate(di) }
        bindSingleton { CommonKeyboardActionListener(di) }
        bindSingleton { BoardWindowManager(di) }
        bindSingleton { InputBarDelegate(di) }
        bindSingleton { CompactCandidateDelegate(di) }
        bindSingleton { KeyboardWindow(di) }
        bindSingleton { LiquidWindow(di) }
        bindSingleton { VoiceWindow(di) }
    }

    private val broadcaster: InputBroadcaster by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val preedit: PreeditDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()
    private val voiceWindow: VoiceWindow by instance()

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.style.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.style.keyboardPaddingLand
    private val keyboardBottomPadding = theme.style.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.style.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    /** 语音面板挂载中：此时键盘区用语音面板自己的高度，并忽略键盘高度的变化。 */
    private var voiceMode = false
    private var lastKeyboardHeightPx = 0
    private val voicePanelHeightPx: Int by lazy { dp(VoicePanelView.PANEL_HEIGHT_DP) }

    /**
     * 顶栏槽位：
     *   候选条**不再显示**。候选已经并进键盘自己的顶栏那一行（见 VoiceToolbarView），
     *   和 Typeless 一样——输入时候选顶替「品牌 + 胶囊」，不输入时换回来。
     *   原来那条独立的候选栏既和顶栏叠印，候选字号（22sp）又撑破 28dp 的行高。
     */
    private fun applyBarVisibility(isVoice: Boolean) {
        inputBar.view.visibility = View.GONE
        applyKeyboardViewHeight(isVoice)
    }

    /**
     * 整块键盘区的显式高度。
     *
     * 主题里的 `keyboard_height` 把窗口区锁死，ConstraintLayout 不会自己长高，
     * 所以这里显式给：语音面板用面板自己的高度，打字键盘用键盘窗口报上来的高度
     *（键盘窗口已经把顶部工具条算进自己的高度里了，见 KeyboardWindow）。
     */
    private fun applyKeyboardViewHeight(isVoice: Boolean = voiceMode) {
        if (!this::keyboardView.isInitialized) return
        if (keyboardView.layoutParams == null) return
        keyboardView.updateLayoutParams {
            height = if (isVoice) voicePanelHeightPx else lastKeyboardHeightPx
        }
    }

    private fun broadcastKeyAppearanceUpdate() {
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
            applyBarVisibility(voiceMode)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    /** 整块键盘区（含顶栏槽位）。init 里赋值，之后高度由 applyKeyboardViewHeight 管。 */
    lateinit var keyboardView: View

    /** Restyles colors after a scheme switch without rebuilding the view tree. */
    fun refreshColors() {
        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")
        popup.refreshColors()
        keyboardWindow.refreshColors()
        inputBar.refreshColors()
        preedit.refreshColors()
        windowManager.refreshColors()
    }

    init {
        // MUST call before any operation
        val receivers: List<InputBroadcastReceiver> by allInstances()
        receivers.forEach { broadcaster.addReceiver(it) }

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        windowManager.cacheResidentWindow(voiceWindow, createView = true)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        // 语音面板占据整块键盘区时：
        //   ① 收起顶部的候选/工具条（对齐 Typeless 的干净画面，GONE 后 ConstraintLayout
        //      会把窗口区上提到父容器顶部）；
        //   ② 把键盘区加高到 Typeless 的尺度（它的语音面板约 320dp，Trime 默认键盘只有 250dp）。
        broadcaster.addReceiver(
            object : InputBroadcastReceiver {
                override fun onWindowAttached(window: BoardWindow) {
                    val isVoice = window === voiceWindow
                    voiceMode = isVoice
                    applyBarVisibility(isVoice)
                    windowManager.view.updateLayoutParams {
                        height = if (isVoice) voicePanelHeightPx else lastKeyboardHeightPx
                    }
                    applyKeyboardViewHeight(isVoice)
                    if (isVoice) {
                        // 从拼音键盘切过来时把没打完的拼写清掉：不清的话候选和预编辑会
                        // 残留在语音面板下面透出来（面板顶栏是透明的）。
                        rime.launchOnReady { it.clearComposition() }
                    }
                }
            },
        )

        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")

        keyboardView =
            constraintLayout {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
            }

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    lastKeyboardHeightPx = it
                    // 语音面板挂载时不受键盘高度影响（它有自己的一套高度）
                    if (!voiceMode) {
                        windowManager.view.updateLayoutParams {
                            height = it
                        }
                        applyKeyboardViewHeight(isVoice = false)
                    }
                }
            }

        updateKeyboardSize()

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )

        add(
            keyboardView,
            lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            },
        )

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )
    }

    private fun updateKeyboardSize() {
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        val unset = LayoutParams.UNSET
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        inputBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // 系统给输入法的可见高度里已经扣掉导航栏了，这里不要再留一次边距——
        // 多留一个导航栏的高度会把整块键盘往上顶。
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        applyKeyboardViewHeight()
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            // 对标 Typeless：语音面板就是**主键盘**——点进任何输入框先看到「点击说话」。
            // 要打字时按面板左下角的键盘键切到拼音键盘；键盘右下角的「语音」键切回来。
            windowManager.attachWindow(voiceWindow)
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    /** 行内预编辑变了：顶栏左边那截拼写跟着变。 */
    fun onComposingTextChanged(text: String) {
        keyboardWindow.onComposingText(text)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                // 正在用语音面板时别被切回键盘——切方案不该把用户踢出语音
                if (!windowManager.isAttached(voiceWindow)) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }

            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
            }

            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }

            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    override fun onDetachedFromWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        broadcaster.clear()
        super.onDetachedFromWindow()
    }
}
