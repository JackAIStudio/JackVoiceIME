// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import com.osfans.trime.ime.emoji.EmojiIndex
import com.osfans.trime.ime.emoji.EmojiMode
import com.osfans.trime.ime.emoji.EmojiQuery
import com.osfans.trime.ime.emoji.EmojiUsage
import com.osfans.trime.ime.emoji.EmojiVariantPopup
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.voice.CandidatePages
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
import com.osfans.trime.ui.jackvoice.JvColors
import com.osfans.trime.util.AppUtils
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
    private lateinit var trackpadHint: TextView
    private var trackpadVisible = false
    private var lastCandidates: Array<CandidateProto> = emptyArray()

    /** 当前候选条是英文补全给的（不是 Rime 的中文候选）。 */
    private var englishMode = false

    /**
     * Emoji 模式：候选行换成 emoji 来源，键盘与语言都不动。
     *
     * 和 [englishMode] 是同一条通路上的两个来源，都绕开 Rime 自己出候选。
     */
    private val emojiMode = EmojiMode(rime)

    /** 长按 emoji 时弹出肤色 / 性别变体。浮在候选条上方，所以是独立窗口。 */
    private val emojiVariants by lazy { EmojiVariantPopup(context) }

    private var candidatesExpanded = false

    /** [candidatePanel] 现在是不是 emoji 网格（9 列）。列数变了就得重建，见 [toggleCandidatePanel]。 */
    private var panelIsEmojiGrid = false

    /**
     * 面板当前显示的那一份数据。
     *
     * 中文/英文候选就是 [lastCandidates]（和候选条同一份，index 通用）；
     * emoji 展开时会换成更长的列表，此时**只能**按这一份去解析下标。
     */
    private var panelSource: Array<CandidateProto>? = null

    /** 展开面板的分页游标：Rime 一次只给 16 条，再往后得自己按全局下标要。 */
    private val candidatePages = CandidatePages()

    /** 每次换候选都加一；飞在半路上的分页请求回来时对不上号就丢掉。 */
    private var candidatePagesToken = 0

    /** Rime 报的候选总数；-1 表示它也不知道有多少，意味着 16 条后面还有。 */
    private var candidateTotal = -1

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

        /** 展开面板里最多给多少个 emoji。1906 个一次全塞进去会明显卡顿，500 够滑很久了。 */
        private const val PANEL_EMOJI_LIMIT = 500
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
                bar.onBrandTap = { AppUtils.launchMainActivity(context) }
                bar.onCandidatePick = { index -> pickCandidate(index) }
                bar.onExpandTap = { toggleCandidatePanel() }
                bar.onHideTap = { service.requestHideSelf(0) }
                // 候选行里的红色波形：听写在后台跑着时，从这里回语音面板停下来说完了。
                bar.onVoiceFromComposing = { boardWindowManager.attachWindow(voiceWindow) }
                // 候选行里的「拼/EN」：英文候选常驻，没有这一格就切不回中文。
                bar.onLanguageSwitchTap = { switchLanguage() }
                // 长按 emoji 弹肤色 / 性别变体。中文候选没有变体，这里直接不响应。
                bar.onCandidateLongPick = { index, anchor -> showEmojiVariants(index, anchor) }
                // 候选行那颗 ✕：一次清掉 emoji 搜索词（只清搜索词，不动输入框里的正文）
                bar.onClearQuery = { clearEmojiQuery() }
            }

        attachKeyboard(evalKeyboard(".default"))

        candidatePanel = buildCandidatePanel(emojiGrid = false)

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
                // 高度**必须**显式给按键区的高度，不能用 MATCH_PARENT：
                // keyArea 自己没有固定高度，面板 MATCH_PARENT 会反过来把 keyArea
                // 撑到整屏，盖住底部手势条（实测会把导航栏压掉）。
                currentKeyboardHeightPx(),
            ),
        )

        // 空格长按时的轻量触控板提示；不接收触摸，拖动事件继续由 KeyView 处理。
        trackpadHint =
            TextView(context).apply {
                setText(R.string.keyboard_trackpad_hint)
                setTextColor(JvColors.trackpadHintText)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(context.dp(16), 0, context.dp(16), 0)
                background =
                    GradientDrawable().apply {
                        cornerRadius = context.dp(18).toFloat()
                        setColor(JvColors.trackpadHintBg)
                    }
                elevation = context.dp(6).toFloat()
                isClickable = false
                isFocusable = false
                isLongClickable = false
                isSoundEffectsEnabled = false
                visibility = View.GONE
                alpha = 0f
            }
        keyArea.addView(
            trackpadHint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                context.dp(34),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = context.dp(10) },
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
    /** 当前键面的按键区高度（px）。面板要盖住按键区，用的就是它。 */
    private fun currentKeyboardHeightPx(): Int =
        activeKeyboard?.keyboardHeight ?: context.dp(theme.style.keyboardHeight)

    private fun reportHeight(keysHeight: Int) {
        val chrome =
            toolbarTopMarginPx +
                toolbarHeightPx +
                context.dp(TOOLBAR_GAP_DP) +
                context.dp(BOTTOM_GAP_DP)
        runBlocking { _currentKeyboardHeight.emit(keysHeight + chrome) }
    }

    /**
     * 造一个展开候选面板。
     *
     * 中文/英文候选是 3 列 × 50dp（字给眼睛读，一行放三个刚好），
     * emoji 是 9 列 × 44dp（图形要密，一屏才看得下足够多）。
     *
     * 两种网格的列数不同，没法共用一个已经 addView 过的实例，所以切换模式时
     * 整个换掉——面板只在展开那一刻存在，重建不丢状态。
     */
    private fun buildCandidatePanel(emojiGrid: Boolean): CandidatePanelView = CandidatePanelView(
        context,
        perRow = if (emojiGrid) CandidatePanelView.EMOJI_PER_ROW else CandidatePanelView.DEFAULT_PER_ROW,
        rowHeightDp = if (emojiGrid) CandidatePanelView.EMOJI_ROW_HEIGHT_DP else CandidatePanelView.DEFAULT_ROW_HEIGHT_DP,
    ).apply {
        visibility = View.GONE
        // 展开面板里的候选和候选条走同一个 pickCandidate：
        // 英文归词表提交、emoji 归替换、中文交给 Rime。这里的 index 一律是**全量下标**。
        onPick = { index ->
            // emoji 面板的列表和候选条不是同一份（展开时会给更多），
            // 所以拿 index 去查 lastCandidates 会点错。按「面板里点到的那个」提交。
            val picked = panelSource?.getOrNull(index)?.text
            collapseCandidatePanel()
            if (emojiMode.enabled && picked != null) {
                pickEmoji(picked)
            } else {
                pickCandidate(index)
            }
        }
        // 中文：Rime 的 bulk 候选只有 16 条，后面的得一批批要。
        // emoji：本地列表已经全在内存里，不需要分批（见 requestMoreCandidates）。
        onLoadMore = { requestMoreCandidates() }
    }

    private fun setTrackpadActive(active: Boolean) {
        if (!::trackpadHint.isInitialized || trackpadVisible == active) return
        trackpadVisible = active
        trackpadHint.animate().cancel()
        keyboardView.animate().cancel()
        if (active) {
            trackpadHint.visibility = View.VISIBLE
            trackpadHint.animate().alpha(1f).setDuration(90).start()
            keyboardView.animate().alpha(0.22f).setDuration(120).start()
        } else {
            keyboardView.animate().alpha(1f).setDuration(120).start()
            trackpadHint.animate().alpha(0f).setDuration(90).withEndAction {
                if (trackpadHint.alpha == 0f) trackpadHint.visibility = View.GONE
            }.start()
        }
    }

    private fun detachCurrentView() {
        keyboardView.animate().cancel()
        keyboardView.alpha = 1f
        if (::trackpadHint.isInitialized) {
            trackpadHint.animate().cancel()
            trackpadHint.visibility = View.GONE
            trackpadHint.alpha = 0f
        }
        trackpadVisible = false
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
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
        }

        keyboard.also {
            reportHeight(it.keyboardHeight)
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            // 换布局**不碰语言**：中英只由顶栏胶囊和输入框类型决定。
            // 这里以前会按布局的 ascii_mode 顺手 setRuntimeOption，于是
            // 「123 → 字母键回键盘」一来一回就是「强制英文、再强制中文」，
            // 顶栏胶囊乱跳，组合中的拼写还会被顺手确认上屏。
            // 只有 YAML 里显式写了 reset_ascii_mode: true 的布局才允许这么做。
            asciiModeOnAttach(
                resetAsciiMode = it.resetAsciiMode,
                layoutAsciiMode = it.asciiMode,
                currentAsciiMode = rime.run { statusCached }.isAsciiMode,
            )?.let { targetMode ->
                service.postRimeJob {
                    commitComposition()
                    setRuntimeOption("ascii_mode", targetMode)
                }
            }

            currentKeyboard = it
            // 面板要盖住按键区：换键面后高度可能变了（横屏 200dp / 竖屏 236dp），
            // 不跟着改的话面板会比按键区高或矮一截。
            if (::candidatePanel.isInitialized && candidatePanel.layoutParams != null) {
                (candidatePanel.layoutParams as? FrameLayout.LayoutParams)?.height =
                    currentKeyboardHeightPx()
            }
            // 换键面（字母 → 123 → 回来）时把那颗表情键的亮灭重新落一遍：
            // 每张布局是各自的 Key 实例，状态不会跟着走。这里直接用刚挂上的
            // keyboard，不走 activeKeyboard——缓存里可能还是旧的。
            applyEmojiKeyState(it)
        }

        view.let {
            it.onTrackpadStateChanged = { active -> setTrackpadActive(active) }
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
        if (::toolbar.isInitialized) toolbar.refreshColors()
        if (::candidatePanel.isInitialized) candidatePanel.refreshColors()
        if (::trackpadHint.isInitialized) {
            trackpadHint.setTextColor(JvColors.trackpadHintText)
            trackpadHint.background =
                GradientDrawable().apply {
                    cornerRadius = context.dp(18).toFloat()
                    setColor(JvColors.trackpadHintBg)
                }
        }
    }

    override fun onStartInput(info: EditorInfo) {
        // 换输入框时退出 emoji 模式：新输入框有它自己的语言与布局，带着一个
        // 半截的 emoji 搜索词过去没有意义。
        if (emojiMode.enabled) exitEmojiMode()

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
                    // 这里也只在布局显式 opt-in 时才动语言；没写 reset_ascii_mode 的布局
                    // 就沿用当前语言，免得换个输入框把用户选的拼音/EN 顶掉。
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else isAsciiMode
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
        val picked = lastCandidates.getOrNull(index)?.text ?: return
        when {
            // emoji 候选不走 Rime，也不走英文那条「删词干提交整词」——它替换的是搜索时打的那串字母
            emojiMode.enabled -> pickEmoji(picked)

            englishMode -> service.commitEnglishSuggestion(picked)

            else -> rime.launchOnReady { it.selectCandidate(index, global = true) }
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
        if (emojiMode.enabled) return
        englishMode = true
        lastCandidates = items.map { CandidateProto(it, "", "") }.toTypedArray()
        toolbar.updateCandidates(lastCandidates, 0)
        toolbar.setComposing(true)
        if (candidatesExpanded && ::candidatePanel.isInitialized) {
            candidatePanel.show(lastCandidates)
            restartCandidatePages()
        }
    }

    fun clearEnglishCandidates() {
        if (!englishMode) return
        clearCandidates()
    }

    /**
     * 进 / 出 emoji 模式。
     *
     * 只换候选来源，不换键盘、不换语言、不动高度——底行那颗 🙂 也只改高亮。
     */
    fun toggleEmojiMode() {
        if (emojiMode.enabled) {
            exitEmojiMode()
        } else {
            enterEmojiMode()
        }
    }

    private fun enterEmojiMode() {
        if (!::toolbar.isInitialized) return
        emojiMode.enter()
        englishMode = false
        if (::toolbar.isInitialized) toolbar.setEmojiCandidateMode(true)
        // 中文正在拼的那串由 EmojiMode 提交掉，候选条交给 emoji
        refreshEmojiCandidates()
        syncEmojiKeyState()
    }

    private fun exitEmojiMode() {
        emojiMode.exit()
        emojiVariants.dismiss()
        if (::toolbar.isInitialized) toolbar.setEmojiCandidateMode(false)
        clearCandidates()
        syncEmojiKeyState()
        // 回到中文时 Rime 组合已经清空，候选等下一次输入自己会回来；
        // 回到英文时按光标前的词补一次，和切语言那条路保持一致。
        if (rime.run { statusCached }.isAsciiMode) {
            service.lifecycleScope.launch { service.updateEnglishSuggestions() }
        }
    }

    /**
     * 🙂 那颗键的亮灭跟着模式走。
     *
     * 键盘布局本身不变，所以只需要把当前键面上那颗键的状态改掉再重绘；
     * 键不在这个布局上（比如符号页）就什么都不做。
     */
    private fun syncEmojiKeyState() {
        applyEmojiKeyState(activeKeyboard ?: return)
    }

    /** 把 emoji 模式的亮灭落到某张具体布局上；布局里没有这颗键就什么都不做。 */
    private fun applyEmojiKeyState(keyboard: Keyboard) {
        val key = keyboard.keys.firstOrNull { it.click?.command == "emoji_mode" } ?: return
        key.setOnState(emojiMode.enabled)
        currentKeyboardView?.invalidateKeyByIndex(key.index)
    }

    /**
     * 打字时刷新 emoji 候选。
     *
     * 空查询显示最近用过的（进模式就能直接抓），有查询显示搜索结果。
     */
    private fun refreshEmojiCandidates() {
        if (!emojiMode.enabled || !::toolbar.isInitialized) return
        EmojiIndex.ensureLoaded(context)
        val recents = EmojiUsage.recent(EmojiIndex.STRIP_LIMIT)
        val query = emojiMode.query
        val items =
            if (query.isEmpty()) {
                recents.ifEmpty { EmojiIndex.emojis.take(EmojiIndex.STRIP_LIMIT) }
            } else {
                EmojiIndex.search(query, EmojiIndex.STRIP_LIMIT, recents)
            }

        // 搜索无结果时**不能**把候选行撤掉退回「品牌 + 胶囊」：
        // 那样用户看不到自己打了什么，也分不清是「没搜到」还是「模式关了」，
        // 只会觉得键盘坏了。保留候选行，用它显示一条占位提示。
        if (items.isEmpty()) {
            lastCandidates = emptyArray()
            toolbar.showEmojiNoResult(query)
            setComposingToolbar(true)
            collapseCandidatePanel()
            return
        }

        lastCandidates = items.map { CandidateProto(it, "", "") }.toTypedArray()
        toolbar.updateCandidates(lastCandidates, 0)
        setComposingToolbar(true)
        if (candidatesExpanded && ::candidatePanel.isInitialized) {
            candidatePanel.show(lastCandidates)
            restartCandidatePages()
        }
    }

    /** emoji 模式下按了字母 / 退格：返回 true 表示被吃掉，别再送给 Rime。 */
    fun handleEmojiChar(ch: Char): Boolean {
        if (!emojiMode.onChar(ch)) return false
        syncEmojiComposing()
        refreshEmojiCandidates()
        return true
    }

    /**
     * 把 emoji 搜索词写进输入框，显示成**带下划线的组合文本**。
     *
     * 不写进去的话，用户打了 `pisa` 在输入框里什么都看不到——只有候选条在变，
     * 看起来就像键盘吞字。这一条不管是拼音还是英文模式都要有，否则「打字没反应」。
     *
     * 用 setComposingText 而不是 commitText：这样点 emoji 时 `commitText` 会自动
     * 替换掉这段组合文本，不会在输入框里留下 `pisa🍕`。
     */
    private fun syncEmojiComposing() {
        val ic = service.currentInputConnection ?: return
        val query = emojiMode.query
        runCatching {
            if (query.isNotEmpty()) {
                // 组合文本整段重设：查询词最长也就十来个字母，这点开销比维护增量状态稳。
                ic.setComposingText(query, 1)
            } else if (emojiComposingLength > 0) {
                // 清空时**必须先把那串字母删掉**再结束组合。
                // 只调 finishComposingText() 的话，组合态结束、文字留下，
                // 输入框里就会永远躺着一串 `pisa` —— 这正是「✕ 点了没反应」的真相。
                ic.deleteSurroundingText(emojiComposingLength, 0)
                ic.finishComposingText()
            }
            emojiComposingLength = query.length
        }
    }

    /**
     * 上一次写进输入框的组合文本长度。
     *
     * 清空 / 退格时要知道该删几个字符。不能拿 `emojiMode.query.length` 去删——
     * 那时 query 已经变短了，删的会比实际少。
     */
    private var emojiComposingLength: Int = 0

    /**
     * 把一次按键还原成它要输入的字符，供 emoji 搜索用。
     *
     * 虚拟键盘的字母是**键码**进来的（`KEYCODE_Q` 之类），不是现成的字符，
     * 所以这里做一次映射；其它键（退格、空格、回车、符号页的键）返回 null，
     * 交给原来的路径处理。
     */
    fun emojiCharFor(keyEventCode: Int, metaState: Int): Char? {
        if (!emojiMode.enabled) return null
        val shift = metaState and KeyEvent.META_SHIFT_ON != 0
        return when (keyEventCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val lower = 'a' + (keyEventCode - KeyEvent.KEYCODE_A)
                if (shift) lower.uppercaseChar() else lower
            }

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyEventCode - KeyEvent.KEYCODE_0)

            // 空格**不**当搜索字符：emoji 模式下按空格就是想打个空格，
            // 吃掉它会让底行那颗最大的键变成哑键。让它照常落进输入框。
            else -> null
        }
    }

    /**
     * 一整段文字进来（可能是多字符）。
     *
     * 只有全部字符都能被 emoji 模式接受时才整段吃掉；有一个吃不下就把整段原样放行，
     * 免得出现「吃掉一半、剩下半截跑进拼音」的错位。
     */
    fun handleEmojiText(input: String): Boolean {
        if (!emojiMode.enabled) return false
        if (input.isEmpty()) return false
        // 先问「整段能不能全吃」，再真的吃。
        // 如果直接 all { onChar(it) }，遇到中途不可接受的字符会把前面几个已经
        // 塞进 query 的字留下，而返回值是 false——那半截就既没进 Rime 也没进搜索框，
        // 用户会觉得打字丢字。
        if (!input.all { EmojiQuery.isSearchable(it) }) return false
        input.forEach { emojiMode.onChar(it) }
        syncEmojiComposing()
        refreshEmojiCandidates()
        return true
    }

    /**
     * 清空 emoji 搜索词。
     *
     * 只清搜索词，输入框里的正文一个字都不动——这串字母是我们自己用
     * setComposingText 写进去的，finishComposingText 会把这段组合态收掉。
     */
    private fun clearEmojiQuery() {
        if (!emojiMode.enabled) return
        emojiMode.clearQuery()
        syncEmojiComposing()
        refreshEmojiCandidates()
    }

    fun handleEmojiBackspace(): Boolean {
        if (!emojiMode.onBackspace()) return false
        syncEmojiComposing()
        refreshEmojiCandidates()
        return true
    }

    /**
     * 点了一个 emoji：把它插到光标处。
     *
     * 关键点：**搜索时打的那串字母从来没有进过输入框**。
     * 字母在 `onKey` 里就被 emoji 模式吃掉了（见 [handleEmojiChar]），
     * 输入框里只有用户原本的正文。所以这里**不能** deleteSurroundingText——
     * 那删掉的是用户自己的字，不是我们打的搜索词。
     *
     * 中文模式下倒是要先把 Rime 里挂着的组合收掉（进 emoji 模式时已经提交了，
     * 这里是兜底，防止中途又有组合冒出来）。
     */
    private fun pickEmoji(emoji: String) {
        val ic = service.currentInputConnection ?: return
        EmojiUsage.record(emoji)
        emojiMode.onPicked()
        emojiVariants.dismiss()

        // 输入框里那段带下划线的搜索词是**我们自己**用 setComposingText 写的，
        // 和 Rime 的组合无关。commitText 会就地把它替换掉——这正是要的效果。
        // 所以这里不能再调 rime.clearComposition()：那是异步任务，晚一步执行时
        // 会把刚提交的 emoji 一起清掉（表现为「点了没反应」）。
        ic.beginBatchEdit()
        try {
            ic.commitText(emoji, 1)
        } finally {
            ic.endBatchEdit()
        }

        // 提交后候选条回到「最近」那一屏，方便连续发几个
        refreshEmojiCandidates()
    }

    /**
     * 长按候选：emoji 弹肤色 / 性别变体，别的不响应。
     *
     * 没有变体的 emoji（🍕、🚀）不弹空窗，直接什么都不做——弹一个只有它自己的
     * 一格窗只会让人困惑。
     */
    private fun showEmojiVariants(
        index: Int,
        anchor: View,
    ) {
        if (!emojiMode.enabled) return
        val base = lastCandidates.getOrNull(index)?.text ?: return
        val variants = EmojiIndex.variantsOf(base)
        if (variants.isEmpty()) return
        emojiVariants.show(anchor, base, variants) { picked -> pickEmoji(picked) }
    }

    /** 不管候选是 Rime 的中文还是词表给的英文，一律清掉，顶栏交还「品牌 + 胶囊」。 */
    private fun clearCandidates() {
        englishMode = false
        lastCandidates = emptyArray()
        setComposingToolbar(false)
        collapseCandidatePanel()
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        // emoji 模式下 Rime 的候选不该露头（进模式时组合已经提交掉了）
        if (englishMode || emojiMode.enabled) return
        candidateTotal = data.total
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
                restartCandidatePages()
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

        // emoji 用 9 列网格，中文/英文用 3 列。两种网格列数不同，换模式就整个换掉面板。
        // 面板只在这一刻存在，重建不丢任何状态。
        val wantEmojiGrid = emojiMode.enabled
        if (panelIsEmojiGrid != wantEmojiGrid) {
            panelIsEmojiGrid = wantEmojiGrid
            (candidatePanel.parent as? android.view.ViewGroup)?.removeView(candidatePanel)
            candidatePanel = buildCandidatePanel(emojiGrid = wantEmojiGrid)
            keyArea.apply {
                // 插回 trackpadHint 之前，保持叠放次序
                addView(
                    candidatePanel,
                    indexOfChild(trackpadHint).coerceAtLeast(0),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        currentKeyboardHeightPx(),
                    ),
                )
            }
        }

        if (wantEmojiGrid) {
            // emoji 全在内存里：展开时给**全部**匹配结果，而不是候选条那 24 个。
            // 候选条是横着滑的窄条，只放得下开头几个；展开就是要把剩下的摊开。
            val expanded = emojiCandidatesForPanel()
            if (expanded.isNotEmpty()) lastCandidates = expanded
            candidatePanel.show(lastCandidates)
            candidatePanel.visibility = View.VISIBLE
        } else {
            panelSource = lastCandidates
            candidatePanel.show(lastCandidates)
            candidatePanel.visibility = View.VISIBLE
            restartCandidatePages()
        }
        return true
    }

    fun collapseCandidatePanel() {
        if (!candidatesExpanded) return
        candidatesExpanded = false
        panelSource = null
        // 在飞的分页请求作废：回来时会发现 token 对不上
        candidatePagesToken++
        candidatePages.reset(lastCandidates.size, exhausted = true)
        if (::toolbar.isInitialized) toolbar.setExpanded(false)
        if (::candidatePanel.isInitialized) {
            candidatePanel.setLoadingMore(false)
            candidatePanel.visibility = View.GONE
            candidatePanel.clear()
        }
    }

    /**
     * 重开一轮分页。
     *
     * Rime 的 bulk 候选最多 16 条；`total >= 0` 说明它已经把总数报过来了（后面没有
     * 更多），英文候选又是本地词表给的、压根不归 Rime 管——这两种情况都直接判到头，
     * 别再白要一次。
     */
    private fun restartCandidatePages() {
        candidatePagesToken++
        candidatePages.reset(
            loaded = lastCandidates.size,
            exhausted = englishMode || candidateTotal >= 0,
        )
        if (::candidatePanel.isInitialized) candidatePanel.setLoadingMore(false)
        requestMoreCandidates()
    }

    /**
     * 展开面板要显示的 emoji。
     *
     * 候选条只取 24 个（横条放不下更多），展开时把同一次查询的完整结果拿出来。
     * 没有查询时给「最近 + 全部分类」，这样点开就能一路滑到底。
     */
    private fun emojiCandidatesForPanel(): Array<CandidateProto> {
        if (!emojiMode.enabled) return emptyArray()
        EmojiIndex.ensureLoaded(context)
        val recents = EmojiUsage.recent(EmojiIndex.STRIP_LIMIT)
        val items =
            if (emojiMode.query.isEmpty()) {
                if (recents.isEmpty()) EmojiIndex.emojis else recents + EmojiIndex.emojis
            } else {
                EmojiIndex.search(emojiMode.query, PANEL_EMOJI_LIMIT, recents)
            }
        return items.distinct().map { CandidateProto(it, "", "") }.toTypedArray()
    }

    /** 向 Rime 要下一批候选，回来接在展开面板后面。 */
    private fun requestMoreCandidates() {
        if (!candidatesExpanded || !::candidatePanel.isInitialized) return
        // emoji 的候选全在本地，问 Rime 要只会拿到中文候选接到 emoji 后面去。
        if (emojiMode.enabled) return
        val offset = candidatePages.beginLoad() ?: return
        val token = candidatePagesToken
        val limit = candidatePages.pageSize
        candidatePanel.setLoadingMore(true)
        rime.launchOnReady { api ->
            val more = runCatching { api.getCandidates(offset, limit) }.getOrNull()
            candidatePanel.post {
                // 候选换了一批、或者面板已经收起：这批结果作废
                if (token != candidatePagesToken) return@post
                if (more == null) {
                    candidatePages.abortLoad()
                    candidatePanel.setLoadingMore(false)
                    return@post
                }
                candidatePages.endLoad(more.size)
                candidatePanel.setLoadingMore(false)
                if (more.isNotEmpty()) candidatePanel.append(more)
            }
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
        // 变体弹窗是独立的 PopupWindow，不跟着键盘一起隐藏——不主动收会浮在
        // 下一个应用上面。
        emojiVariants.dismiss()
        setComposingToolbar(false)
        collapseCandidatePanel()
    }
}
