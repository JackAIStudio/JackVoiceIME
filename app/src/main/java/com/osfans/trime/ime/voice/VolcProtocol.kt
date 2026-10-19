package com.osfans.trime.ime.voice

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 火山引擎 bigmodel_async 二进制协议。
 *
 * 逐字移植自本仓库 `plugin/src/libs/protocol.ts`（Xime 插件版，已实测跑通），
 * 保证 App 和插件走同一套帧格式。
 *
 * 帧结构：4 字节头 + 4 字节有符号 seq + 4 字节大端 payload 长度 + payload
 *   头[0] = (协议版本 0x1 << 4) | (头长度单位 0x1)
 *   头[1] = (消息类型 << 4) | flags
 *   头[2] = (序列化 << 4) | 压缩
 *   头[3] = 0
 */
object VolcProtocol {

    const val MSG_FULL_CLIENT_REQ = 0x1
    const val MSG_AUDIO_ONLY = 0x2
    const val MSG_SERVER_RESP = 0x9
    const val MSG_SERVER_ERROR = 0xf

    const val FLAG_POS_SEQUENCE = 0x1
    const val FLAG_NEG_WITH_SEQUENCE = 0x3

    const val SERIALIZATION_JSON = 0x1
    const val COMPRESSION_GZIP = 0x1

    const val WS_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"
    const val SAMPLE_RATE = 16000

