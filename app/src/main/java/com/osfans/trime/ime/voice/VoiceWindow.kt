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
        view.onAtTap = { service.commitText("@") }
        view.onActionTap = { performEditorAction() }
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
        panel = view
        return view
    }

    override fun onAttached() {
        VoiceSession.addListener(this)
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
    }

    override fun onDetached() {
        // 只解绑 UI，**不动**听写会话：切到键盘后录音/识别继续。
        VoiceSession.removeListener(this)
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
        // 录音中切走再切回来：把最后一段流式结果补上，预览不会变空。
        if (state == VoiceState.Recording && VoiceSession.lastPartial.isNotEmpty()) {
            view.setPreview(VoiceSession.lastPartial)
        }
    }

    override fun onVoicePartial(text: String) {
        panel?.setPreview(text)
    }

    override fun onVoiceLevel(level: Float) {
        panel?.setLevel(level)
    }

    override fun onVoiceNotice(text: String) {
        val view = panel ?: return
        view.showMessage(text)
        noticeJob?.cancel()
        noticeJob =
            service.lifecycleScope.launch {
                delay(2600)
                if (VoiceSession.state == VoiceState.Idle) view.setState(VoiceState.Idle)
            }
    }

    // ── 麦克风键 ────────────────────────────────────────────────────────

    private fun onOrbTap() {
        when (VoiceSession.state) {
            VoiceState.Idle -> VoiceSession.start()
            VoiceState.Recording -> VoiceSession.stop()
            VoiceState.Thinking -> Unit
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
