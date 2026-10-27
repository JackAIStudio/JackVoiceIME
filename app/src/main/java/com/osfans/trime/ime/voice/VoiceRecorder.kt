/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    /** 每块 PCM 的音量（0..1），录音线程回调；波形靠它，不能再用假动画。 */
    private val onLevel: (Float) -> Unit = {},
) {
    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    /** 每次录音自己跟一条底噪线：安静时必须是 0，不能让底噪把柱子顶起来。 */
    private val levelMeter = VoiceLevel()

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

        worker =
            thread(name = "jackvoice-record") {
                val buf = ByteArray(CHUNK_BYTES)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    when {
                        n > 0 -> {
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
