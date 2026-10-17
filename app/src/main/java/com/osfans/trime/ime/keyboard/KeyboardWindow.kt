// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.voice.CandidatePanelView
import com.osfans.trime.ime.voice.VoiceTab
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.voice.VoiceToolbarView
import com.osfans.trime.ime.voice.VoiceWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class KeyboardWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val rime: RimeSession by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val boardWindowManager: BoardWindowManager by instance()
    private val voiceWindow: VoiceWindow by instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    /** 键盘自带顶栏：品牌 + 🎙|拼|EN 胶囊。整块键盘 = 顶栏 + 按键。 */
    private lateinit var toolbar: VoiceToolbarView

    /** 按键区（含左右留白），整页候选面板叠在它上面。 */
    private lateinit var keyArea: FrameLayout
    private lateinit var candidatePanel: CandidatePanelView
    private var lastCandidates: Array<CandidateProto> = emptyArray()
    private var candidatesExpanded = false

    private val toolbarHeightPx: Int get() = context.dp(VoiceToolbarView.HEIGHT_DP)

    /** 顶栏上方的留白。语音面板也用同一个值，两边切来切去胶囊不会上下跳。 */
    private val toolbarTopMarginPx: Int get() = context.dp(VoiceToolbarView.TOP_MARGIN_DP)

    companion object : ResidentWindow.Key {
        lateinit var currentKeyboard: Keyboard

        /** 顶栏与按键之间的间隔（Typeless 那一条透气缝）。 */
        private const val TOOLBAR_GAP_DP = 9

        /** 按键与屏幕底之间给手势条让的位。 */
        private const val BOTTOM_GAP_DP = 20

        /** 按键区左右留白，Typeless 的键不贴着屏幕边。 */
        private const val KEY_SIDE_PADDING_DP = 6
    }

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val presetKeyboardIds = theme.presetKeyboards.keys.toList()
    private var currentKeyboardId = ""
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val activeKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    private val keyboardActionListener = commonKeyboardActionListener.listener

    private var lastIsPortrait: Boolean? = null
    private var containerWidth: Int = 0
    private var allowedWidth: Int = 0

    private val onKeyboardViewLayoutChangeListener =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && allowedWidth != width) {
                val isPortrait = !context.resources.configuration.isLandscape()
                lastIsPortrait = isPortrait
                containerWidth = width
                allowedWidth = width
                v.post { refreshKeyboards() }
            }
        }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onKeyboardViewLayoutChangeListener)

        toolbar =
            VoiceToolbarView(context).also { bar ->
                bar.onVoiceTap = { boardWindowManager.attachWindow(voiceWindow) }
                bar.onPinyinTap = {
                    service.postRimeJob { setRuntimeOption("ascii_mode", false) }
                    bar.setActive(VoiceTab.Pinyin)
                }
                bar.onEnglishTap = {
                    service.postRimeJob { setRuntimeOption("ascii_mode", true) }
                    bar.setActive(VoiceTab.English)
                }
                bar.onCandidatePick = { index ->
                    rime.launchOnReady { it.selectCandidate(index, global = true) }
                }
                bar.onExpandTap = { toggleCandidatePanel() }
            }

        attachKeyboard(evalKeyboard(".default"))

        candidatePanel =
            CandidatePanelView(context).apply {
                visibility = View.GONE
                onPick = { index ->
                    collapseCandidatePanel()
                    rime.launchOnReady { it.selectCandidate(index, global = true) }
                }
            }

        // 按键区：左右各留 6dp（Typeless 的键不贴屏幕边），整页候选面板叠在上面
        keyArea =
            FrameLayout(context).apply {
                setPadding(context.dp(KEY_SIDE_PADDING_DP), 0, context.dp(KEY_SIDE_PADDING_DP), 0)
            }
        keyArea.addView(
            keyboardView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        keyArea.addView(
            candidatePanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // 顶栏放在键盘自己的布局里，而不是塞到 InputView 的顶栏槽位——
        // 后者会和 preedit 争高度，实测会把工具条顶出窗口上沿切掉一截。
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                View(context),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toolbarTopMarginPx,
                ),
            )
            addView(
                toolbar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toolbarHeightPx,
                ),
            )
            addView(
                View(context),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(TOOLBAR_GAP_DP),
                ),
            )
            addView(
                keyArea,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                View(context),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dp(BOTTOM_GAP_DP),
                ),
            )
        }
    }

    /** 键盘高度报给外面时要带上顶栏、缝和底部让位，否则 InputView 会少给这一截。 */
    private fun reportHeight(keysHeight: Int) {
        val chrome =
            toolbarTopMarginPx +
                toolbarHeightPx +
                context.dp(TOOLBAR_GAP_DP) +
                context.dp(BOTTOM_GAP_DP)
        runBlocking { _currentKeyboardHeight.emit(keysHeight + chrome) }
    }

    private fun detachCurrentView() {
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        activeKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
    }

    /** 计算键盘可用宽度：优先使用已测量的容器宽度，否则回退到系统窗口测量。 */
    private fun computeAllowedWidth(): Int {
        val isPortrait = !context.resources.configuration.isLandscape()

        if (containerWidth > 0 && lastIsPortrait == isPortrait) {
            return containerWidth
        }

        val padding = theme.style.run {
            if (context.isLandscapeMode()) keyboardPaddingLand else keyboardPadding
        }

        val safeWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = context.windowManager.maximumWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val displayWidth = context.resources.displayMetrics.widthPixels
            val windowWidth = windowMetrics.bounds.width() - insets.left - insets.right
            if (windowWidth < displayWidth - context.dp(1)) displayWidth else windowWidth
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            context.windowManager.defaultDisplay.getSize(size)
            size.x
        }

        val width = safeWidth - 2 * context.dp(padding)
        allowedWidth = width
        return width
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? {
        val config = theme.presetKeyboards[name] ?: theme.presetKeyboards["default"]
        val importPreset = config?.importPreset
        if (!importPreset.isNullOrEmpty()) {
            return selectKeyboardConfig(importPreset)
        }
        return config
    }

    private fun attachKeyboard(target: String) {
        currentKeyboardId = target
        lastKeyboardId = target

        val config = selectKeyboardConfig(target)
        val keyboard = activeKeyboard ?: Keyboard(context, theme, computeAllowedWidth(), config)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        if (activeKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        keyboard.also {
            reportHeight(it.keyboardHeight)
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val currentMode = rime.run { statusCached }.isAsciiMode
            val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode

            if (currentMode != targetMode) {
                service.postRimeJob {
                    commitComposition()
                    setRuntimeOption("ascii_mode", targetMode)
                }
            }

            currentKeyboard = it
        }

        view.let {
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                add(it, lParams(matchParent, matchParent))
            }
        }
    }

    private fun smartMatchKeyboard(): String {
        // 主题的布局中包含方案id，直接采用
        val currentSchema = rime.run { statusCached }.schemaId
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        val alphabet = rime.run { schemaCached }.alphabet
        val layout =
            when {
                alphabet.all { it.isLetter() } -> "qwerty"

                // 包含 26 个字母
                alphabet.all { it.isLetter() || ",./;".any(it::equals) } -> "qwerty_"

                // 包含 26 个字母和,./;
                alphabet.all { it.isLetterOrDigit() } -> "qwerty0"

                // 包含 26 个字母和数字键
                else -> "default"
            }
        return if (presetKeyboardIds.contains(layout)) layout else "default"
    }

    private fun evalKeyboard(id: String): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (id) {
                ".default" -> smartMatchKeyboard()

                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId

                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId

                ".last" -> lastKeyboardId

                ".last_lock" -> lastLockKeyboardId

                ".ascii" -> {
                    var ascii = activeKeyboard?.asciiKeyboard
                    if (ascii.isNullOrEmpty()) {
                        ascii = lastLockKeyboardId
                    }
                    if (presetKeyboardIds.contains(ascii)) ascii else currentKeyboardId
                }

                else -> {
                    id.ifEmpty {
                        if (activeKeyboard?.isLock == true) currentKeyboardId else lastLockKeyboardId
                    }
                }
            }
        var final = dot.ifEmpty { smartMatchKeyboard() }

        // 切换到横屏布局
        if (service.isLandscapeMode()) {
            val landscape =
                theme.presetKeyboards[final]?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        }
        return final
    }

    fun switchKeyboard(to: String) {
        val target = evalKeyboard(to)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                if (target == currentKeyboardId) return@execute
            }
            detachCurrentView()
            attachKeyboard(target)
        }
        Timber.d("Switched to keyboard: $target")
    }

    fun refreshKeyboards(isAll: Boolean = false) {
        val id = currentKeyboardId.ifEmpty { return }
        detachCurrentView()
        if (isAll) {
            cachedKeyboards.clear()
        } else {
            cachedKeyboards.remove(id)
        }
        attachKeyboard(id)
    }

    /** Repaints the keyboard after a color-scheme switch; keys re-resolve their colors. */
    override fun refreshColors() {
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onStartInput(info: EditorInfo) {
        setComposingToolbar(false)
        collapseCandidatePanel()
        val targetKeyboard =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"

                else -> {
                    when (info.inputType and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> "number"

                        InputType.TYPE_CLASS_TEXT -> {
                            when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> ".ascii"

                                else -> ""
                            }
                        }

                        else -> ""
                    }
                }
            }
        switchKeyboard(targetKeyboard)
        val isAsciiMode = rime.run { statusCached }.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: activeKeyboard?.let {
                if (theme.style.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        service.postRimeJob { setRuntimeOption("ascii_mode", targetMode) }
                    }
                }
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.run { statusCached }
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.style.autoCaps == true && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }


    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.run { statusCached }.isAsciiMode) {
            activeKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    /**
     * 候选走顶栏那一行（对齐 Typeless）：有候选就顶替品牌 + 胶囊，没有就换回来。
     * Trime 原来那条独立候选栏已经不再显示（见 InputView）。
     */
    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        if (data.candidates.isEmpty()) {
            lastCandidates = emptyArray()
            setComposingToolbar(false)
            collapseCandidatePanel()
        } else {
            if (!::toolbar.isInitialized) return
            lastCandidates = data.candidates
            // 拼音写在品牌那一位：行内预编辑只有输入框有，顶栏也留一份
            toolbar.setComposingText(service.currentComposingText)
            toolbar.updateCandidates(data.candidates, data.highlighted)
            setComposingToolbar(true)
            if (candidatesExpanded && ::candidatePanel.isInitialized) {
                candidatePanel.show(data.candidates)
            }
        }
    }

    /** 顶栏左边的拼写：只有正在拼写时才显示，免得退出组合后还挂着上一次的字。 */
    fun onComposingText(text: String) {
        if (!::toolbar.isInitialized) return
        toolbar.setComposingText(text)
    }

    private fun setComposingToolbar(composing: Boolean) {
        if (!::toolbar.isInitialized) return
        toolbar.setComposing(composing)
    }

    /** ⌄ / ⌃：把整页候选摊到按键区上面，或收起来。 */
    private fun toggleCandidatePanel() {
        if (candidatesExpanded) {
            collapseCandidatePanel()
            return
        }
        if (lastCandidates.isEmpty() || !::candidatePanel.isInitialized) return
        candidatesExpanded = true
        toolbar.setExpanded(true)
        candidatePanel.show(lastCandidates)
        candidatePanel.visibility = View.VISIBLE
    }

    private fun collapseCandidatePanel() {
        if (!candidatesExpanded) return
        candidatesExpanded = false
        if (::toolbar.isInitialized) toolbar.setExpanded(false)
        if (::candidatePanel.isInitialized) {
            candidatePanel.visibility = View.GONE
            candidatePanel.clear()
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            activeKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        switchKeyboard(".default")
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        if (value.option == "ascii_mode") syncToolbarActive()
        val option = value.option
        when {
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }

            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(theme.resolveAction(what))
                }
            }

            option == "ascii_mode" && !value.value -> {
                // 回到中文时，释放 Shift 锁定
                activeKeyboard?.resetShift()
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
        syncToolbarActive()
    }

    private fun syncToolbarActive() {
        if (!::toolbar.isInitialized) return
        toolbar.setActive(
            if (rime.run { statusCached }.isAsciiMode) VoiceTab.English else VoiceTab.Pinyin,
        )
    }

    override fun onDetached() {
        currentKeyboardView?.onDetach()
        setComposingToolbar(false)
        collapseCandidatePanel()
    }
}
