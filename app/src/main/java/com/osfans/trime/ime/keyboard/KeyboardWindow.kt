// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import com.osfans.trime.ime.voice.VoicePrefs
import com.osfans.trime.ime.voice.VoiceSession
import com.osfans.trime.ime.voice.VoiceState
import com.osfans.trime.ime.voice.VoiceTab
import com.osfans.trime.ime.voice.VoiceToolbarView
import com.osfans.trime.ime.voice.VoiceWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
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

    /** 当前候选条是英文补全给的（不是 Rime 的中文候选）。 */
    private var englishMode = false
    private var candidatesExpanded = false

    /**
     * 当前输入框强制 ASCII（密码 / 邮箱 / 数字）。
     *
     * 这种输入框会被系统要求关掉中文，恢复「上次模块」时不能把它扳回拼音；
     * 这只是这一次输入框的临时状态，不写进偏好。
     */
    private var editorForcesAscii = false

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

    /**
     * 听写会话的观察者。
     *
     * 手动拼写时听写仍在后台跑：顶栏的 🎙 标出「录音中 / 处理中」，
     * 点它回语音面板就能停。会话自己负责上屏，键盘这边不碰音频。
     */
    private val voiceSessionListener =
        object : VoiceSession.Listener {
            override fun onVoiceStateChanged(state: VoiceState) {
                if (::toolbar.isInitialized) toolbar.setVoiceState(state)
            }

            override fun onVoiceNotice(text: String) {
                runCatching { Toast.makeText(service, text, Toast.LENGTH_SHORT).show() }
            }
        }

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
                bar.onVoiceTap = {
                    VoicePrefs.setLastTab(context, VoiceTab.Voice)
                    boardWindowManager.attachWindow(voiceWindow)
                }
                // 切的是 Rime 方案（英文有自己的方案和词库），不是只翻 ascii_mode
                bar.onPinyinTap = {
                    service.switchInputLanguage(false)
                    bar.setActive(VoiceTab.Pinyin)
                    VoicePrefs.setLastTab(context, VoiceTab.Pinyin)
                }
                bar.onEnglishTap = {
                    service.switchInputLanguage(true)
                    bar.setActive(VoiceTab.English)
                    VoicePrefs.setLastTab(context, VoiceTab.English)
                }
                bar.onCandidatePick = { index -> pickCandidate(index) }
                bar.onExpandTap = { toggleCandidatePanel() }
                bar.onHideTap = { service.requestHideSelf(0) }
                // 候选行里的红色波形：听写在后台跑着时，从这里回语音面板停下来说完了。
                bar.onVoiceFromComposing = { boardWindowManager.attachWindow(voiceWindow) }
                // 候选行里的「拼/EN」：英文候选常驻，没有这一格就切不回中文。
                bar.onLanguageSwitchTap = { switchLanguage() }
            }

        attachKeyboard(evalKeyboard(".default"))

        candidatePanel =
            CandidatePanelView(context).apply {
                visibility = View.GONE
                // 展开面板里的英文候选也归英文补全处理，不能送去 Rime 选——
                // 那里的下标对不上，点了等于没点。
                onPick = { index ->
                    collapseCandidatePanel()
                    pickCandidate(index)
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
        // 英文候选是词表给的，Rime 重新聚焦时不会自己冒候选；如果当前已经是英文模式，
        // 别把顶栏收成品牌——等 updateEnglishSuggestions 刷新，否则切键盘时顶栏会闪一下。
        if (!rime.run { statusCached }.isAsciiMode) {
            clearEnglishCandidates()
            setComposingToolbar(false)
        }
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
        // 这个输入框是不是系统要求必须 ASCII；恢复上次模块时要绕开它。
        editorForcesAscii = targetKeyboard == ".ascii" || targetKeyboard == "number"
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

    /**
     * 新开输入框时恢复上次选的模块（拼音 / EN）。
     *
     * 只恢复语言，不恢复布局：布局已经由 [onStartInput] 按 inputType 选好。
     * 密码 / 邮箱 / 数字这类强制 ASCII 的输入框保持系统要求的模式，
     * 不强行扳回中文，也不把这次临时状态记成用户的选择。
     */
    fun restorePreferredLanguage(tab: VoiceTab) {
        if (!::toolbar.isInitialized) return
        if (editorForcesAscii) {
            syncToolbarActive()
            return
        }
        val english = tab == VoiceTab.English
        if (rime.run { statusCached }.isAsciiMode != english) {
            service.switchInputLanguage(english)
        }
        // Rime 的 ascii_mode 是异步生效的，先在顶栏上落位，别闪一下再切。
        toolbar.setActive(if (english) VoiceTab.English else VoiceTab.Pinyin)
    }

    /** 点候选：英文补全按词表提交，中文交给 Rime。候选条与展开面板共用这一条。 */
    private fun pickCandidate(index: Int) {
        val picked = lastCandidates.getOrNull(index)?.text
        if (englishMode && picked != null) {
            service.commitEnglishSuggestion(picked)
        } else {
            rime.launchOnReady { it.selectCandidate(index, global = true) }
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

    // 组合中：拼写单独一行浮在键盘上方（ComposingBarView），候选占顶栏那一行
    // （VoiceToolbarView 的 composingRow）。这里只负责记住候选、以及 ⌄ 展开整页。

    /** 英文候选：词表补全出来的，和 Rime 没关系，别被 Rime 的空候选顶掉。 */
    fun showEnglishCandidates(items: List<String>) {
        if (items.isEmpty() || !::toolbar.isInitialized) return
        englishMode = true
        lastCandidates = items.map { CandidateProto(it, "", "") }.toTypedArray()
        toolbar.updateCandidates(lastCandidates, 0)
        toolbar.setComposing(true)
        if (candidatesExpanded && ::candidatePanel.isInitialized) candidatePanel.show(lastCandidates)
    }

    fun clearEnglishCandidates() {
        if (!englishMode) return
        clearCandidates()
    }

    /** 不管候选是 Rime 的中文还是词表给的英文，一律清掉，顶栏交还「品牌 + 胶囊」。 */
    private fun clearCandidates() {
        englishMode = false
        lastCandidates = emptyArray()
        setComposingToolbar(false)
        collapseCandidatePanel()
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        if (englishMode) return
        if (data.candidates.isEmpty()) {
            lastCandidates = emptyArray()
            setComposingToolbar(false)
            collapseCandidatePanel()
        } else {
            lastCandidates = data.candidates
            if (::toolbar.isInitialized) {
                toolbar.updateCandidates(data.candidates, data.highlighted)
                setComposingToolbar(true)
            }
            if (candidatesExpanded && ::candidatePanel.isInitialized) {
                candidatePanel.show(data.candidates)
            }
        }
    }

    private fun setComposingToolbar(composing: Boolean) {
        if (!::toolbar.isInitialized) return
        toolbar.setComposing(composing)
    }

    /** ⌄ / ⌃：把整页候选摊到按键区上面，或收起来。返回收起后的状态（true = 已展开）。 */
    fun toggleCandidatePanel(): Boolean {
        if (candidatesExpanded) {
            collapseCandidatePanel()
            return false
        }
        if (lastCandidates.isEmpty() || !::candidatePanel.isInitialized) return false
        candidatesExpanded = true
        if (::toolbar.isInitialized) toolbar.setExpanded(true)
        candidatePanel.show(lastCandidates)
        candidatePanel.visibility = View.VISIBLE
        return true
    }

    fun collapseCandidatePanel() {
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
        if (value.option == "ascii_mode") {
            syncToolbarActive()
            // 注意：这里**不要**记 voice_last_tab。ascii_mode 也会被输入框类型、
            // 方案重载之类的非用户动作翻动，写进去会把用户上次选的模块带偏；
            // 模块只认顶栏那几下明确的点击。
            // 语言一切换，旧语言的候选就作废（中文候选不该留在英文键盘上）；先清掉，
            // 英文模式下再按光标前的词重建。
            clearCandidates()
            if (value.value) {
                // ascii_mode 真正生效之后再按光标前的词补候选。切英文的那一下
                // statusCached 还是旧值，早一步更新会被当成「非英文」清空，候选就没了。
                service.updateEnglishSuggestions()
            }
        }
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
        // 从语音面板切回来时 ascii_mode 往往已经是对的，不会再发 OptionMessage；
        // 英文又没有 Rime 组合会自己冒候选，所以这里主动按光标前的词补一次，
        // 否则切回英文键盘后要等下一次按键候选才出来。
        if (rime.run { statusCached }.isAsciiMode) {
            service.lifecycleScope.launch { service.updateEnglishSuggestions() }
        }
        // 后台可能还在录：顶栏把状态接上（addListener 会立刻回一次当前状态）。
        VoiceSession.addListener(voiceSessionListener)
    }

    private fun syncToolbarActive() {
        if (!::toolbar.isInitialized) return
        val english = rime.run { statusCached }.isAsciiMode
        toolbar.setActive(if (english) VoiceTab.English else VoiceTab.Pinyin)
    }

    /** 候选行里的语言小键：按当前 ascii_mode 切到另一种语言。 */
    private fun switchLanguage() {
        val toEnglish = !rime.run { statusCached }.isAsciiMode
        clearCandidates()
        service.switchInputLanguage(toEnglish)
        toolbar.setActive(if (toEnglish) VoiceTab.English else VoiceTab.Pinyin)
        VoicePrefs.setLastTab(context, if (toEnglish) VoiceTab.English else VoiceTab.Pinyin)
    }

    override fun onDetached() {
        VoiceSession.removeListener(voiceSessionListener)
        currentKeyboardView?.onDetach()
        englishMode = false
        setComposingToolbar(false)
        collapseCandidatePanel()
    }
}
