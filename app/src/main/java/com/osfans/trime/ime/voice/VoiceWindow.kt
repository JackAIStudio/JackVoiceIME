/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.AppUtils
import timber.log.Timber
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

/**
 * 语音面板窗口。与 KeyboardWindow / LiquidWindow 平级：
 * 它占据输入法的键盘区整块位置（不是悬浮窗，也不抢宿主输入框的焦点）。
 *
 * **面板只是 [VoiceSession] 的一个观察者**：录音、识别、上屏都在会话里，
 * 切到拼音 / EN 把这个窗口 detach 掉，听写照样在后台跑；切回来时按会话状态
 * 恢复画面（录音中 / 处理中 / 空闲），不会重置成「点击说话」。
 *
 * 上屏后**留在语音面板**（Typeless 也是这样）；要打字按顶栏的「拼」/「EN」。
 */
class VoiceWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    VoiceSession.Listener {
    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()

    private var panel: VoicePanelView? = null
    private var noticeJob: Job? = null

    /** 用户点了「本地」但模型还没下载完：就绪后自动切过去。 */
    private var pendingLocal = false

    private val localModelListener =
        LocalModelManager.Listener { status ->
            val view = panel ?: return@Listener
            when (status.state) {
                LocalModelManager.State.Downloading -> {
                    if (pendingLocal && VoiceSession.state == VoiceState.Idle) {
                        view.showMessage("本地模型下载 ${status.progress}%")
                    }
                }

                LocalModelManager.State.Ready -> {
                    if (pendingLocal) {
                        pendingLocal = false
                        VoicePrefs.setEngine(context, AsrEngine.Local)
                        view.setEngine(AsrEngine.Local)
                        view.showMessage("本地模型已就绪，已切到本地")
                    }
                }

                LocalModelManager.State.Failed -> {
                    if (pendingLocal) {
                        pendingLocal = false
                        view.showMessage("本地模型下载失败：${status.message}")
                    }
                }

                LocalModelManager.State.Missing -> Unit
            }
        }

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = VoiceWindow

