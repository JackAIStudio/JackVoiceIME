/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音落盘。**只要说了话，这段声音就一定在本地**。
 *
 * 以前录音只活在内存里：一块 PCM 从 [VoiceRecorder] 直接喂给流式识别，
 * 网络一断、终稿没回来、进程被系统回收，这句话就永久消失——连「有一条失败的
 * 记录」都不存在，用户想补都无从下手。桌面版从第一天就存 WAV，手机端补上同一件事。
 *
 * ## 格式与时机
 *
 * 16kHz / 16bit / 单声道，正是豆包流式识别要的那一种，重放时不需要任何转码。
 * 每收到一块 PCM 就直接写进文件（[RandomAccessFile] 不经用户态缓冲，进程被杀
 * 也不会丢已写入的部分），先把 44 字节 WAV 头占位，收尾时再把长度回填。
 *
 * ## 崩溃恢复
 *
 * 录到一半被中断的文件叫 `xxx.wav.part`。下次启动时 [recoverInterrupted]
 * 把能救的都救回来：长度补进头里、去掉 `.part` 后缀，交给
 * [VoiceHistory.adoptOrphans] 补一条失败记录。用户能看见它、能点重新识别。
 */
object VoiceAudio {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /** 16k/16bit/单声道：一秒钟 32000 字节。 */
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

    private const val DIR_NAME = "voice_audio"
    private const val WAV_HEADER_BYTES = 44
    private const val PART_SUFFIX = ".part"

    data class Artifact(
        val fileName: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val sampleRate: Int = SAMPLE_RATE,
        val channels: Int = CHANNELS,
        val bitsPerSample: Int = BITS_PER_SAMPLE,
    ) {
        val exists: Boolean
            get() = true
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

    fun file(
        ctx: Context,
        fileName: String,
    ): File? {
        if (fileName.isBlank() || fileName.contains('/') || fileName.contains("..")) return null
        return File(dir(ctx), fileName)
    }

    fun exists(
        ctx: Context,
        fileName: String,
    ): Boolean = file(ctx, fileName)?.isFile == true

    /**
     * 开一段新录音。**失败不该拦住录音本身**——存不下就只丢音频，
     * 识别照常进行，返回 null 由调用方处理。
     */
    fun create(
        ctx: Context,
        entryId: String,
        stampMillis: Long = System.currentTimeMillis(),
    ): Writer? = runCatching {
        val directory = dir(ctx).apply { mkdirs() }
        if (!directory.isDirectory) error("无法创建录音目录")
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(stampMillis))
        val base = "${stamp}_${entryId.take(8)}.wav"
        Writer(File(directory, base + PART_SUFFIX), base)
    }.onFailure { Timber.w(it, "创建录音文件失败，本次只识别不落盘") }.getOrNull()

    /**
     * 边录边写。整个对象的公开方法都在同一把锁里，
     * 因为 [write] 从录音线程进来、[finish] 从主线程进来。
     */
    class Writer internal constructor(
        private val temp: File,
        private val finalName: String,
    ) {
        private val raf = RandomAccessFile(temp, "rw")
        private var pcmBytes = 0L
        private var closed = false

        init {
            raf.setLength(0)
            raf.write(ByteArray(WAV_HEADER_BYTES))
        }

        val fileName: String get() = finalName

        /** 已写入的音频时长；录音过程中面板可以拿它显示。 */
        @Synchronized
        fun durationMs(): Long = pcmBytes * 1000 / BYTES_PER_SECOND

        @Synchronized
        fun write(pcm: ByteArray) {
            if (closed || pcm.isEmpty()) return
            runCatching {
                raf.seek(WAV_HEADER_BYTES + pcmBytes)
                raf.write(pcm)
                pcmBytes += pcm.size
            }.onFailure { Timber.w(it, "写入录音失败，后续音频可能缺失") }
        }

        /** 收尾：回填头、改名。没有任何有效音频时删掉文件并返回 null。 */
        @Synchronized
        fun finish(): Artifact? {
            if (closed) return null
            closed = true
            runCatching {
                raf.seek(0)
                raf.write(wavHeader(pcmBytes))
                raf.close()
            }.onFailure { Timber.w(it, "录音文件收尾失败") }

            if (pcmBytes <= 0) {
                runCatching { temp.delete() }
                return null
            }
            val finalFile = File(temp.parentFile, finalName)
            if (!temp.renameTo(finalFile)) {
                Timber.w("录音改名失败：${temp.name}")
                return null
            }
            return Artifact(
                fileName = finalName,
                durationMs = pcmBytes * 1000 / BYTES_PER_SECOND,
                sizeBytes = finalFile.length(),
            )
        }

        // 这里曾经有一个 abort()：把写了一半的文件删掉。
        //
        // 它挂在语音引擎的 cancel() 上，而那个 cancel 又是「音频焦点被抢」、
        // 「输入法被系统回收」、「界面状态自愈」这些路径的默认动作——**没有一个是
        // 用户说了「不要」**。结果是用户话说到一半被打断，那段声音永久消失，
        // 连「进程被杀还能救回来」那层兜底都被亲手绕过了。
        //
        // 现在任何中途结束都走 finish()：有声音就留成 WAV、有字就落历史。
        // 没有音频可留时 finish() 自己会删掉空文件——那不是「丢」，是本来就没有。
        //
        // **别再把 abort() 加回来。** 音频只该被用户显式发起的删除动作清掉
        // （历史页那条）。
    }

