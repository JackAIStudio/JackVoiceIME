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
 * 状态机：空闲 → 录音 → 处理 → 上屏 → 回到键盘。
 * 录音与识别接在 [VoiceController] 上（下一步接入豆包流式）。
 */
class VoiceWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow {
    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()

    private var panel: VoicePanelView? = null
    private var state = VoiceState.Idle
    private var task: Job? = null

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = VoiceWindow

    override fun onCreateView(): View {
        val view = VoicePanelView(context)
        view.onOrbTap = { onOrbTap() }
        view.onKeyboardTap = { windowManager.attachWindow(keyboardWindow) }
        view.onBackspaceTap = { service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        view.onActionTap = { performEditorAction() }
        panel = view
        return view
    }

    override fun onAttached() {
        setState(VoiceState.Idle)
        panel?.setActionLabel(actionLabel())
    }

    override fun onDetached() {
        task?.cancel()
        task = null
        setState(VoiceState.Idle)
    }

    private fun setState(next: VoiceState) {
        state = next
        panel?.setState(next)
    }

    private fun onOrbTap() {
        when (state) {
            VoiceState.Idle -> {
                setState(VoiceState.Recording)
            }

            VoiceState.Recording -> {
                setState(VoiceState.Thinking)
                task?.cancel()
                task =
                    service.lifecycleScope.launch {
                        // 占位：真实流程是「停止录音 → 等豆包二遍终稿 → 上屏」
                        delay(1500)
                        setState(VoiceState.Idle)
                    }
            }

            VoiceState.Thinking -> Unit
        }
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
