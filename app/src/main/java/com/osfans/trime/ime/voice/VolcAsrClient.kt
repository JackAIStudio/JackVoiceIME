package com.osfans.trime.ime.voice

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 豆包（火山引擎）双向流式识别客户端。
 *
 * 端点、请求头、热词注入方式与 `plugin/`（Xime 插件版）完全一致——
 * 两边共用同一套 API Key、Resource ID、热词库，改一处两边都变。
 *
 * 线程约定：feed() 由录音线程调用；回调在 OkHttp 线程触发，调用方自行切线程。
 */
class VolcAsrClient(
    private val config: Config,
    private val listener: Listener,
) {

    data class Config(
        val apiKey: String,
        val resourceId: String = VolcProtocol.DEFAULT_RESOURCE_ID,
        val hotwords: List<String> = emptyList(),
        val uid: String = "jackvoice",
        /** 二遍终稿：停止后服务端再给一次更准的非流式结果 */
        val enableNonstream: Boolean = true,
    )

    interface Listener {
        /** 实时（一遍）结果 */
        fun onPartial(text: String)
        /** 终稿（二遍）结果 */
        fun onFinal(text: String)
        fun onError(message: String)
        fun onClosed()
    }

    companion object {
        private const val TAG = "JackVoice"
        private const val MAX_PREBUFFER_CHUNKS = 300
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // 长连接不设读超时
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var seq = 1
    private var audioReady = false
    private var stopPending = false
    private val prebuffer = ArrayDeque<ByteArray>()

    /** 发末包的时刻，用来算「停止 → 服务端回帧」的耗时。诊断用。 */
    @Volatile
    private var stopAt = 0L

    /** 最后一版流式结果，只用于和终稿比对（不落正文）。诊断用。 */
    private var lastPartial = ""

    @Volatile
    private var closed = false

    fun start() {
        val request = Request.Builder()
            .url(VolcProtocol.WS_ENDPOINT)
            .addHeader("X-Api-Key", config.apiKey)
            .addHeader("X-Api-Resource-Id", config.resourceId)
            .addHeader("X-Api-Request-Id", UUID.randomUUID().toString().lowercase())
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString().lowercase())
            .addHeader("X-Api-Sequence", "-1")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "火山连接已建立")
                val json = VolcProtocol.buildFullClientRequestJson(
                    hotwords = config.hotwords,
                    uid = config.uid,
                    enableNonstream = config.enableNonstream,
                )
                val payload = VolcProtocol.gzip(json.toByteArray(Charsets.UTF_8))
                val frame = VolcProtocol.buildFrame(
                    VolcProtocol.MSG_FULL_CLIENT_REQ,
                    seq,
                    VolcProtocol.SERIALIZATION_JSON,
                    VolcProtocol.COMPRESSION_GZIP,
                    payload,
                )
                webSocket.send(frame.toByteString())
                seq += 1
                audioReady = true
                flushPrebuffer()
                if (stopPending) {
                    stopPending = false
                    sendLastFrame()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val parsed = VolcProtocol.parseServerFrame(bytes.toByteArray()) ?: return
                // 诊断：每一帧都记下来。停止后到最终帧的耗时、以及「除了流式末帧之外
                // 还会不会再来一包二遍结果」，都只能从这条日志里看出来。
                logFrame(parsed)
                when (parsed.msgType) {
                    VolcProtocol.MSG_SERVER_RESP -> {
                        val text = parsed.text.orEmpty()
                        if (text.isEmpty()) return
                        if (parsed.isLast) {
                            logFinalVsPreview(text, parsed.resultKeys)
                            listener.onFinal(text)
                        } else {
                            lastPartial = text
                            listener.onPartial(text)
                        }
                    }
                    VolcProtocol.MSG_SERVER_ERROR -> {
                        Log.e(TAG, "火山返回错误 code=${parsed.code} msg=${parsed.message}")
                        listener.onError(parsed.message ?: "识别服务返回错误")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) {
                    // 自己 cancel 关的连接，OkHttp 也会走 onFailure，不是真错误。
                    Log.i(TAG, "连接已由本地关闭，忽略这条 onFailure")
                    return
                }
                Log.e(TAG, "火山连接失败: ${t.message}")
                listener.onError(t.message ?: "连接失败")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "火山连接关闭 code=$code 停止后=${elapsedSinceStop()}ms")
                if (!closed) listener.onClosed()
            }
        })
    }

    /** 诊断：一帧一行。isLast 的帧会被当作终稿直接上屏，所以用 I 级别标出来。 */
    private fun logFrame(parsed: VolcProtocol.ParsedFrame) {
        val len = parsed.text?.length ?: -1
        val line =
            "← 帧 type=0x${parsed.msgType.toString(16)} flags=0x${parsed.flags.toString(16)} " +
                "seq=${parsed.seq} last=${parsed.isLast} len=$len 停止后=${elapsedSinceStop()}ms " +
                "keys=${parsed.resultKeys}"
        if (parsed.isLast || parsed.msgType == VolcProtocol.MSG_SERVER_ERROR) {
            Log.i(TAG, line)
        } else {
            Log.d(TAG, line)
        }
    }

    /**
     * 诊断：终稿和最后一遍流式结果是不是**同一串字**。
     *
     * 这是判断「等到的到底是二遍终稿，还是流式尾帧」的唯一硬证据。只记是否相同和
     * 首个不同的位置，不落任何正文——位置 + 长度足够定性，也不把用户说的话写进日志。
     */
    private fun logFinalVsPreview(finalText: String, keys: List<String>) {
        val prev = lastPartial
        val n = minOf(prev.length, finalText.length)
        var i = 0
        while (i < n && prev[i] == finalText[i]) i++
        val same = i == n && prev.length == finalText.length
        val verdict = if (same) "与预览逐字相同（二遍没改任何字）" else "与预览不同，首个不同在第 ${i + 1} 个字"
        Log.i(
            TAG,
            "终稿 vs 最后一遍预览：same=$same 终稿=${finalText.length}字 预览=${prev.length}字 $verdict keys=$keys",
        )
    }

    private fun elapsedSinceStop(): Long =
        if (stopAt > 0) System.currentTimeMillis() - stopAt else -1L

    /** 送一帧 PCM（16kHz / 16bit / 单声道）。建连期间先缓冲，避免开头丢失。 */
    fun feed(pcm: ByteArray) {
        if (audioReady) {
            sendAudio(pcm)
        } else {
            prebuffer.addLast(pcm)
            if (prebuffer.size > MAX_PREBUFFER_CHUNKS) prebuffer.removeFirst()
        }
    }

    private fun sendAudio(pcm: ByteArray) {
        val socket = ws ?: return
        val payload = VolcProtocol.gzip(pcm)
        val frame = VolcProtocol.buildFrame(
            VolcProtocol.MSG_AUDIO_ONLY,
            seq,
            0x0,
            VolcProtocol.COMPRESSION_GZIP,
            payload,
        )
        socket.send(frame.toByteString())
        seq += 1
    }

    private fun flushPrebuffer() {
        while (prebuffer.isNotEmpty()) {
            sendAudio(prebuffer.removeFirst())
        }
    }

    /** 发结束包（负包），服务端随后给出二遍终稿。 */
    fun stop() {
        stopAt = System.currentTimeMillis()
        if (!audioReady) {
            Log.i(TAG, "停止：连接还没就绪，等 onOpen 再发末包")
            stopPending = true
            return
        }
        sendLastFrame()
    }

    private fun sendLastFrame() {
        val socket = ws ?: return
        flushPrebuffer()
        val payload = VolcProtocol.gzip(ByteArray(0))
        val frame = VolcProtocol.buildFrame(
            VolcProtocol.MSG_AUDIO_ONLY,
            -seq,
            0x0,
            VolcProtocol.COMPRESSION_GZIP,
            payload,
        )
        socket.send(frame.toByteString())
        Log.i(TAG, "已发末包 seq=-$seq，等二遍终稿")
    }

    fun cancel() {
        closed = true
        runCatching { ws?.close(1000, "cancel") }
        runCatching { client.dispatcher.executorService.shutdown() }
        ws = null
        audioReady = false
        prebuffer.clear()
    }
}
