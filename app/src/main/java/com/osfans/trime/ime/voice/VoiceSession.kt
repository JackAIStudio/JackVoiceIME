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
 *                    ├── VoiceWindow（面板 UI）
 *                    └── KeyboardWindow 顶栏（🎙 变色）
 * ```
 *
 * 上屏策略：语音终稿到达时如果用户**正在拼写**，先把结果排进 [pending]，
 * 等这一轮组合结束（选完候选 / 清空）再插到光标处，不打断手动输入。
 * 键盘收起期间到达的结果同样先存着，下次展开键盘再落。
 *
 * ## 前台服务只有这里开、也只有这里关
 *
 * 以前 `start()` 先拉起 [VoiceCaptureService]、`startRecording()` 失败再立刻
 * `stopService()`，两次调用是异步排队进 AMS 的：谁先到不确定。晚了半步的
 * start 会把服务重新拉起来、`goForeground()` 挂上「正在聆听」，
 * 而这一轮早就结束了，于是通知栏里永远停着「正在聆听」。
 * 现在录音的起、停、取消、异常、输入法销毁**都走 [finishCapture]** 一个出口，
 * 服务跟在会话状态后面走，不再各说各话。
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

    /**
     * 前台服务该不该活着：只有真的在录音 / 等终稿时才算。
     *
     * [VoiceCaptureService] 用它挡掉迟到的启动请求，也用它决定要不要撤通知。
     */
    fun isCapturing(): Boolean = VoiceEngine.isRecording || VoiceEngine.isWaitingFinal

    /** 录音中最后一段流式结果：切回面板时用它把预览恢复出来。 */
    @Volatile
    var lastPartial: String = ""
        private set

    /**
     * 最近一次失败、且音频还在的那个条目。
     *
     * 面板据此在麦克风旁边亮出「重试」——不用用户自己去历史页翻。
     * 用户点了重试、或者重新录了一条，这里就清掉。
     */
    @Volatile
    var retryEntryId: String? = null
        private set

    /** 已识别、但因为用户在拼写 / 键盘收起而暂未上屏的文本。 */
    private val pending = StringBuilder()

    val hasPending: Boolean get() = pending.isNotEmpty()

    /** 输入法服务创建时绑定。会话活多久，听写就能挂多久。 */
    fun bind(svc: TrimeInputMethodService) {
        service = svc
        VoiceEngine.setListener(this)
        // 输入法被系统回收又重新起来时，上一轮失败的录音还在：把重试入口接回来。
        if (retryEntryId == null) {
            retryEntryId =
                runCatching { VoiceHistory.retryable(svc).firstOrNull()?.id }.getOrNull()
        }
        // 进程里的引擎可能还在上一轮（服务重建、窗口重建）：状态跟着引擎走。
        state =
            when {
                VoiceEngine.isRecording -> VoiceState.Recording
                VoiceEngine.isWaitingFinal -> VoiceState.Thinking
                else -> VoiceState.Idle
            }
        notifyState()
    }

    /**
     * 输入法服务销毁。
     *
     * **必须把前台服务也停掉**：只取消引擎的话，麦克风前台服务还活着，
     * 通知栏里那条「正在聆听」就一直挂着——用户明明早说完了。
     */
    fun unbind() {
        val svc = service
        service = null
        VoiceEngine.setListener(null)
        if (VoiceEngine.isRecording || VoiceEngine.isWaitingFinal) {
            // 输入法都销毁了，没人能接结果：别让它继续占麦克风。
            VoiceEngine.cancel()
        }
        // 同一条收尾路径：停录音、停前台服务、还音频焦点。
        finishCapture(svc)
        // 同样别占着别人的音频。焦点虽由系统兜底回收，主动还更及时。
        DictationAudioFocus.release()
        state = VoiceState.Idle
        lastPartial = ""
        pending.setLength(0)
        retryEntryId = null
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

        retryEntryId = null
        VoiceCaptureService.start(svc)
        if (!VoiceEngine.startRecording(svc)) {
            // 麦克风没起来：前台服务也要一起收掉，否则通知栏会留下一条
            // 没人负责关的「正在聆听」（这就是之前那个 bug 的来源之一）。
            finishCapture(svc)
            return
        }
        // 麦克风已经拿到，再按住别的 App 的音频。拿不到焦点只是背景音按不住，
        // 不影响本轮录音。
        DictationAudioFocus.acquire(svc) { cancel() }
        lastPartial = ""
        state = VoiceState.Recording
        notifyState()
    }

    /** 停止录音，等服务端终稿。此时可以继续用手动键盘打字。 */
    fun stop() {
        val svc = service ?: return
        if (state != VoiceState.Recording) return
        // 以引擎为准：面板显示在录，但引擎可能已经自己收场了（出错后 finishWithError
        // 已经把 isRecording 置 false）。这时 stopRecording() 会在开了守卫后直接返回，
        // 超时兜底也就挂不上，状态会永远停在 Thinking。直接收尾回空闲。
        if (!VoiceEngine.isRecording) {
            VoiceEngine.cancel()
            finishCapture(svc)
            state = VoiceState.Idle
            lastPartial = ""
            notifyState()
            return
        }
        state = VoiceState.Thinking
        notifyState()
        VoiceEngine.stopRecording()
        // 麦克风已经停，立刻把声音还给别的 App——它们没必要陪着等文字整理完。
        DictationAudioFocus.release()
        // 前台服务的意义只是「让我们有资格拿麦克风」；音频已经落盘，
        // 剩下的等终稿不需要它了。早点停掉，通知栏那条也就跟着消失。
        VoiceCaptureService.stop(svc)
    }

    /** 直接丢弃这一轮（通知栏「取消」/ 输入法销毁）。 */
    fun cancel() {
        VoiceEngine.cancel()
        finishCapture(service)
        state = VoiceState.Idle
        lastPartial = ""
        retryEntryId = null
        pending.setLength(0)
        notifyState()
    }

    /**
     * 收掉麦克风那条前台服务。**所有结束路径的最后一步都走这里**，
     * 保证不会出现「会话已经空闲、服务还挂着」的状态。
     */
    private fun finishCapture(svc: TrimeInputMethodService?) {
        DictationAudioFocus.release()
        svc?.let { VoiceCaptureService.stop(it) }
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
        // 替换词和标点已经在 VoiceEngine.finish 里处理过，这里只负责上屏。
        deliver(text)
    }

    override fun onError(message: String) {
        lastPartial = ""
        state = VoiceState.Idle
        notifyState()
        // 这一轮如果存下了音频，把「重试」入口递到界面上——不用用户去历史页翻。
        val failed = VoiceEngine.failedEntryId
        if (failed.isNotEmpty()) retryEntryId = failed
        activeListeners().forEach { it.onVoiceNotice("识别失败：$message") }
    }

    /**
     * 没转出文字，但声音还在。
     *
     * 这不是「错误」，是一句还没来得及变成字的话：给一句说明，并把
     * 「重新识别这段录音」亮出来。录音一直都在本地，随时能再试。
     */
    override fun onRetryable(
        entryId: String,
        message: String,
    ) {
        lastPartial = ""
        state = VoiceState.Idle
        retryEntryId = entryId
        notifyState()
        activeListeners().forEach { it.onVoiceNotice("$message，录音已保存，可重新识别") }
    }

    // ── 重新识别（失败的那条录音还在，用户点一下再试）──────────────────

    /**
     * 重新识别 [retryEntryId] 指向的那段录音。
     *
     * @return false 表示没跑起来，原因已经 toast 出去。
     */
    fun retryLast(): Boolean {
        val id = retryEntryId
        if (id == null) {
            toast("没有可重新识别的录音")
            return false
        }
        return retry(id)
    }

    /** 重新识别指定条目；成功就把文字当作一次正常听写落进输入框。 */
    fun retry(entryId: String): Boolean {
        val svc = service ?: return false
        val started =
            VoiceTranscriber.transcribe(
                svc,
                entryId,
                object : VoiceTranscriber.Callback {
                    override fun onDone(
                        entryId: String,
                        text: String,
                    ) {
                        // 补出来了就不再挂着那个按钮。
                        if (retryEntryId == entryId) retryEntryId = null
                        VoiceHistory.markCompleted(svc, entryId, text, 0L)
                        notifyState()
                        activeListeners().forEach { it.onVoiceNotice("已补上：$text") }
                        deliver(text)
                    }

                    override fun onFailed(
                        entryId: String,
                        message: String,
                        retryable: Boolean,
                    ) {
                        // 写进记录：这段确实没声音/识别不了，别让按钮一直亮着骗人点。
                        VoiceHistory.markFailed(svc, entryId, VoiceHistory.Status.Empty, message)
                        if (retryable) {
                            retryEntryId = entryId
                        } else if (retryEntryId == entryId) {
                            retryEntryId = null
                        }
                        notifyState()
                        activeListeners().forEach { it.onVoiceNotice("重新识别失败：$message") }
                    }
                },
            )
        if (started) {
            val entry = runCatching { VoiceHistory.find(svc, entryId) }.getOrNull()
            val whenText =
                entry?.let {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(it.atMillis))
                }
            val line = if (whenText != null) "正在重新识别 $whenText 那段" else "正在重新识别这段录音"
            activeListeners().forEach { it.onVoiceNotice("$line…") }
        }
        return started
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
        // 通知栏的标题跟着状态换：「正在聆听」不该在等终稿时还挂着。
        runCatching { VoiceCaptureService.refresh() }
    }

    private fun toast(message: String) {
        val svc = service ?: return
        runCatching { Toast.makeText(svc, message, Toast.LENGTH_SHORT).show() }
    }
}
