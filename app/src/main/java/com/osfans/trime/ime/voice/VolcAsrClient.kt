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
 * 端点、请求头与桌面版一致；热词优先走云端热词表，未配置表 ID 时
 * 按请求级直传（归一化 + 100 字符预算）。
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
        /** 云端热词表 ID；非空时优先整表生效，忽略 [hotwords]。 */
        val boostingTableId: String = "",
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

        /**
         * 连接已建立、可以开始灌音频了。
         *
         * 现场录音不需要它（音频本来就是一秒十块地来），但**回放已存录音**必须等：
         * 一次性把整段 PCM 灌进 [feed] 会撑爆预缓冲（[MAX_PREBUFFER_CHUNKS]），
         * 开头那段就被丢掉了。
         */
        fun onOpen() {}
    }

    companion object {
        private const val TAG = "JackVoice"
        private const val MAX_PREBUFFER_CHUNKS = 300

        /** 发送队列里最多攒多少帧。回放整段录音时用来控速：不丢音频，只推迟。 */
        private const val MAX_QUEUED_FRAMES = 64L
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
                    boostingTableId = config.boostingTableId,
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
                if (stopPending) {
                    // 用户已经点了停止（或者整段录音的回放已经灌完）：
                    // 先把缓冲灌完再发末包，顺序不能反。
                    stopPending = false
                    flushPrebuffer()
                    sendLastFrame()
                } else {
                    flushPrebuffer()
                    listener.onOpen()
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

    /**
     * 送一帧 PCM（16kHz / 16bit / 单声道）。建连期间先缓冲，避免开头丢失。
     *
     * @return 真的进去了才为 true。发送队列满时返回 false——回放整段录音的
     *         调用方据此稍后再送，**不能当成已经送出去**，否则末包会抢在前面。
     */
    fun feed(pcm: ByteArray): Boolean {
        if (closed) return false
        if (!audioReady) {
            prebuffer.addLast(pcm)
            if (prebuffer.size > MAX_PREBUFFER_CHUNKS) prebuffer.removeFirst()
            return true
        }
        return sendAudio(pcm)
    }

    private fun sendAudio(pcm: ByteArray): Boolean {
        val socket = ws ?: return false
        if (closed) return false
        // 对方还没接住上一批时先攥着：回放整段录音会把几百块一次灌进来，
        // 无脑 send 只会把 OkHttp 的发送队列撑爆。
        if (socket.queueSize() > MAX_QUEUED_FRAMES) return false
        val payload = VolcProtocol.gzip(pcm)
        val frame = VolcProtocol.buildFrame(
            VolcProtocol.MSG_AUDIO_ONLY,
            seq,
            0x0,
            VolcProtocol.COMPRESSION_GZIP,
            payload,
        )
        return try {
            socket.send(frame.toByteString())
            seq += 1
            true
        } catch (e: Exception) {
            Log.w(TAG, "发送音频帧失败：${e.message}")
            false
        }
    }

    /** 回放整段录音时用：发送队列里还压着多少帧，调用方据此控速。 */
    fun queuedFrames(): Long = ws?.queueSize() ?: 0L

    private fun flushPrebuffer() {
        while (prebuffer.isNotEmpty()) {
            val chunk = prebuffer.first()
            if (!sendAudio(chunk)) break
            prebuffer.removeFirst()
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
        if (closed) return
        flushPrebuffer()
        val payload = VolcProtocol.gzip(ByteArray(0))
        val frame = VolcProtocol.buildFrame(
            VolcProtocol.MSG_AUDIO_ONLY,
            -seq,
            0x0,
            VolcProtocol.COMPRESSION_GZIP,
            payload,
        )
        runCatching { socket.send(frame.toByteString()) }
            .onFailure { Log.w(TAG, "发末包失败：${it.message}") }
        Log.i(TAG, "已发末包 seq=-$seq，等二遍终稿")
    }

    fun cancel() {
        closed = true
        runCatching { ws?.cancel() }
        runCatching { client.dispatcher.executorService.shutdown() }
        ws = null
        audioReady = false
        prebuffer.clear()
    }
}