    /**
     * 启动时抢救上次没写完的录音。
     *
     * @return 救回来的文件名（不含 .part）。空文件直接删掉，不返回。
     */
    fun recoverInterrupted(ctx: Context): List<String> {
        val directory = dir(ctx)
        val parts = directory.listFiles { f -> f.isFile && f.name.endsWith(PART_SUFFIX) } ?: return emptyList()
        val recovered = mutableListOf<String>()
        for (part in parts) {
            val length = part.length()
            // 只有占位的头、一个字节音频都没有：没什么可救的。
            if (length <= WAV_HEADER_BYTES) {
                runCatching { part.delete() }
                continue
            }
            val ok =
                runCatching {
                    RandomAccessFile(part, "rw").use {
                        it.seek(0)
                        it.write(wavHeader(length - WAV_HEADER_BYTES))
                    }
                }.isSuccess
            if (!ok) {
                Timber.w("修复半截录音失败：${part.name}")
                continue
            }
            val name = part.name.removeSuffix(PART_SUFFIX)
            val target = File(directory, name)
            if (part.renameTo(target)) {
                recovered += name
                Timber.i("已救回中断的录音：$name（${(length - WAV_HEADER_BYTES) * 1000 / BYTES_PER_SECOND}ms）")
            }
        }
        return recovered
    }

    /** 读回 PCM（跳过 WAV 头），给重新识别用。短录音仍可整段读。 */
    fun readPcm(
        ctx: Context,
        fileName: String,
    ): ByteArray? {
        val f = file(ctx, fileName) ?: return null
        if (!f.isFile) return null
        return runCatching {
            val bytes = f.readBytes()
            if (bytes.size <= WAV_HEADER_BYTES) return null
            bytes.copyOfRange(WAV_HEADER_BYTES, bytes.size)
        }.onFailure { Timber.w(it, "读取录音失败：$fileName") }.getOrNull()
    }

    /** 按文件大小反推时长，用于恢复出来的半截录音。 */
    fun artifactFor(
        ctx: Context,
        fileName: String,
    ): Artifact? {
        val f = file(ctx, fileName) ?: return null
        if (!f.isFile) return null
        val size = f.length()
        return Artifact(
            fileName = fileName,
            durationMs = ((size - WAV_HEADER_BYTES).coerceAtLeast(0)) * 1000 / BYTES_PER_SECOND,
            sizeBytes = size,
        )
    }

    fun delete(
        ctx: Context,
        fileName: String,
    ) {
        file(ctx, fileName)?.let { runCatching { it.delete() } }
    }

    /** 整个录音目录占多少字节（设置页显示用）。 */
    fun totalBytes(ctx: Context): Long = runCatching {
        dir(ctx).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }.getOrDefault(0L)

    /**
     * 44 字节 WAV 头。长度用 Long 传进来只是为了避免调用方到处强转，
     * 真正写进去的是 32 位小端。
     */
    private fun wavHeader(pcmBytes: Long): ByteArray {
        val out = ByteArray(WAV_HEADER_BYTES)
        val dataSize = pcmBytes.toInt()
        fun putAscii(
            offset: Int,
            s: String,
        ) = s.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }

        fun putLe32(
            offset: Int,
            v: Int,
        ) {
            out[offset] = (v and 0xff).toByte()
            out[offset + 1] = (v shr 8 and 0xff).toByte()
            out[offset + 2] = (v shr 16 and 0xff).toByte()
            out[offset + 3] = (v shr 24 and 0xff).toByte()
        }

        fun putLe16(
            offset: Int,
            v: Int,
        ) {
            out[offset] = (v and 0xff).toByte()
            out[offset + 1] = (v shr 8 and 0xff).toByte()
        }

        putAscii(0, "RIFF")
        putLe32(4, 36 + dataSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putLe32(16, 16) // PCM 的 fmt 块固定 16 字节
        putLe16(20, 1) // audioFormat = PCM
        putLe16(22, CHANNELS)
        putLe32(24, SAMPLE_RATE)
        putLe32(28, BYTES_PER_SECOND)
        putLe16(32, CHANNELS * BITS_PER_SAMPLE / 8)
        putLe16(34, BITS_PER_SAMPLE)
        putAscii(36, "data")
        putLe32(40, dataSize)
        return out
    }
}
