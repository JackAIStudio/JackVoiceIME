/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 本地 ASR 模型的下载 / 解压 / 就绪判断。
 *
 * 模型不进 APK：X-ASR 480ms int8 的 tar.bz2 约 128MB，解压后约 175MB。
 * 首次点「本地」时下载，之后直接读 [modelDir]。
 *
 * Android 没有 bzip2，所以用 commons-compress 解 tar.bz2。只取识别需要的
 * 四个文件，`bpe.model` 等热词文件先不落盘——这一版本地不做热词。
 */
object LocalModelManager {
    const val VERSION = "x-asr-480ms-int8-20260605"
    private const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2"

    /** 用于服务端没给 Content-Length 时估算进度；实际大小以写盘为准。 */
    private const val EXPECTED_TAR_BYTES = 133_900_000L

    private val REQUIRED =
        linkedMapOf(
            "encoder.int8.onnx" to 100_000_000L,
            "decoder.onnx" to 5_000_000L,
            "joiner.int8.onnx" to 1_000_000L,
            "tokens.txt" to 10_000L,
        )

    enum class State { Missing, Downloading, Ready, Failed }

    data class Status(
        val state: State,
        val progress: Int = 0,
        val message: String = "",
    )

    fun interface Listener {
        fun onStatus(status: Status)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var current = Status(State.Missing)

    @Volatile
    private var job: Thread? = null

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStatus(current)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun modelDir(ctx: Context): File = File(ctx.filesDir, "local_asr/$VERSION")

    fun isReady(ctx: Context): Boolean {
        val dir = modelDir(ctx)
        if (!File(dir, ".ready").isFile) return false
        return REQUIRED.all { (name, minBytes) -> File(dir, name).length() > minBytes }
    }

    fun status(ctx: Context): Status =
        when {
            isReady(ctx) -> Status(State.Ready, 100, "本地模型已就绪")
            job != null -> current
            current.state == State.Failed -> current
            else -> Status(State.Missing)
        }

    /**
     * 确保模型可用。已经在下载时直接返回 false，不重复起线程。
     *
     * @return true 表示模型已经就绪；false 表示刚开始下载 / 已有任务 / 失败
     */
    @Synchronized
    fun ensure(ctx: Context): Boolean {
        if (isReady(ctx)) {
            publish(Status(State.Ready, 100, "本地模型已就绪"))
            return true
        }
        if (job != null) return false

        val app = ctx.applicationContext
        publish(Status(State.Downloading, 0, "开始下载本地模型"))
        // 注意 kotlin.concurrent.thread 默认 start=true；这里要自己先挂到 job
        // 再启动，否则线程跑完把 job 置空之后，这一行又会把已完成的 Thread
        // 写回去，下一次 ensure 直接 IllegalThreadStateException。
        val worker =
            thread(start = false, name = "jackvoice-model-download") {
                runCatching { install(app) }
                    .onSuccess {
                        publish(Status(State.Ready, 100, "本地模型已就绪"))
                    }.onFailure {
                        Timber.w(it, "本地模型安装失败")
                        publish(Status(State.Failed, 0, it.message ?: "本地模型下载失败"))
                    }
                if (job === Thread.currentThread()) job = null
            }
        job = worker
        worker.start()
        return false
    }

    private fun install(ctx: Context) {
        val dir = modelDir(ctx)
        val parent = dir.parentFile ?: error("模型目录不可用")
        val tar = File(ctx.cacheDir, "$VERSION.tar.bz2")
        val staging = File(ctx.cacheDir, "$VERSION.staging")

        tar.delete()
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            download(tar)
            publish(Status(State.Downloading, 90, "正在解压本地模型"))
            extract(tar, staging)
            verify(staging)
            dir.deleteRecursively()
            parent.mkdirs()
            if (!staging.renameTo(dir)) {
                copyDirectory(staging, dir)
            }
            File(dir, ".ready").writeText(VERSION)
            Timber.i("本地模型安装完成：${dir.absolutePath}")
        } finally {
            tar.delete()
            staging.deleteRecursively()
        }
    }

    private fun download(target: File) {
        val client =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        val request = Request.Builder().url(DOWNLOAD_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val body = response.body ?: error("下载失败：响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: EXPECTED_TAR_BYTES
            var written = 0L
            var lastProgress = -1
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        val progress = ((written * 88) / total).toInt().coerceIn(0, 88)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            publish(Status(State.Downloading, progress, "正在下载本地模型"))
                        }
                    }
                    output.fd.sync()
                }
            }
            if (written < 10_000_000L) error("下载文件不完整")
        }
    }

    private fun extract(tarFile: File, outDir: File) {
        var found = 0
        var extractedBytes = 0L
        val totalBytes = REQUIRED.values.sum()
        var lastProgress = -1
        TarArchiveInputStream(
            BZip2CompressorInputStream(FileInputStream(tarFile), true),
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = File(entry.name).name
                if (name !in REQUIRED) continue
                val out = File(outDir, name)
                out.outputStream().use { tar.copyTo(it) }
                found += 1
                extractedBytes += entry.size.coerceAtLeast(out.length())
                val progress = (90 + extractedBytes * 9 / totalBytes).toInt().coerceIn(90, 99)
                if (progress != lastProgress) {
                    lastProgress = progress
                    publish(Status(State.Downloading, progress, "正在解压本地模型"))
                }
            }
        }
        if (found != REQUIRED.size) error("模型包不完整：只解出 $found/${REQUIRED.size} 个文件")
    }

    private fun verify(dir: File) {
        for ((name, minBytes) in REQUIRED) {
            val f = File(dir, name)
            if (!f.isFile || f.length() <= minBytes) {
                error("模型文件异常：$name（${f.length()} 字节）")
            }
        }
    }

    private fun copyDirectory(from: File, to: File) {
        to.mkdirs()
        from.listFiles().orEmpty().forEach { child ->
            val target = File(to, child.name)
            if (child.isDirectory) copyDirectory(child, target) else child.copyTo(target, overwrite = true)
        }
    }

    private fun publish(status: Status) {
        current = status
        main.post { listeners.toList().forEach { it.onStatus(status) } }
    }
}