    data class ParsedFrame(
        val msgType: Int,
        val seq: Int? = null,
        val isLast: Boolean = false,
        val event: Int? = null,
        val text: String? = null,
        val code: Int? = null,
        val message: String? = null,
        /** 原始 flags 位：0x1 带 seq、0x2 末包、0x4 带 event。诊断用，别拿它当业务判据。 */
        val flags: Int = 0,
        /** `result` 里的字段名。只看键、不看值：用来分辨这一包是流式结果还是二遍结果。 */
        val resultKeys: List<String> = emptyList(),
    )

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }

    /** 组装一帧。seqValue < 0 表示末包（flags=0x3）。 */
    fun buildFrame(
        msgType: Int,
        seqValue: Int,
        ser: Int,
        comp: Int,
        payload: ByteArray,
    ): ByteArray {
        val flags = if (seqValue < 0) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE
        val out = ByteArray(12 + payload.size)
        out[0] = 0x11
        out[1] = ((msgType * 16 + flags) and 0xff).toByte()
        out[2] = ((ser * 16 + comp) and 0xff).toByte()
        out[3] = 0
        writeInt32be(out, 4, seqValue)
        writeUint32be(out, 8, payload.size)
        System.arraycopy(payload, 0, out, 12, payload.size)
        return out
    }

    private fun writeInt32be(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = ((value ushr 24) and 0xff).toByte()
        arr[offset + 1] = ((value ushr 16) and 0xff).toByte()
        arr[offset + 2] = ((value ushr 8) and 0xff).toByte()
        arr[offset + 3] = (value and 0xff).toByte()
    }

    private fun writeUint32be(arr: ByteArray, offset: Int, value: Int) {
        writeInt32be(arr, offset, value)
    }

    private fun readInt32(arr: ByteArray, offset: Int): Int {
        return ((arr[offset].toInt() and 0xff) shl 24) or
            ((arr[offset + 1].toInt() and 0xff) shl 16) or
            ((arr[offset + 2].toInt() and 0xff) shl 8) or
            (arr[offset + 3].toInt() and 0xff)
    }

    private fun readUint32(arr: ByteArray, offset: Int): Int = readInt32(arr, offset)

    fun parseServerFrame(frame: ByteArray): ParsedFrame? {
        if (frame.size < 8) return null
        val b0 = frame[0].toInt() and 0xff
        val b1 = frame[1].toInt() and 0xff
        val b2 = frame[2].toInt() and 0xff
        val headerSize = (b0 % 16) * 4
        val msgType = (b1 / 16) % 16
        val flags = b1 % 16
        val ser = (b2 / 16) % 16
        val comp = b2 % 16
        var offset = headerSize

        var seq: Int? = null
        var isLast = false
        var event: Int? = null

        if (flags % 2 == 1) {           // flags & 0x01：带 sequence
            seq = readInt32(frame, offset)
            offset += 4
        }
        if ((flags / 2) % 2 == 1) {     // flags & 0x02：末包
            isLast = true
        }
        if ((flags / 4) % 2 == 1) {     // flags & 0x04：带 event
            event = readInt32(frame, offset)
            offset += 4
        }

        if (msgType == MSG_SERVER_RESP) {
            if (offset + 4 > frame.size) return null
            val size = readUint32(frame, offset)
            offset += 4
            val end = (offset + size).coerceAtMost(frame.size)
            var payload = frame.copyOfRange(offset, end)
            if (comp == COMPRESSION_GZIP) {
                payload = try {
                    gunzip(payload)
                } catch (e: Exception) {
                    return ParsedFrame(msgType, seq, isLast, event, flags = flags)
                }
            }
            if (ser == SERIALIZATION_JSON) {
                val result = try {
                    org.json.JSONObject(String(payload, Charsets.UTF_8)).optJSONObject("result")
                } catch (e: Exception) {
                    null
                }
                return ParsedFrame(
                    msgType,
                    seq,
                    isLast,
                    event,
                    text = result?.optString("text"),
                    flags = flags,
                    resultKeys = result?.keys()?.asSequence()?.toList().orEmpty(),
                )
            }
            return ParsedFrame(msgType, seq, isLast, event, flags = flags)
        } else if (msgType == MSG_SERVER_ERROR) {
            if (offset + 8 > frame.size) return ParsedFrame(msgType, seq, isLast, event, flags = flags)
            val code = readInt32(frame, offset)
            val size = readUint32(frame, offset + 4)
            val start = offset + 8
            val end = (start + size).coerceAtMost(frame.size)
            val message = String(frame.copyOfRange(start, end), Charsets.UTF_8)
            return ParsedFrame(msgType, seq, isLast, event, code = code, message = message, flags = flags)
        }
        return ParsedFrame(msgType, seq, isLast, event, flags = flags)
    }

    /** 构造建连后的第一包（含识别参数与热词）。 */
    fun buildFullClientRequestJson(
        hotwords: List<String>,
        boostingTableId: String = "",
        uid: String,
        enableNonstream: Boolean = true,
        sampleRate: Int = SAMPLE_RATE,
    ): String {
        val request = org.json.JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            // 标点始终要：三档都靠服务端给出标点位置，「无标点」也是先拿到标点再换成空格。
            put("enable_punc", true)
            put("enable_ddc", false)
            // 对齐 AgenBoard：词级时间戳 + 完整结果
            put("show_utterances", true)
            put("ssd_version", "200")
            put("result_type", "full")
            // 二遍终稿：停止后服务端再给一次非流式结果
            put("enable_nonstream", enableNonstream)
        }

        val tableId = boostingTableId.trim()
        if (tableId.isNotEmpty()) {
            // 与桌面版一致：配置了云端热词表就整表生效，不再直传热词。
            request.put("corpus", org.json.JSONObject().put("boosting_table_id", tableId))
        } else {
            // 请求级直传：归一化 + 100 字符预算，避免把整份词库原样塞进去。
            val words = HotwordSanitizer.inline(hotwords)
            if (words.isNotEmpty()) {
                val arr = org.json.JSONArray()
                words.forEach { arr.put(org.json.JSONObject().put("word", it)) }
                // 对齐 AgenBoard：request.corpus.context 是 **JSON 字符串**（双层嵌套）
                request.put(
                    "corpus",
                    org.json.JSONObject().put("context", org.json.JSONObject().put("hotwords", arr).toString())
                )
            }
        }

        val root = org.json.JSONObject().apply {
            put("user", org.json.JSONObject().apply {
                put("uid", uid)
                put("platform", "android")
            })
            put("audio", org.json.JSONObject().apply {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", sampleRate)
                put("bits", 16)
                put("channel", 1)
            })
            put("request", request)
        }
        return root.toString()
    }
}
