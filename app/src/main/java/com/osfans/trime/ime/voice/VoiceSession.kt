/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.widget.Toast
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber
import java.util.Collections
import java.util.WeakHashMap

/** 听写会话的三种状态。面板和键盘顶栏都看这一份，不再各记各的。 */
enum class VoiceState { Idle, Recording, Thinking }

/**
 * 听写会话：**与键盘模块无关的常驻服务**。
 *
 * 以前录音、识别、上屏都挂在 [VoiceWindow] 上，切到拼音 / EN 时窗口 detach，
 * `cancel()` 跟着把录音一起掐了——用户点着说话切去拼一个字，回来语音就没了。
 * 现在会话挂在输入法服务上，面板只是它的一个观察者：
 *
 * ```
 * VoiceCaptureService（前台，只为拿麦克风）
 *        └── VoiceEngine（录音 + 豆包流式 + 终稿）
 *              └── VoiceSession（状态 + 上屏 + 待插入队列）  ← 常驻
 *                    ├── VoiceWindow（语音面板 UI）
 *                    └── KeyboardWindow 顶栏（录音中指示）
 * ```
 *
 * 上屏策略：语音终稿到达时如果用户**正在拼写**，先把结果排进 [pending]，
 * 等这一轮组合结束（选完候选 / 清空）再插到光标处，不打断手动输入。
 * 键盘收起期间到达的结果同样先存着，下次展开键盘再落。
 */
object VoiceSession : VoiceEngine.Listener {
    interface Listener {
        fun onVoiceStateChanged(state: VoiceState)

        /** 流式结果，面板用它做实时预览。 */
        fun onVoicePartial(text: String) {}

        /** 实时音量，面板录音圆用它画波形。 */
        fun onVoiceLevel(level: Float) {}

        /** 一行提示（识别失败 / 结果已就绪等）。 */
        fun onVoiceNotice(text: String) {}
    }

    private var service: TrimeInputMethodService? = null

    /**
     * 窗口是短期对象（主题切换会重建 InputView），用弱引用收着：
     * 窗口正常 detach 时会自己摘掉；漏掉的那个也不会把整个窗口钉在内存里。
     */
    private val listeners: MutableSet<Listener> =
        Collections.newSetFromMap(WeakHashMap<Listener, Boolean>())

    @Volatile
    var state: VoiceState = VoiceState.Idle
        private set

    val isBound: Boolean get() = service != null

    /** 录音中最后一段流式结果：切回面板时用它把预览恢复出来。 */
    @Volatile
    var lastPartial: String = ""
        private set

    /** 已识别、但因为用户在拼写 / 键盘收起而暂未上屏的文本。 */
    private val pending = StringBuilder()

    val hasPending: Boolean get() = pending.isNotEmpty()

    /** 输入法服务创建时绑定。会话活多久，听写就能挂多久。 */
    fun bind(svc: TrimeInputMethodService) {
        service = svc
        VoiceEngine.setListener(this)
        // 进程里的引擎可能还在上一轮（服务重建、窗口重建）：状态跟着引擎走。
        state =
            when {
                VoiceEngine.isRecording -> VoiceState.Recording
                VoiceEngine.isWaitingFinal -> VoiceState.Thinking
                else -> VoiceState.Idle
            }
        notifyState()
    }

