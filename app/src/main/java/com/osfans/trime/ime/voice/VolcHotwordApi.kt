/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 火山热词表管理 API，和桌面版 `volc_hotword_api.rs` 使用同一个接口。
 *
 * 整表覆盖：文件每行 `词|10`。同步成功后，Android 和桌面版读的是同一张表。
 */
object VolcHotwordApi {
    private const val URL =
        "https://openspeech.bytedance.com/api/proxy/invoke/" +
            "?Action=UpdateBoostingTable&Version=2022-08-30"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    fun updateBoostingTable(
        apiKey: String,
        tableId: String,
        words: List<String>,
    ): Result<Unit> {
        val key = apiKey.trim()
        val table = tableId.trim()
        if (key.isEmpty()) return Result.failure(IllegalStateException("请先填写火山 API Key"))
        if (table.isEmpty()) return Result.failure(IllegalStateException("请先填写云端热词表 ID"))

        val content =
            HotwordSanitizer.sanitize(words)
                .joinToString("\n") { "$it|10" }
        if (content.isBlank()) return Result.failure(IllegalStateException("没有可同步的热词"))

        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("BoostingTableID", table)
                .addFormDataPart(
                    "File",
                    "hotwords.txt",
                    content.toRequestBody("text/plain".toMediaType()),
                ).build()
        val request =
            Request.Builder()
                .url(URL)
                .header("X-Api-Key", key)
                .header("Accept", "*/*")
                .post(body)
                .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}：${raw.take(200)}")
                }
                val root = runCatching { JSONObject(raw) }.getOrNull()
                    ?: error("解析同步响应失败：${raw.take(200)}")
                val meta = root.optJSONObject("ResponseMetadata")?.optJSONObject("Error")
                val message = meta?.optString("Message").orEmpty()
                if (message.isNotEmpty()) {
                    val code = meta?.optString("Code").orEmpty()
                    error(if (code.isEmpty()) message else "$message ($code)")
                }
                if (root.optJSONObject("Result")?.optJSONObject("BoostingTable") == null) {
                    error("同步成功但未返回热词表信息")
                }
            }
        }
    }
}
