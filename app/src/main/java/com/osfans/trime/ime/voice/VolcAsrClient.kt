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
                when (parsed.msgType) {
                    VolcProtocol.MSG_SERVER_RESP -> {
                        val text = parsed.text.orEmpty()
                        if (text.isEmpty()) return
                        if (parsed.isLast) listener.onFinal(text) else listener.onPartial(text)
                    }
                    VolcProtocol.MSG_SERVER_ERROR -> {
                        Log.e(TAG, "火山返回错误 code=${parsed.code} msg=${parsed.message}")
                        listener.onError(parsed.message ?: "识别服务返回错误")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "火山连接失败: ${t.message}")
                if (!closed) listener.onError(t.message ?: "连接失败")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "火山连接关闭 code=$code")
                if (!closed) listener.onClosed()
            }
        })
    }

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
        if (!audioReady) {
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