    fun unbind() {
        service = null
        VoiceEngine.setListener(null)
        if (VoiceEngine.isRecording || VoiceEngine.isWaitingFinal) {
            // 输入法都销毁了，没人能接结果：别让它继续占麦克风。
            VoiceEngine.cancel()
        }
        state = VoiceState.Idle
        lastPartial = ""
        pending.setLength(0)
        listeners.clear()
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onVoiceStateChanged(state)
        if (lastPartial.isNotEmpty()) listener.onVoicePartial(lastPartial)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** 弱引用集合在遍历中可能被回收，回调前先取一份活的快照。 */
    private fun activeListeners(): List<Listener> = listeners.toList()

    // ── 会话控制（面板的麦克风键 / 通知栏都走这里）──────────────────────

    /** 开始一轮听写。已经录着 / 等终稿时忽略。 */
    fun start() {
        val svc = service ?: return
        if (state != VoiceState.Idle) return
        // 界面状态偶尔会和引擎错开一拍：以引擎为准，别把正在录的那一轮掐掉。
        if (VoiceEngine.isRecording || VoiceEngine.isWaitingFinal) {
            state =
                if (VoiceEngine.isRecording) VoiceState.Recording else VoiceState.Thinking
            notifyState()
            return
        }
        // 上一轮还没落完的结果先插进去，别被新记录覆盖。
        flushPending(force = true)

        VoiceCaptureService.start(svc)
        if (!VoiceEngine.startRecording(svc)) {
            VoiceCaptureService.stop(svc)
            return
        }
        lastPartial = ""
        state = VoiceState.Recording
        notifyState()
    }

    /** 停止录音，等服务端终稿。此时可以继续用手动键盘打字。 */
    fun stop() {
        val svc = service ?: return
        if (state != VoiceState.Recording) return
        state = VoiceState.Thinking
        notifyState()
        VoiceEngine.stopRecording()
        VoiceCaptureService.stop(svc)
    }

    /** 直接丢弃这一轮（通知栏「取消」/ 输入法销毁）。 */
    fun cancel() {
        VoiceEngine.cancel()
        service?.let { VoiceCaptureService.stop(it) }
        state = VoiceState.Idle
        lastPartial = ""
        pending.setLength(0)
        notifyState()
    }

    // ── VoiceEngine 回调（引擎保证在主线程）────────────────────────────

    override fun onPartial(text: String) {
        lastPartial = text
        activeListeners().forEach { it.onVoicePartial(text) }
    }

    override fun onLevel(level: Float) {
        activeListeners().forEach { it.onVoiceLevel(level) }
    }

    override fun onFinal(text: String) {
        val svc = service
        lastPartial = ""
        state = VoiceState.Idle
        notifyState()

        if (svc == null) {
            Timber.w("听写终稿到达时输入法已销毁，丢弃 ${text.length} 字")
            return
        }
        if (text.isBlank()) return
        val processed = PostProcessor.process(text, svc)
        if (processed.isBlank()) return
        deliver(processed)
    }

    override fun onError(message: String) {
        lastPartial = ""
        state = VoiceState.Idle
        notifyState()
        activeListeners().forEach { it.onVoiceNotice("识别失败：$message") }
    }

    // ── 上屏 ────────────────────────────────────────────────────────────

    /**
     * 结果落到输入框：
     *  - 键盘收起 / 正在拼写 → 排队，别打断用户；
     *  - 否则直接插到光标处。
     */
    private fun deliver(text: String) {
        val svc = service ?: return
        if (!svc.isVoiceTargetReady() || svc.currentComposingText.isNotEmpty()) {
            val firstPending = pending.isEmpty()
            pending.append(text)
            if (firstPending) {
                activeListeners().forEach { it.onVoiceStateChanged(state) }
                toast("语音已识别，稍后自动插入")
            }
            return
        }
        commit(text, svc)
    }

    /**
     * 把排队的结果插进去。
     *
     * @param force 用户明确要结果（切回语音面板 / 开始新录音）时，允许先结束
     *              正在拼的组合再插入；普通回调只等组合自己结束。
     */
    fun flushPending(force: Boolean) {
        val svc = service ?: return
        if (pending.isEmpty()) return
        if (!svc.isVoiceTargetReady()) return
        if (svc.currentComposingText.isNotEmpty()) {
            if (!force) return
            svc.finishComposingForVoice()
        }
        val text = pending.toString()
        pending.setLength(0)
        commit(text, svc)
        activeListeners().forEach { it.onVoiceStateChanged(state) }
    }

    private fun commit(
        text: String,
        svc: TrimeInputMethodService,
    ) {
        svc.commitText(text)
    }

    /** 组合结束、键盘重新展开这类时机由 IME 服务回调过来。 */
    fun onComposingEnded() {
        if (pending.isEmpty()) return
        flushPending(force = false)
    }

    private fun notifyState() {
        activeListeners().forEach { it.onVoiceStateChanged(state) }
    }

    private fun toast(message: String) {
        val svc = service ?: return
        runCatching { Toast.makeText(svc, message, Toast.LENGTH_SHORT).show() }
    }
}
