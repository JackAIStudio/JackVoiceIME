/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber
import java.util.Collections
import java.util.WeakHashMap

/**
 * 听写会话的三种状态。面板和键盘顶栏都看这一份，不再各记各的。
 *
 * 「没网」不是第四种状态：[VoiceEngine] 会在没网时照样录音，界面仍然显示
 * [Recording]，只是通过 [VoiceSession.asrDegraded] 把提示语换成「先录着」。
 * 状态机只描述**麦克风在干什么**，网络的好坏是它的注脚，不是它的一个分支。
 *
 * 重新识别的预览也不是状态：它走 [VoiceSession.preview]，面板在空闲态上
 * 叠一张卡片，麦克风仍然是 [Idle]——用户随时可以直接再录一条。
 */
enum class VoiceState { Idle, Recording, Thinking }

/**
 * 重新识别出来的文字，等用户决定要不要。
 *
 * **不自动上屏**。重新转写是一条补救路径（当时没网 / 没等到终稿），
 * 不是日常热路径：它可能要等好几秒，结果也可能不是用户想要的。
 * 直接塞进输入框会打断用户已经打的内容，而且没法反悔。
 *
 * 所以流程是：出结果 → 面板上给一张卡片 → 用户点「插入」才落进输入框。
 * 卡片是**只读**的：改字该在输入框里改，在面板上编辑会引入一套
 * 和 Rime 组合输入打架的状态，不值当。
 */