    override fun onCreateView(): View {
        val view = VoicePanelView(context)
        view.onOrbTap = { onOrbTap() }
        // 左下角是标点档位：点按循环，长按直选。窗口只负责落盘。
        view.setPunctuationMode(VoicePrefs.punctuationMode(context))
        view.onPunctuationModeChange = { VoicePrefs.setPunctuationMode(context, it) }
        view.onSpaceTap = { service.commitText(" ") }
        view.onSpaceSlide = { dx, dy -> service.moveCursorBy(dx, dy) }
        view.onBackspaceTap = { service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        view.onActionTap = { performEditorAction() }
        view.onEngineTap = { onEngineTap() }
        // 顶部切换胶囊的「拼」/「EN」：切到拼音/英文键盘，并同步 Rime 的 ascii_mode。
        // 注意**不要**在这里 cancel 听写——手动拼写和听写是并行的两件事。
        view.toolbar.onPinyinTap = {
            service.switchInputLanguage(false)
            switchToKeyboard(VoiceTab.Pinyin)
        }
        view.toolbar.onEnglishTap = {
            service.switchInputLanguage(true)
            switchToKeyboard(VoiceTab.English)
        }
        view.toolbar.onBrandTap = {
            AppUtils.launchMainActivity(context)
        }
        // 顶栏最右一格「收起键盘」。语音面板和打字键盘各有一份顶栏，
        // 两边的回调都要接上——只在 KeyboardWindow 里接的话，语音面板上这颗键点了没反应。
        view.toolbar.onHideTap = { service.requestHideSelf(0) }
        // 上一轮识别失败时，这段录音还在本地：点一下就地再识别，不用去历史页翻。
        view.onRetryTap = { onRetryTap() }
        view.onRetryCancel = { VoiceSession.cancelRetry() }
        // 重新识别的结果不自动上屏：先在这张卡片上给用户看一眼，他点「插入」才落。
        view.onPreviewCommit = { VoiceSession.commitPreview() }
        view.onPreviewDiscard = { VoiceSession.discardPreview() }
        // 面板每次挂上窗口都回来同步一次：键盘展开 / 从拼音切回来都走这里。
        // 这是刷新键唯一可靠的时机——只靠「状态变化」推的话，
        // 面板一直开着、状态没变，就再没人推了。
        view.onAttachedSync = {
            syncRetryButton()
            view.setPreviewText(VoiceSession.preview?.text)
            syncRetrying(view)
        }
        panel = view
        return view
    }

    override fun onAttached() {
        VoiceSession.addListener(this)
        LocalModelManager.addListener(localModelListener)
        syncEngine()
        panel?.setActionLabel(actionLabel())
        panel?.toolbar?.setActive(VoiceTab.Voice)
        // 设置页可能刚改过标点；回到面板时同步一次。
        panel?.setPunctuationMode(VoicePrefs.punctuationMode(context))
        // 记住用户停在这一屏：下次展开键盘先回到语音面板（默认也是它）。
        VoicePrefs.setLastTab(context, VoiceTab.Voice)
        // 另一端可能刚改过词库：面板展开时做一次节流检查，下一轮听写就能用上。
        GlossarySyncManager.onVoicePanelShown(context)
        // 用户明确回到面板：上一轮残留的识别结果该落就落，别一直排着。
        VoiceSession.flushPending(force = true)
        syncRetryButton()
    }

    override fun onDetached() {
        // 只解绑 UI，**不动**听写会话：切到键盘后录音/识别继续。
        VoiceSession.removeListener(this)
        LocalModelManager.removeListener(localModelListener)
        noticeJob?.cancel()
        panel?.setLevel(0f)
    }

    /** 外观模式或主题配色变了：面板里的自定义颜色当场重取，不重建视图。 */
    override fun refreshColors() {
        panel?.refreshColors()
    }

    // ── VoiceSession 回调（会话保证在主线程）────────────────────────────

    override fun onVoiceStateChanged(state: VoiceState) {
        val view = panel ?: return
        view.setState(state)
        // 这一轮是不是「只录着、还没识别」：面板上那句提示要跟着换。
        view.setAsrDegraded(VoiceSession.asrDegraded, VoiceSession.asrNotice)
        // 连声音都收不到时，覆盖掉上面那句——说什么都没用，得先说麦克风的事。
        view.setMicBlocked(VoiceSession.micBlocked)
        syncRetryButton()
        // 重新识别的进度与预览卡片。**顺序不能反**：
        // setRetrying(false) 会按「有没有预览」决定要不要把麦克风放回来，
        // 所以得先把预览状态摆好，再让它收尾。
        view.setPreviewText(VoiceSession.preview?.text)
        syncRetrying(view)
        // 录音中切走再切回来：把最后一段流式结果补上，预览不会变空。
        if (state == VoiceState.Recording && VoiceSession.lastPartial.isNotEmpty()) {
            view.setPreview(VoiceSession.lastPartial)
        }
    }

    /**
     * 「正在重新识别…」该不该显示。
     *
     * 不只看会话里那个标记，还要跟 [VoiceTranscriber] 的真实任务对一次：
     * 标记是界面状态，任务是事实。标记因为任何原因残留（服务重建、回调丢失），
     * 而事实上没有任务在跑，就该立刻把这句话收掉——不能让用户对着
     * 「正在重新识别…」干等，那看起来就是卡死了。
     */
    private fun syncRetrying(view: VoicePanelView) {
        val marked = VoiceSession.retryingEntryId
        if (marked != null && VoiceTranscriber.busyEntryId == null) {
            // 标记残留：清掉它，别让界面继续等一个不存在的任务。
            VoiceSession.clearStaleRetrying()
            view.setRetrying(false)
            return
        }
        view.setRetrying(marked != null, VoiceSession.retryProgress)
    }

    /** 有可重试的录音、且面板回到空闲时，把「重新识别这段录音」亮出来。 */
    private fun syncRetryButton() {
        panel?.setRetryAvailable(VoiceSession.retryEntryId != null)
    }

    private fun onRetryTap() {
        // 进度由 VoiceSession.retryingEntryId 驱动（setRetrying）。
        // 面板中央现在只有一个渲染入口，提示和进度不会再叠字。
        VoiceSession.retryLast()
    }

    override fun onVoicePartial(text: String) {
        panel?.setPreview(text)
    }

    override fun onVoiceLevel(level: Float) {
        panel?.setLevel(level)
    }

    override fun onVoiceNotice(text: String) {
        val view = panel ?: return
        // 顺序很重要：先把重识别的真实状态摆好，再写这句提示。
        // 两者现在汇到同一个中央渲染入口，重识别进行中时提示不会顶掉进度，
        // 也不会再出现「点击说话 / 正在重新识别 / 提示」三层文字叠在一起。
        syncRetrying(view)
        view.showMessage(text)
        syncRetryButton()
        // 提示自己会过期（VoicePanelView 内部计时），这里只负责让刷新键
        // 在提示结束后重新同步一次；不要再用 setState(Idle) 硬刷整块面板。
        noticeJob?.cancel()
        noticeJob =
            service.lifecycleScope.launch {
                delay(2600)
                if (VoiceSession.state == VoiceState.Idle) {
                    syncRetryButton()
                }
            }
    }

    // ── 引擎切换 ────────────────────────────────────────────────────────

    /** 以「模型真的在不在」为准修正落盘值，避免设置里留着 local、实际却跑不起来。 */
    private fun syncEngine() {
        val ready = LocalModelManager.isReady(context)
        val saved = VoicePrefs.engine(context)
        val effective = if (saved == AsrEngine.Local && ready) AsrEngine.Local else AsrEngine.Cloud
        if (saved != effective) VoicePrefs.setEngine(context, effective)
        if (effective == AsrEngine.Local) LocalAsrEngine.preload(LocalModelManager.modelDir(context))
        panel?.setEngine(effective)
    }

    private fun onEngineTap() {
        if (VoiceSession.state != VoiceState.Idle) {
            panel?.showMessage("说完这一轮再切识别引擎")
            return
        }
        if (VoiceSession.retryingEntryId != null) {
            panel?.showMessage("先取消重新识别，再切引擎")
            return
        }
        when (VoicePrefs.engine(context)) {
            AsrEngine.Cloud -> {
                if (LocalModelManager.isReady(context)) {
                    VoicePrefs.setEngine(context, AsrEngine.Local)
                    LocalAsrEngine.preload(LocalModelManager.modelDir(context))
                    panel?.setEngine(AsrEngine.Local)
                    panel?.showMessage("已切到本地识别")
                } else {
                    pendingLocal = true
                    val ready = LocalModelManager.ensure(context)
                    if (ready) {
                        pendingLocal = false
                        VoicePrefs.setEngine(context, AsrEngine.Local)
                        panel?.setEngine(AsrEngine.Local)
                        panel?.showMessage("已切到本地识别")
                    } else {
                        panel?.showMessage("正在下载本地模型（约 128MB）")
                    }
                }
            }

            AsrEngine.Local -> {
                VoicePrefs.setEngine(context, AsrEngine.Cloud)
                panel?.setEngine(AsrEngine.Cloud)
                panel?.showMessage("已切到云端识别")
            }
        }
    }

    // ── 麦克风键 ────────────────────────────────────────────────────────

    private fun onOrbTap() {
        when (VoiceSession.state) {
            VoiceState.Idle -> VoiceSession.start()

            VoiceState.Recording -> VoiceSession.stop()

            // 等终稿时点一下 = 不等了，**用手上这版**。
            //
            // 以前这里调 cancel()：会把录音连同记录一起删掉。用户只是嫌慢想催一下，
            // 结果刚说的话没了，而且没有任何提示——这是最不能接受的一种「帮倒忙」。
            //
            // 现在走 finishNow()：引擎立刻交卷，流式一遍结果马上上屏，
            // 音频照样落库、照样能在历史里重新识别。最坏情况也只是文字不够准，
            // 而不是「白说一场」。
            //
            // 顺带把另一个更隐蔽的洞补上了：如果引擎其实**早就完成**、
            // 只是 onFinal 没送到这颗 View（主题重建、窗口刚 attach、
            // 弱引用监听者被回收），以前的 cancel() 会把一条已经成功的录音删掉。
            VoiceState.Thinking -> VoiceSession.finishNow()
        }
    }

    // ── 底栏与顶栏 ───────────────────────────────────────────────────────

    private fun switchToKeyboard(tab: VoiceTab? = null) {
        windowManager.attachWindow(keyboardWindow)
        // attach 时 Rime 的 ascii_mode 还没切完，键盘窗口会先按旧值记一次；
        // 这里在 attach 之后用用户真正点的目标盖回去，下次展开才是对的。
        tab?.let { VoicePrefs.setLastTab(context, it) }
    }

    private fun actionLabel(info: EditorInfo = service.currentInputEditorInfo): String {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
    }

    /**
     * 换编辑器时要刷一次动作键标签。
     *
     * 面板只在 [onAttached] 里算过一次标签，而从一个 App 切到另一个 App 时键盘往往一直显示着、
     * 面板并不 detach，那次计算不会再发生，底行就会留着上一个输入框的字（短信里是「发送」，
     * 切到搜索框还写着「发送」）。[InputView.startInput] 会带着新的 `EditorInfo` 调到这里。
     */
    fun updateActionLabel(info: EditorInfo = service.currentInputEditorInfo) {
        panel?.setActionLabel(actionLabel(info))
    }

    private fun performEditorAction() {
        val action = service.currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        val connection = service.currentInputConnection
        if (action != EditorInfo.IME_ACTION_NONE) {
            connection?.performEditorAction(action)
        } else {
            service.commitText("\n")
        }
    }
}
