/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * 录音：16kHz / 16bit / 单声道 PCM —— 正是豆包流式识别要的格式。
 *
 * 音源用 `VOICE_RECOGNITION`（与 Xime 那条已验证链路一致）：它关掉 AGC 之类的
 * 通话后处理，识别率比 `MIC` 更好。
 *
 * 录音跑在独立线程，读到一块就回调一块；调用方负责把数据喂给识别客户端。
 */
class VoiceRecorder(
    /** 用来查「我是不是被系统静音了」；传 null 就只靠全零判定。 */
    private val appContext: android.content.Context? = null,
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    /** 每块 PCM 的音量（0..1），录音线程回调；波形靠它，不能再用假动画。 */
    private val onLevel: (Float) -> Unit = {},
    /**
     * 麦克风拿不到时的回调，参数是要直接给用户看的一句话。
     *
     * 和 [onError] 分开：[onError] 是「这一轮到此为止」，这里是
     * 「录不了了，而且我能说出大概为什么」——调用方据此决定要不要留这段音频。
     */
    private val onMicBlocked: (MicStatus.Reason) -> Unit = {},
    /** 麦克风恢复出声了，提示该撤掉。[onMicBlocked] 的反面，同样在录音线程回调。 */
    private val onMicRecovered: () -> Unit = {},
) {
    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    /** 每次录音自己跟一条底噪线：安静时必须是 0，不能让底噪把柱子顶起来。 */
    private val levelMeter = VoiceLevel()

    /**
     * 连续全零的判定：系统把麦克风静音时 `read()` 不报错、老实返回零，
     * 只能靠数「连续多久一个字都没有」来区分「安静」和「拿不到」。
     */
    private val silence = SilenceGate()

    /** 系统通知我们「被静音了」。回调在别的线程，用 volatile 传。 */
    @Volatile
    private var systemSilenced = false

    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            onError("设备不支持 16kHz 单声道录音")
            return false
        }

        val rec =
            runCatching {
                AudioRecord(
                    audioSource,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    minBuf * 2,
                )
            }.getOrElse {
                Timber.e(it, "创建 AudioRecord 失败")
                onError("无法打开麦克风")
                return false
            }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            onError("麦克风初始化失败")
            return false
        }

        record = rec
        running = true
        runCatching { rec.startRecording() }
            .onFailure {
                running = false
                runCatching { rec.release() }
                record = null
                onError("启动录音失败")
                return false
            }

        silence.reset()
        systemSilenced = false
        registerSilenceCallback(rec)

        worker =
            thread(name = "jackvoice-record") {
                val buf = ByteArray(CHUNK_BYTES)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    when {
                        n > 0 -> {
                            trackSilence(buf, n)
                            // 先算音量再送识别：两块互不影响，但顺序上 UI 更跟手。
                            onLevel(levelMeter.fromPcm(buf, n))
                            onChunk(buf.copyOf(n))
                        }

                        n < 0 -> {
                            if (running) {
                                Timber.e("AudioRecord.read 返回 $n")
                                onError("录音中断")
                            }
                            break
                        }
                    }
                }
            }
        return true
    }

    // ── 「拿不到麦克风」的判定 ──────────────────────────────────────────

    /**
     * 系统告诉我们这一路被静音了。
     *
     * 这是**秒级**的信号，比数全零快得多，所以能第一时间把面板上的提示换掉。
     * 必须在 `startRecording()` **之前**注册，否则第一次变更收不到。
     */
    private fun registerSilenceCallback(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am =
            runCatching { appContext?.getSystemService(AudioManager::class.java) }.getOrNull()
                ?: return
        val cb =
            object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    val mine = runCatching { rec.activeRecordingConfiguration }.getOrNull() ?: return
                    val silenced = runCatching { mine.isClientSilenced }.getOrDefault(false)
                    if (silenced != systemSilenced) {
                        systemSilenced = silenced
                        Timber.i("录音静音状态变为 $silenced")
                    }
                }
            }
        val ok =
            runCatching {
                am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
                true
            }.getOrDefault(false)
        if (ok) recordingCallback = cb
    }

    private fun unregisterSilenceCallback() {
        val cb = recordingCallback ?: return
        recordingCallback = null
        val am = runCatching { appContext?.getSystemService(AudioManager::class.java) }.getOrNull() ?: return
        runCatching { am.unregisterAudioRecordingCallback(cb) }
    }

    /**
     * 数「连续全零」的时长。
     *
     * 门限 1.5 秒：短一点的零是正常的（AudioRecord 刚起来的第一块、
     * 对方刚好松手、系统切换路由的那一下），超过这个长度就是真的拿不到。
     *
     * 只报警不掐录音——调用方可能想留着这段音频做证据，也可能想自动重试。
     * 真正的收场交给它决定。
     */
    private fun trackSilence(
        buf: ByteArray,
        length: Int,
    ) {
        // SilenceGate 自己维护「报过没有」：跨过门限的那一刻返回一次 true，
        // 恢复出声时它会把标记清掉，于是下一次被抢还能再报一遍。
        val wasReported = silence.hasReported
        val crossed = silence.feed(buf, length)
        val recovering = wasReported && !silence.hasReported
        if (crossed) {
            Timber.w("连续 ${silence.silentSoFarMs}ms 全零，判定麦克风不可用（系统静音=$systemSilenced）")
            // 系统主动说「静音了」就信它；否则按现场查到的原因报。
            val reason =
                if (systemSilenced) {
                    MicStatus.Reason.SelfSilenced
                } else {
                    appContext?.let { MicStatus.probeWhileRecording(it, record).reason }
                        ?: MicStatus.Reason.AllSilent
                }
            onMicBlocked(reason)
        } else if (recovering) {
            // 声音回来了：把界面上的提示撤掉。
            // 回调走的是 mic 线程，调用方自己负责切主线程。
            Timber.i("麦克风恢复，波形重新有数据")
            onMicRecovered()
        }
    }

    /**
     * 停止录音，并**等录音线程真的退出**再返回。
     *
     * 顺序很要紧：先让 `running=false`，等当前这次 `read` 把手上那 100ms 读完
     * （通常几十毫秒），线程自然退出；实在卡住才用 `AudioRecord.stop()` 把它
     * 踢醒。以前是先 `join(600)`、再 stop：超时之后主线程已经去收 WAV、
     * 发识别末包了，录音线程可能还在回调最后一块，于是最后那截声音
     * 既没进 WAV、也没进识别队列——本地听起来就是「最后几个字断了」。
     *
     * 现在只有确认线程结束才释放 `AudioRecord`，回调不会再和收尾抢跑。
     */
    fun stop() {
        if (!running) return
        running = false
        val rec = record
        // 正常情况下阻塞中的 read 会在这一块（100ms）读满后返回；
        // 给足两个块的时间，避免把最后一块音频硬切掉。
        runCatching { worker?.join(250) }
        if (worker?.isAlive == true) {
            // 设备卡住时才强制打断 read，让它从阻塞里出来。
            runCatching { rec?.stop() }
            runCatching { worker?.join(1_000) }
        }
        worker = null
        unregisterSilenceCallback()
        rec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /**
         * 默认用 `MIC`。`VOICE_RECOGNITION`（source 6）在小米 HyperOS 上即使权限齐全
         * 也会被 audio policy 拒掉（`getInputForAttr: missing perms for source 6`），
         * 实测 `MIC` 才放行；识别侧本来就有自己的降噪，音源差别可以忽略。
         */
        private val audioSource = MediaRecorder.AudioSource.MIC
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 一帧 100ms：够小，流式识别延迟低。 */
        private const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2
    }
}
