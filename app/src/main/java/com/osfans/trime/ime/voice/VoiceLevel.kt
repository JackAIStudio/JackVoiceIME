/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 把 16bit 单声道 PCM 换算成 0..1 的**说话音量**，给录音圆里的波形用。
 *
 * 关键不是「有多响」，而是「比环境底噪响多少」：手机麦克风在安静房间里也有
 * 稳定的底噪（实测这台手机的麦克风 RMS 约 1000，换算过来 -30 dBFS），直接按绝对音量画，
 * 用户明明没说话，五根柱子也会一直跳——这正是之前那个问题的来源。
 *
 * 所以这里维护一条会自己跟的底噪线：
 *   - 掉下去跟得快（0.4/块，约 0.2s）——一停嘴就落到环境噪声；
 *   - 涨上去跟得慢（0.003/块，约 30s）——说话不会被当成底噪抬走。
 *
 * 波形只画高出底噪 [GATE_ABOVE_FLOOR_DB] 的部分，再加 [DEADBAND_DB] 的死区，
 * 环境噪声的小幅波动不会让柱子抖；说话比底噪高 10~20 dB，柱子自然就起来了。
 */
class VoiceLevel {
    /** 当前环境的底噪（dBFS）。NaN 表示还在预热、没定下来。 */
    private var noiseFloorDb = Double.NaN

    /**
     * 开头几块先只看不画：AudioRecord 刚起来的第一块经常是全 0 或很轻的预热块，
     * 拿它当底噪会把整段录音的音量顶满。取开头几块里最响的那块当底噪，
     * 既有预热静音、又有环境噪声时也能落到环境噪声上。
     */
    private val warmupDb = DoubleArray(WARMUP_CHUNKS)
    private var warmupCount = 0

    /** 重新开始录音时清掉底噪估计。 */
    fun reset() {
        noiseFloorDb = Double.NaN
        warmupCount = 0
    }

    /**
     * 从一块 16bit 小端 PCM 里算音量。
     *
     * @param buf 录音缓冲
     * @param length 本次实际读到的字节数（偶数）
     */
    fun fromPcm(
        buf: ByteArray,
        length: Int,
    ): Float {
        val usable = minOf(length, buf.size) and 1.inv()
        if (usable <= 0) return 0f
        var sum = 0.0
        var i = 0
        while (i < usable) {
            val sample = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (sample * sample).toDouble()
            i += 2
        }
        val rms = sqrt(sum / (usable / 2))
        return fromRms(rms)
    }

    /** RMS（0..32768）→ 0..1。 */
    fun fromRms(rms: Double): Float {
        val db = dbOf(rms)
        if (warmupCount < WARMUP_CHUNKS) {
            warmupDb[warmupCount++] = db
            if (warmupCount < WARMUP_CHUNKS) return 0f
            val peak = warmupDb.maxOrNull() ?: MIN_DB
            // 开头就很响（用户点完立刻开口）或者从头到尾都是数字静音，
            // 都不适合直接当底噪；用保守的默认值，宁可多显示一点也别把说话吞掉。
            noiseFloorDb =
                if (peak > SPEECH_LIKE_DB || peak < MIN_TRUSTED_FLOOR_DB) DEFAULT_FLOOR_DB else peak
            return 0f
        }
        val floor = noiseFloorDb
        noiseFloorDb =
            if (db < floor) {
                floor + (db - floor) * FLOOR_DOWN
            } else {
                floor + (db - floor) * FLOOR_UP
            }

        val over = db - (noiseFloorDb + GATE_ABOVE_FLOOR_DB) - DEADBAND_DB
        if (over <= 0) return 0f
        val normalized = (over / RANGE_DB).coerceIn(0.0, 1.0)
        return normalized.pow(CURVE).toFloat().coerceIn(0f, 1f)
    }

    private fun dbOf(rms: Double): Double {
        if (!rms.isFinite() || rms <= 0.0) return MIN_DB
        return (20.0 * log10((rms / FULL_SCALE).coerceAtMost(1.0))).coerceAtLeast(MIN_DB)
    }

    companion object {
        /** 16bit PCM 的满量程。 */
        private const val FULL_SCALE = 32768.0

        private const val MIN_DB = -80.0

        /** 开头先看几块再定底噪（100ms 一块 → 300ms）。 */
        private const val WARMUP_CHUNKS = 3

        /** 开头最响的一块超过这个值，多半是用户已经开口了。 */
        private const val SPEECH_LIKE_DB = -28.0

        /** 底噪低于这个值说明采到的是数字静音，不能信。 */
        private const val MIN_TRUSTED_FLOOR_DB = -40.0

        /** 分不清开头是底噪还是说话时，用一个偏保守的默认底噪。 */
        private const val DEFAULT_FLOOR_DB = -40.0

        /** 高出底噪多少 dB 才开始算「有声音」。 */
        private const val GATE_ABOVE_FLOOR_DB = 4.0

        /** 高过门限后的死区，吸掉环境噪声的轻微起伏。 */
        private const val DEADBAND_DB = 2.0

        /** 从门限到满格跨多少 dB。正常说话大约高出底噪 10~20 dB。 */
        private const val RANGE_DB = 22.0

        /** 曲线指数：<1 把中等音量抬一点。 */
        private const val CURVE = 0.8

        /** 底噪往下跟的速度（每 100ms 一块）。 */
        private const val FLOOR_DOWN = 0.4

        /** 底噪往上跟的速度：很慢，免得说话把底噪抬走。 */
        private const val FLOOR_UP = 0.003
    }
}
