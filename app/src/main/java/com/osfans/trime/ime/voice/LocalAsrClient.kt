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
import java.io.FileInputStream
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

    /** `stop()` 之后不再接收新的 PCM；worker 还要把 stop 之前的都吃完。 */
    @Volatile
    private var stopped = false

    @Volatile
    private var cancelled = false

    private var worker: Thread? = null

    fun start() {
        worker =
            thread(name = "jackvoice-local-asr") {
                run()
            }
    }

    /** @return false 表示已停止 / 已取消，这一块不再进入识别。 */
    fun feed(pcm: ByteArray): Boolean {
        if (cancelled || stopped) return false
        return queue.offer(pcm)
    }

    /**
     * 排进结束标记；worker 会先吃完前面的音频，再补尾、inputFinished。
     *
     * 标记只排一次。录音线程的收尾和 UI 的 stop 可能前后脚都调到这里，
     * 排两个标记不会出错，但第一个就会让 worker 返回，第二个永远没人取；
     * 更重要的是别让 `stopped` 之后还有音频挤在标记后面，那才是真正的断尾。
     */
    fun stop() {
        if (cancelled || stopped) return
        stopped = true
        if (!queue.offer(stopMarker)) {
            listener.onError("本地识别队列忙，请稍后再试")
        }
    }

    fun cancel() {
        cancelled = true
        stopped = true
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
                    LocalAsrEngine.finishStream(recognizer, stream, hasAudio = totalSamples > 0)
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

    /**
     * 结束流式识别前补的尾部静音。
     *
     * 模型是 480ms chunk 的流式 zipformer，encoder 需要右上下文才能把最后一个
     * chunk 的音节解出来。以前直接 `inputFinished()`，末尾不足一个 chunk 的
     * 音频没有完整右上下文，最后几个字就丢了——云端有两遍终稿，所以看起来
     * 只有本地会「断尾」。sherpa-onnx 官方 `online-decode-files.py` 同样在
     * `input_finished()` 前补 0.66 秒静音，这里取同一量级。
     *
     * 只补静音，不伪造任何语音；`inputFinished()` 仍要在这之后调用。
     *
     * 实测（同一份模型 + 真机录音）：不补尾会稳定丢掉最后一个字，
     * 0.33 秒仍然不够，0.66 秒能补齐；1.0 秒没有额外收益，所以取 0.66。
     */
    internal const val TAIL_PADDING_SECONDS = 0.66f
    internal const val SAMPLE_RATE = 16_000

    internal val tailPadding: FloatArray
        get() = FloatArray((TAIL_PADDING_SECONDS * SAMPLE_RATE).toInt())

    /**
     * 补尾、标结束、把最后几个 chunk 解完。
     *
     * 顺序不能反：先 `acceptWaveform(tailPadding)`，再 `inputFinished()`。
     * 补尾放在 `inputFinished()` 之后不会进入特征流，等于没补。
     */
    internal fun finishStream(
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
        hasAudio: Boolean,
    ) {
        if (hasAudio) {
            stream.acceptWaveform(tailPadding, SAMPLE_RATE)
        }
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

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
     * 把一段已经落盘的 PCM 转成文字，给历史里的「重新识别」用。
     *
     * [pcm] 为空时从 [pcmFile] 按块流式读，避免 4 分钟录音整段进内存；
     * 两者都只用于重识别，不和实时听写共用 stream。[Synchronized]
     * 保证不会和正在跑的实时听写抢同一个 native 对象。
     *
     * @param onProgress 已处理的 PCM 字节数 / 总字节数（0..1），用于 UI 进度
     * @param isCancelled 返回 true 时提前收尾，不再解码
     */
    @Synchronized
    fun transcribe(
        modelDir: File,
        pcm: ByteArray = ByteArray(0),
        pcmFile: File? = null,
        onProgress: ((Float) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): String {
        val recognizer = get(modelDir)
        val stream = recognizer.createStream()
        var readCancelled = false
        try {
            var processed = 0L
            val totalBytes =
                when {
                    pcm.isNotEmpty() -> pcm.size.toLong()
                    pcmFile != null && pcmFile.isFile -> (pcmFile.length() - 44).coerceAtLeast(0)
                    else -> 0L
                }
            fun feed(block: ByteArray): Boolean {
                if (isCancelled?.invoke() == true) return false
                stream.acceptWaveform(pcmToFloat(block), 16_000)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                processed += block.size
                if (totalBytes > 0) onProgress?.invoke((processed.toFloat() / totalBytes).coerceIn(0f, 1f))
                return true
            }
            if (pcm.isNotEmpty()) {
                var offset = 0
                while (offset < pcm.size) {
                    val n = minOf(3_200, pcm.size - offset)
                    if (!feed(pcm.copyOfRange(offset, offset + n))) return ""
                    offset += n
                }
            } else if (pcmFile != null) {
                // 直接读 WAV 文件，跳过 44 字节头；不再经 VoiceAudio 的 Context 路径，
                // 重识别拿到的就是 entry.fileName 对应的绝对文件。
                val recognized =
                    readFileInBlocks(pcmFile) { block ->
                        if (!feed(block)) {
                            readCancelled = true
                            false
                        } else {
                            true
                        }
                    }
                if (!recognized || readCancelled) return ""
            } else {
                return ""
            }
            if (isCancelled?.invoke() == true) return ""
            // 重识别也必须补尾：同一个模型、同一个「最后几个字解不出来」的问题。
            finishStream(recognizer, stream, hasAudio = totalBytes > 0)
            return recognizer.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun readFileInBlocks(
        file: File,
        onBlock: (ByteArray) -> Boolean,
    ): Boolean =
        runCatching {
            FileInputStream(file).use { input ->
                var skipped = 0L
                while (skipped < 44) {
                    val n = input.skip(44 - skipped)
                    if (n <= 0) return false
                    skipped += n
                }
                val buffer = ByteArray(3_200)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) return true
                    if (n == 0) continue
                    val block = if (n == buffer.size) buffer.copyOf() else buffer.copyOf(n)
                    if (!onBlock(block)) return true
                }
                @Suppress("UNREACHABLE_CODE")
                true
            }
        }.getOrDefault(false)

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

    /** 切到本地时先预热，别让用户开口后才开始加载 2–3 秒。 */
    fun preload(modelDir: File) {
        Thread({ runCatching { get(modelDir) } }, "jackvoice-local-preload").start()
    }

    @Synchronized
    fun close() {
        runCatching { recognizer?.release() }
        recognizer = null
        loadedDir = null
    }
}