data class VoicePreview(
    val entryId: String,
    val text: String,
)

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

    /** 主线程队列：识别回调来自 OkHttp 线程，见 [post]。 */
    private val main = Handler(Looper.getMainLooper())

    /** 「正在重新识别」最多让用户等这么久；到点强制恢复可操作。 */
    private const val RETRY_UI_TIMEOUT_MS = 30_000L

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
     * 这一轮只录了音、没有识别（没网，或者连接中途断了）。
     *
     * 面板据此把那句「正在聆听」换成「先录着，停下后再识别」：
     * 用户得知道字不会实时出来，而不是对着一个不出字的界面猜。
     */
    @Volatile
    var asrDegraded: Boolean = false
        private set

    /** 断掉的原因（已是可以直接看的措辞）。没断时是空串。 */
    @Volatile
    var asrNotice: String = ""
        private set

    /**
     * 这一轮**拿不到麦克风**的原因；`null` 表示声音正常。
     *
     * 和 [asrDegraded] 并列但不同：那个是「有声音、没字」，这个是
     * 「连声音都没有」。两者可能同时成立（没网 + 麦克风被占），
     * 面板优先显示更致命的那条——没有声音，说什么都没用。
     */
    @Volatile
    var micBlocked: MicStatus.Reason? = null
        private set

    /**
     * 重新识别出来、还没被用户确认的文字。非空时面板会盖一张预览卡片。
     *
     * 只在 [VoiceState.Idle] 有意义：录音 / 等终稿时用户已经表达了别的意图，
     * 那张卡片会被 [start] / [cancel] 主动收掉。
     */
    @Volatile
    var preview: VoicePreview? = null
        private set

    /** 正在跑重新识别的那条记录。界面据此显示「正在重新识别…」。 */
    @Volatile
    var retryingEntryId: String? = null
        private set

    /** 本地重识别进度 0..1；云端没有分片进度，保持 0。 */
    @Volatile
    var retryProgress: Float = 0f
        private set

    /** 重新识别的 UI 看门狗：到点还没回调就主动收掉，别让面板一直转。 */
    private var retryWatchdog: Runnable? = null

    /**
     * 最近一次失败、且音频还在的那个条目。
     *
     * 面板据此在麦克风旁边亮出「重试」——不用用户自己去历史页翻。
     *
     * **不再只靠内存里的一个 id**：以前这个字段在 `unbind()` 里被清空、
     * 只在 `bind()` 时补一次，输入法服务被回收/重建的时机稍有偏差，
     * 刷新键就再也不出现了（用户实测：历史里有 11 条可重试，面板上却没有那颗键）。
     * 现在读的时候顺手回退到历史里真正的第一条可重试记录，
     * 只要磁盘上有，键就在。
     */
    val retryEntryId: String?
        get() {
            inMemoryRetryId?.let { return it }
            val ctx = service ?: return null
            val id = runCatching { VoiceHistory.retryable(ctx).firstOrNull()?.id }.getOrNull()
            if (id != null) inMemoryRetryId = id
            return id
        }

    /** 缓存：避免每次读都扫一遍历史文件。清空时置 null，下次读会重新算。 */
    @Volatile
    private var inMemoryRetryId: String? = null

    /** 已识别、但因为用户在拼写 / 键盘收起而暂未上屏的文本。 */
    private val pending = StringBuilder()

    val hasPending: Boolean get() = pending.isNotEmpty()

    /** 输入法服务创建时绑定。会话活多久，听写就能挂多久。 */
    fun bind(svc: TrimeInputMethodService) {
        service = svc
        VoiceEngine.setListener(this)
        // 上一轮如果是在「音量已归零」的状态下被杀掉的，这里把音量还回去。
        // 放在最早的位置：越早恢复，用户越不容易先碰到「手机没声音」。
        DictationAudioMute.recoverIfStuck(svc)
        // 输入法被系统回收又重新起来时，上一轮失败的录音还在。
        // 缓存清掉，下次读 retryEntryId 会自己去历史里找（见那个 getter）。
        inMemoryRetryId = null
        // 进程里的引擎可能还在上一轮（服务重建、窗口重建）：状态跟着引擎走。
        state =
            when {
                VoiceEngine.isRecording -> VoiceState.Recording
                VoiceEngine.isWaitingFinal -> VoiceState.Thinking
                else -> VoiceState.Idle
            }
        asrDegraded = VoiceEngine.mode == VoiceEngine.Mode.Offline
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
        // 同一条收尾路径：停录音、停前台服务、还音频。
        finishCapture(svc)
        state = VoiceState.Idle
        lastPartial = ""
        asrDegraded = false
        micBlocked = null
        asrNotice = ""
        pending.setLength(0)
        inMemoryRetryId = null
        // 输入法都没了，正在跑的那条转写也没人接结果：连进度标记一起清掉。
        disarmRetryWatchdog()
        retryingEntryId = null
        preview = null
        // 转写任务本身也要掐掉，否则它跑完还会回调一个已经销毁的 svc。
        runCatching { VoiceTranscriber.cancel() }
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
        // 预览卡片还开着就开始新录音：用户显然已经不要那段字了。
        preview = null

        inMemoryRetryId = null
        micBlocked = null

        // 开录前先问一句「现在拿得到麦克风吗」。注意只拦**确定拿不到**的两种情况
        // （没权限 / 系统总开关关了）：别的应用正在录并不等于我们录不到，
        // 普通应用之间还看界面优先级，贸然拦下来会挡住本来能用的场景。
        val probe = MicStatus.probeBeforeStart(svc)
        if (probe.reason == MicStatus.Reason.PermissionDenied || probe.reason == MicStatus.Reason.MicMuted) {
            micBlocked = probe.reason
            state = VoiceState.Idle
            notifyState()
            activeListeners().forEach { it.onVoiceNotice(probe.reason!!.message) }
            return
        }

        VoiceCaptureService.start(svc)
        if (!VoiceEngine.startRecording(svc)) {
            // 麦克风没起来：前台服务也要一起收掉，否则通知栏会留下一条
            // 没人负责关的「正在聆听」（这就是之前那个 bug 的来源之一）。
            finishCapture(svc)
            return
        }
        // 麦克风已经拿到，再按住别的 App 的声音。按不住只是背景音还在响，
        // 不影响本轮录音——直播类应用要靠音量那一层才压得住，见 [DictationAudioMute]。
        DictationAudioMute.engage(svc) { cancel() }
        lastPartial = ""
        // 没网时这一轮照样录，只是不出字：把这件事先摆到界面上。
        asrDegraded = VoiceEngine.mode == VoiceEngine.Mode.Offline
        asrNotice = if (asrDegraded) "网络没连上" else ""
        // 别人已经在录，而且我们还没被静音：现在还能用，但值得先提醒一句。
        if (probe.others > 0) {
            activeListeners().forEach {
                it.onVoiceNotice(
                    if (probe.privacySensitive) {
                        "有应用正在占用麦克风，可能随时被静音"
                    } else {
                        "有其他应用在录音"
                    },
                )
            }
        }
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
        DictationAudioMute.release()
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
        asrDegraded = false
        micBlocked = null
        asrNotice = ""
        inMemoryRetryId = null
        preview = null
        disarmRetryWatchdog()
        retryingEntryId = null
        pending.setLength(0)
        notifyState()
    }

    /**
     * 收掉麦克风那条前台服务。**所有结束路径的最后一步都走这里**，
     * 保证不会出现「会话已经空闲、服务还挂着」的状态。
     */
    private fun finishCapture(svc: TrimeInputMethodService?) {
        // 音量归零必须在这里恢复：这是**唯一**一条所有结束路径都经过的出口。
        // 漏掉任何一条，用户的手机就会一直静音——比背景音吵难受得多。
        DictationAudioMute.release()
        svc?.let { VoiceCaptureService.stop(it) }
    }

    // ── VoiceEngine 回调（引擎保证在主线程）────────────────────────────

    override fun onPartial(text: String) {
        lastPartial = text
        activeListeners().forEach { it.onVoicePartial(text) }
        // 通知栏也摆一份实时文字。走节流入口：识别结果一秒十几条，
        // 每条都 notify 会让通知栏一直重建、锁屏跟着闪。
        runCatching { VoiceCaptureService.refreshPreview() }
    }

    override fun onLevel(level: Float) {
        activeListeners().forEach { it.onVoiceLevel(level) }
    }

    override fun onFinal(text: String) {
        val svc = service
        lastPartial = ""
        state = VoiceState.Idle
        asrDegraded = false
        micBlocked = null
        asrNotice = ""
        // 终稿到达 = 这一轮结束。**必须在这里收掉前台服务**：
        // 以前只有 stop() / cancel() 会收，而引擎自己走完（终稿、出错、超时兜底）
        // 时没人管，于是麦克风前台服务一直挂着、通知栏那条「正在聆听」再也不走。
        // 用户看到的正是「明明没在听了，下拉还写着正在聆听」。
        finishCapture(svc)
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
        asrDegraded = false
        micBlocked = null
        asrNotice = ""
        // 出错同样是「这一轮结束了」：前台服务不该比录音活得久。
        finishCapture(service)
        notifyState()
        // 这一轮如果存下了音频，把「重试」入口递到界面上——不用用户去历史页翻。
        val failed = VoiceEngine.failedEntryId
        if (failed.isNotEmpty()) inMemoryRetryId = failed
        activeListeners().forEach { it.onVoiceNotice("识别失败：${VoiceFailure.describe(message)}") }
    }

    /**
     * 识别这条线断了，**录音还在继续**。
     *
     * 这里**不能**碰状态机、也**不能**收前台服务：麦克风还得开着，
     * 用户说的话还要落盘。只把「暂时不出字」这件事告诉界面。
     */
    override fun onAsrDegraded(message: String) {
        asrDegraded = true
        asrNotice = VoiceFailure.describe(message)
        lastPartial = ""
        activeListeners().forEach {
            it.onVoicePartial("")
            it.onVoiceStateChanged(state)
        }
    }

    /**
     * 麦克风拿不到，但录音还在继续。
     *
     * 和 [onAsrDegraded] 是同一类事——「这一轮拿不到东西，原因不在你」——
     * 所以走同一条通道，只是原因不同。**别动状态机、别收前台服务**：
     * 用户可能正说着，对方一松手声音就回来了。
     */
    override fun onMicBlocked(reason: MicStatus.Reason) {
        micBlocked = reason
        lastPartial = ""
        activeListeners().forEach {
            it.onVoicePartial("")
            it.onVoiceStateChanged(state)
        }
        // 权限和总开关这两种，光在面板上打字不够——用户未必正盯着面板。
        if (reason.pointsToIndicator || reason == MicStatus.Reason.PermissionDenied) {
            activeListeners().forEach { it.onVoiceNotice(reason.message) }
        }
    }

    override fun onMicRecovered() {
        if (micBlocked == null) return
        micBlocked = null
        activeListeners().forEach { it.onVoiceStateChanged(state) }
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
        asrDegraded = false
        micBlocked = null
        asrNotice = ""
        inMemoryRetryId = entryId
        // 同样要收掉前台服务：这一轮已经结束，只是没转出字而已。
        finishCapture(service)
        notifyState()
        activeListeners().forEach { it.onVoiceNotice("$message，可重新识别") }
    }

    // ── 重新识别（失败的那条录音还在，用户点一下再试）──────────────────

    /**
     * 清掉「正在重新识别」这个标记。
     *
     * 给界面用：当标记还在、但 [VoiceTranscriber] 已经没有任务在跑时，
     * 说明这个标记是残留（服务重建、回调丢失）。留着它界面会永远停在
     * 「正在重新识别…」，看起来就是卡死。
     */
    fun clearStaleRetrying() {
        if (retryingEntryId == null) return
        disarmRetryWatchdog()
        retryingEntryId = null
        notifyState()
    }

    /**
     * 输入法收起：本地模型释放内存。
     *
     * 只在**没有**在录音 / 等终稿 / 重识别时做；否则会把正在跑的任务的 recognizer 抽走。
     */
    fun onImeHidden() {
        if (state != VoiceState.Idle || retryingEntryId != null) return
        LocalAsrEngine.close()
        retryProgress = 0f
    }

    /**
     * 用户点「取消」或 UI 看门狗到点：立刻把界面从「正在重新识别…」里拉出来。
     *
     * 不能只依赖 [VoiceTranscriber] 的回调——它可能在等一个永远不来的 WebSocket
     * 终稿；界面必须先恢复，任务在后台慢慢收。
     */
    fun cancelRetry() {
        val id = retryingEntryId ?: return
        disarmRetryWatchdog()
        retryingEntryId = null
        retryProgress = 0f
        inMemoryRetryId = id
        runCatching { VoiceTranscriber.cancel() }
        notifyState()
        activeListeners().forEach { it.onVoiceNotice("已取消重新识别") }
    }

    private fun armRetryWatchdog(entryId: String) {
        disarmRetryWatchdog()
        val watchdog =
            Runnable {
                if (retryingEntryId != entryId) return@Runnable
                Timber.w("重新识别看门狗触发，界面强制恢复：$entryId")
                retryingEntryId = null
                retryProgress = 0f
                inMemoryRetryId = entryId
                runCatching { VoiceTranscriber.cancel() }
                notifyState()
                activeListeners().forEach { it.onVoiceNotice("重新识别超时，已停止，可重试") }
            }
        retryWatchdog = watchdog
        main.postDelayed(watchdog, RETRY_UI_TIMEOUT_MS)
    }

    private fun disarmRetryWatchdog() {
        retryWatchdog?.let { main.removeCallbacks(it) }
        retryWatchdog = null
    }

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

    /**
     * 重新识别指定条目。
     *
     * **成功也不自动上屏**：结果放进 [preview]，由用户点「插入」才落进输入框。
     * 理由见 [VoicePreview]——这是补救路径，不是热路径。
     */
    fun retry(entryId: String): Boolean {
        val svc = service ?: return false
        val started =
            VoiceTranscriber.transcribe(
                svc,
                entryId,
                object : VoiceTranscriber.Callback {
                    // 回调来自 OkHttp / 本地解码线程：状态、上屏、通知都得回主线程再动。
                    override fun onProgress(
                        entryId: String,
                        progress: Float,
                    ) {
                        post {
                            if (retryingEntryId != entryId) return@post
                            retryProgress = progress
                            notifyState()
                        }
                    }

                    override fun onDone(
                        entryId: String,
                        text: String,
                    ) {
                        post {
                            // 用户已经点了取消 / 看门狗已经收尾：结果不要了。
                            if (retryingEntryId != entryId) return@post
                            disarmRetryWatchdog()
                            // 补出来了就不再挂着那个按钮。
                            if (inMemoryRetryId == entryId) inMemoryRetryId = null
                            retryingEntryId = null
                            retryProgress = 0f
                            VoiceHistory.markCompleted(svc, entryId, text, 0L)
                            // 文字已经进记录了，用户随时能在历史页看到；
                            // 这里只是把「要不要现在插进输入框」交还给他。
                            preview = VoicePreview(entryId, text)
                            notifyState()
                        }
                    }

                    override fun onFailed(
                        entryId: String,
                        message: String,
                        retryable: Boolean,
                    ) {
                        post {
                            // 界面已经先恢复了（取消 / 超时）：迟到的失败回调不要再改写。
                            if (retryingEntryId != entryId) return@post
                            disarmRetryWatchdog()
                            retryingEntryId = null
                            retryProgress = 0f
                            // 写进记录时要**如实**分状态。
                            //
                            // 这里原来一律写 Status.Empty（「没听到人声」）。可 Empty 在
                            // canRetry 里是被排除的——于是一次网络抖动就把这条录音
                            // 永久判死：历史页说「这段里没听到人声」，重试按钮再也不出现，
                            // 而音频其实好好的（实测 01:29 那条有 57% 的块是明显人声）。
                            // 现在只有服务端真的说「这段没人说话」才记 Empty。
                            VoiceHistory.markFailed(
                                svc,
                                entryId,
                                VoiceHistory.statusAfterRetryFailure(message, retryable),
                                message,
                            )
                            if (retryable) {
                                inMemoryRetryId = entryId
                            } else {
                                // 这条被判成「没人说话」了，但**别的录音还在**。
                                // 以前这里只清掉「等于本条」的缓存，而缓存本来就指着本条，
                                // 清完不会去历史里找下一条——刷新键就整个消失，
                                // 明明还有 9 条可重试，用户却看不到入口。
                                // 统一置空，让 getter 去历史里重新挑一条。
                                inMemoryRetryId = null
                            }
                            notifyState()
                            activeListeners().forEach {
                                it.onVoiceNotice("重新识别失败：${VoiceFailure.describe(message)}")
                            }
                        }
                    }
                },
            )
        if (started) {
            retryingEntryId = entryId
            retryProgress = 0f
            armRetryWatchdog(entryId)
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
     * 用户点了「插入」：把预览的文字落进输入框。
     *
     * 走和正常终稿同一条 [deliver] 路径，所以「正在拼写时排队」那套规则一样生效。
     */
    fun commitPreview() {
        val p = preview ?: return
        preview = null
        notifyState()
        deliver(p.text)
    }

    /** 用户点了「丢弃」：文字已经存进历史，这里只是不插进输入框。 */
    fun discardPreview() {
        if (preview == null) return
        preview = null
        notifyState()
    }

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

    /**
     * 把回调切回主线程。
     *
     * [VoiceTranscriber] 的回调来自 OkHttp 线程；会话里的状态、上屏、
     * 通知刷新都假定在主线程（监听者是 View）。在后台线程直接改，
     * 轻则界面不刷新，重则 `CalledFromWrongThreadException`。
     */
    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun toast(message: String) {
        val svc = service ?: return
        runCatching { Toast.makeText(svc, message, Toast.LENGTH_SHORT).show() }
    }
}
