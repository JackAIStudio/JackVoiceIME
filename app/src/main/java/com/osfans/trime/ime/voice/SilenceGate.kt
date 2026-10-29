/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 数「连续拿到多少全零 PCM」，判定麦克风是不是根本收不到声音。
 *
 * ## 为什么需要单独一个类
 *
 * 系统把麦克风静音时，`AudioRecord.read()` **不报错**，它老实返回全零 PCM。
 * 录音线程无法据此区分三种情况：
 *
 *  - 用户就是在安静地思考，一个字没说；
 *  - 麦克风被别的应用抢走了；
 *  - 系统麦克风总开关被关了。
 *
 * 于是在界面上表现成「点了录音、波形柱一格都不动、也没有任何提示」——
 * 用户完全不知道发生了什么。这里把「连续多久全是零」这件事**用纯逻辑**数清楚，
 * 判断交给调用方（它能查权限、查系统静音、查别人的录音会话），测试也容易钉。
 *
 * ## 门限为什么是 1.5 秒
 *
 * `AudioRecord` 刚起来的第一两块本来就可能是零（预热），系统切换路由、
 * 对方刚好松手也会短暂为零。太短会误报，太长用户已经对着不动波形发呆了。
 */
class SilenceGate(
    /** 连续全零多久判定为「拿不到麦克风」。 */
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
) {
    private var silentMs = 0L

    /** 已经报过、还没恢复。避免整段录音里反复回调。 */
    private var reported = false

    /** 当前累计的全零时长，测试与日志用。 */
    val silentSoFarMs: Long get() = silentMs

    /** 这一轮是否已经报过「收不到声音」。 */
    val hasReported: Boolean get() = reported

    /**
     * 喂一块 PCM，返回**这一次**该不该报「拿不到麦克风」。
     *
     * 只在跨过门限的那一刻返回一次 `true`；之后一直返回 `false`，
     * 直到 [reset] 或有人真的说出话来（[dropped]）。
     *
     * @param buf PCM 缓冲
     * @param length 本次实际读到的字节数
     */
    fun feed(
        buf: ByteArray,
        length: Int,
    ): Boolean {
        if (!isAllZero(buf, length)) {
            // 有声音了：清零。报过的话，调用方会从 hasReported 看到「刚恢复」。
            silentMs = 0
            reported = false
            return false
        }
        silentMs += length / BYTES_PER_MS
        if (silentMs >= thresholdMs && !reported) {
            reported = true
            return true
        }
        return false
    }

    /** 这次喂进去的是不是全零。调用方据此决定要不要清「已报告」。 */
    fun isAllZero(
        buf: ByteArray,
        length: Int,
    ): Boolean {
        val usable = minOf(length, buf.size)
        for (i in 0 until usable) {
            if (buf[i].toInt() != 0) return false
        }
        return true
    }

    /** 重新开始录音时清掉。 */
    fun reset() {
        silentMs = 0
        reported = false
    }

    companion object {
        /** 16kHz / 16bit / 单声道：每毫秒 32 字节（32000 字节/秒 ÷ 1000）。 */
        private const val BYTES_PER_MS = 16_000 * 2 / 1000

        const val DEFAULT_THRESHOLD_MS = 1_500L
    }
}
