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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import timber.log.Timber

/**
 * 语音面板窗口。与 KeyboardWindow / LiquidWindow 平级：
 * 它占据输入法的键盘区整块位置（不是悬浮窗，也不抢宿主输入框的焦点）。
 *
 * 链路：点击说话 → 起麦克风前台服务（拿麦克风的前提）→ 16k 单声道录音
 *      → 豆包流式（带 59 条热词）→ 二遍终稿 → 79 条替换规则 → commitText 落到当前输入框。
 *
 * 上屏后**留在语音面板**（Typeless 也是这样）；要打字按顶栏的「拼」/「EN」
 * 或左下角的键盘键。
 */
class VoiceWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    VoiceEngine.Listener {
    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()

    private var panel: VoicePanelView? = null
    private var state = VoiceState.Idle

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = VoiceWindow

    override fun onCreateView(): View {
        val view = VoicePanelView(context)
        view.onOrbTap = { onOrbTap() }
        view.onKeyboardTap = { switchToKeyboard(ascii = null) }
        view.onBackspaceTap = { service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        view.onAtTap = { service.commitText("@") }
        view.onActionTap = { performEditorAction() }
        // 顶部切换器的「拼」/「EN」：切到拼音/英文键盘，并同步 Rime 的 ascii_mode
        view.onPinyinTab = { switchToKeyboard(ascii = false) }
        view.onEnglishTab = { switchToKeyboard(ascii = true) }
        panel = view
        return view
    }

    override fun onAttached() {
        VoiceEngine.setListener(this)
        setState(VoiceState.Idle)
        panel?.setActionLabel(actionLabel())
    }

    override fun onDetached() {
        VoiceEngine.setListener(null)
        VoiceEngine.cancel()
        VoiceCaptureService.stop(service)
        setState(VoiceState.Idle)
    }

    private fun setState(next: VoiceState) {
        state = next
        panel?.setState(next)
    }

    private fun onOrbTap() {
        when (state) {
            VoiceState.Idle -> startRecording()
            VoiceState.Recording -> stopRecording()
            VoiceState.Thinking -> Unit
        }
    }

    private fun startRecording() {
        VoiceEngine.setListener(this)
        // 前台服务是「尽力而为」：能起来最好（前台态更容易拿到麦克风），
        // 起不来也不影响录音——录音本体在 VoiceEngine 里。
        VoiceCaptureService.start(service)
        VoiceEngine.startRecording(service)
        setState(VoiceState.Recording)
    }

    private fun stopRecording() {
        setState(VoiceState.Thinking)
        VoiceEngine.stopRecording()
        VoiceCaptureService.stop(service)
    }

    // ── VoiceEngine 回调（引擎保证在主线程）────────────────────────────────

    override fun onPartial(text: String) = Unit

    override fun onFinal(text: String) {
        service.lifecycleScope.launch {
            VoiceCaptureService.stop(service)
            val processed = if (text.isBlank()) "" else PostProcessor.process(text, service)
            if (processed.isNotBlank()) {
                service.commitText(processed)
            } else {
                Timber.w("语音识别结果为空，未上屏")
            }
            setState(VoiceState.Idle)
        }
    }

    override fun onError(message: String) {
        service.lifecycleScope.launch {
            VoiceCaptureService.stop(service)
            panel?.showMessage("识别失败：$message")
            state = VoiceState.Idle
            delay(2600)
            if (state == VoiceState.Idle) panel?.setState(VoiceState.Idle)
        }
    }

    // ── 底栏与顶栏 ────────────────────────────────────────────────────────

    /** ascii 为 null 表示只是回键盘，不动中英模式。 */
    private fun switchToKeyboard(ascii: Boolean?) {
        VoiceEngine.cancel()
        VoiceCaptureService.stop(service)
        if (ascii != null) {
            service.postRimeJob { setRuntimeOption("ascii_mode", ascii) }
        }
        windowManager.attachWindow(keyboardWindow)
    }

    private fun actionLabel(): String {
        val action = service.currentInputEditorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        return when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
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
