/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 识别失败的类型判定与「人话」。
 *
 * 原始错误是 `connection closed`、`SocketTimeoutException`、`401 Unauthorized` 这类
 * 只有日志才该看的字符串。直接甩到面板上，用户既看不懂，也不知道**这段声音还在不在**。
 *
 * 这里只做纯字符串判断，不碰 Android，方便单测把边界钉住：
 *
 *  - **网络类**：值得稍后重试，而且录音一定还在本机；
 *  - **鉴权类**：Key 填错了，再试一百次也没用，得让人去设置里改；
 *  - 其余：原样透出，总比吞掉强。
 */
object VoiceFailure {
    private val AUTH_MARKERS =
        listOf(
            "401",
            "unauthorized",
            "invalid api key",
            "invalid_api_key",
            "鉴权",
        )

    private val NETWORK_MARKERS =
        listOf(
            "timeout",
            "timed out",
            "connect",
            "network",
            "unable to resolve host",
            "socket",
            "closed",
            "broken pipe",
            "no route",
            "dns",
            "unreachable",
            "reset by peer",
            "econnreset",
            "unexpected end of stream",
            "handshake",
            "ssl",
            // 中文兜底文案（连接异常且 message 为 null 时由客户端自己拼的）
            "网络",
            "连接失败",
            "连接中断",
            "连接被",
            "未连接",
            "超时",
        )

    fun isAuth(message: String): Boolean {
        val m = message.lowercase()
        return AUTH_MARKERS.any { m.contains(it) }
    }

    /**
     * 网络没连上 / 连接断了。
     *
     * 鉴权错误先排除：`401` 的响应体里也可能带着 connect 之类的词，
     * 但那是配置问题，不该被当成「等会儿再试」。
     */
    fun isNetwork(message: String): Boolean {
        if (isAuth(message)) return false
        val m = message.lowercase()
        return NETWORK_MARKERS.any { m.contains(it) }
    }

    /** 一句能直接放在界面上的话。 */
    fun describe(message: String): String {
        val raw = message.trim()
        return when {
            isAuth(raw) -> "API Key 校验没过，检查一下设置"
            isNetwork(raw) -> "网络没连上"
            raw.isEmpty() -> "识别失败"
            else -> raw
        }
    }

    /**
     * 历史记录里那句「为什么没有字」。
     *
     * 与 [describe] 分开：这里是事后回看，要说清**当时**发生了什么，
     * 而不是「现在网络不行」。用户过两天翻到这条时，需要知道
     * 「这段声音还在、还能再识别一次」。
     */
    fun describeForHistory(message: String): String {
        val raw = message.trim()
        return when {
            isAuth(raw) -> "识别服务的 Key 没过校验"
            isNetwork(raw) -> "当时网络断了，没能转成文字"
            raw.isEmpty() -> "没有转出文字"
            else -> raw
        }
    }
}
