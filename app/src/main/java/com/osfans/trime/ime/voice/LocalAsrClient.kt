/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 本地流式识别：sherpa-onnx + X-ASR 480ms int8。
 *
 * 音频从录音线程进来，先进一个有界队列；识别在单独线程里一块一块取。
 * 这样麦克风回调不会被推理拖住，模型加载期间的声音也不会丢。
 *
 * 模型是共享的（[LocalAsrEngine]），每次听写只新建一个 stream。
 * 模型加载 1–3 秒，第一次点本地会等这一下；之后同一进程内复用。
 */
class LocalAsrClient(
    private val modelDir: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onError(message: String)
    }

    // 不设上限：录音线程不能阻塞，宁可多占几 MB，也不能把用户刚说的话丢掉。
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val stopMarker = ByteArray(0)

    @Volatile
    private var cancelled = false

    private var worker: Thread? = null

    fun start() {
        worker =
            thread(name = "jackvoice-local-asr") {
                run()
            }
    }

    /** @return false 表示队列满了，这一块没进去。 */
    fun feed(pcm: ByteArray): Boolean {
        if (cancelled) return false
        return queue.offer(pcm)
    }

    /** 排进结束标记；worker 会先吃完前面的音频，再 inputFinished。 */
    fun stop() {
        if (cancelled) return
        if (!queue.offer(stopMarker)) {
            listener.onError("本地识别队列忙，请稍后再试")
        }
    }

    fun cancel() {
        cancelled = true
        queue.clear()
        queue.offer(stopMarker)
    }

    private fun run() {
        var stream: OnlineStream? = null
        try {
            val loadStart = System.currentTimeMillis()
            val recognizer = LocalAsrEngine.get(modelDir)
            val loadMs = System.currentTimeMillis() - loadStart
            stream = recognizer.createStream()
            var lastText = ""
            var totalSamples = 0L
            var decodeNanos = 0L
            val startedAt = System.currentTimeMillis()
            while (!cancelled) {
                val chunk = queue.take()
                if (chunk === stopMarker) {
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    val audioSec = totalSamples.toDouble() / SAMPLE_RATE
                    val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000.0
                    val decodeSec = decodeNanos / 1_000_000_000.0
                    Timber.i(
                        "本地识别完成：音频 %.1fs，墙钟 %.1fs，计算 %.1fs，计算 RTF %.2f，加载 %dms",
                        audioSec,
                        elapsedSec,
                        decodeSec,
                        if (audioSec > 0) decodeSec / audioSec else 0.0,
                        loadMs,
                    )
                    if (!cancelled) listener.onFinal(text)
                    return
                }
                totalSamples += chunk.size / 2
                val decodeStart = System.nanoTime()
                stream.acceptWaveform(LocalAsrEngine.pcmToFloat(chunk), SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                decodeNanos += System.nanoTime() - decodeStart
                val text = recognizer.getResult(stream).text.trim()
                if (text != lastText) {
                    lastText = text
                    Timber.d("本地 partial len=%d", text.length)
                    if (!cancelled) listener.onPartial(text)
                }
            }
        } catch (t: Throwable) {
            if (!cancelled) {
                Timber.w(t, "本地识别失败")
                listener.onError(t.message ?: "本地识别失败")
            }
        } finally {
            runCatching { stream?.release() }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

    }
}

/**
 * 进程内共享的 sherpa recognizer。
 *
 * 只允许一个 stream 同时跑（听写本来就是单路）；[get] 加锁，
 * 空闲时由 [close] 主动释放内存。
 */
object LocalAsrEngine {
    private var recognizer: OnlineRecognizer? = null
    private var loadedDir: String? = null

    @Synchronized
    fun get(modelDir: File): OnlineRecognizer {
        val key = modelDir.absolutePath
        recognizer?.let { if (loadedDir == key) return it }
        close()
        val config =
            OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0f),
                modelConfig =
                    OnlineModelConfig(
                        transducer =
                            OnlineTransducerModelConfig(
                                encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                                decoder = File(modelDir, "decoder.onnx").absolutePath,
                                joiner = File(modelDir, "joiner.int8.onnx").absolutePath,
                            ),
                        tokens = File(modelDir, "tokens.txt").absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer2",
                    ),
                enableEndpoint = false,
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
            )
        val created = OnlineRecognizer(assetManager = null, config = config)
        recognizer = created
        loadedDir = key
        Timber.i("本地识别模型已加载：$key")
        return created
    }

    /**
     * 把一段已经落盘的 PCM 一次性转成文字，给历史里的「重新识别」用。
     *
     * 和实时那条流共用同一个 recognizer，但不共用 stream；[Synchronized]
     * 保证不会和正在跑的实时听写抢同一个 native 对象。
     */
    @Synchronized
    fun transcribe(modelDir: File, pcm: ByteArray): String {
        val recognizer = get(modelDir)
        val stream = recognizer.createStream()
        try {
            var offset = 0
            while (offset < pcm.size) {
                val n = minOf(3200, pcm.size - offset)
                stream.acceptWaveform(pcmToFloat(pcm, offset, n), 16_000)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                offset += n
            }
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    fun pcmToFloat(
        pcm: ByteArray,
        offset: Int = 0,
        length: Int = pcm.size,
    ): FloatArray {
        val shorts = ByteBuffer.wrap(pcm, offset, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(shorts.remaining())
        for (i in out.indices) out[i] = shorts.get(i) / 32768f
        return out
    }

    @Synchronized
    fun close() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedDir = null
    }
}
