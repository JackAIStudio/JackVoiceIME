/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import com.volcengine.tos.TOSV2
import com.volcengine.tos.TOSV2ClientBuilder
import com.volcengine.tos.TosServerException
import com.volcengine.tos.model.`object`.GetObjectV2Input
import com.volcengine.tos.model.`object`.HeadObjectV2Input
import com.volcengine.tos.model.`object`.PutObjectInput
import org.json.JSONObject

/**
 * 直接用火山 TOS 读写 `glossary.json`，不经函数、不经 API 网关。
 * 凭据只存在这台设备的应用私有存储里。
 */
object GlossarySyncClient {
    data class Config(
        val accessKeyId: String,
        val secretAccessKey: String,
        val bucket: String = "jackvoice-glossary",
        val key: String = "glossary.json",
        val region: String = "cn-beijing",
        val endpoint: String = "tos-cn-beijing.volces.com",
    )

    data class Remote(
        val glossary: JSONObject,
        val etag: String,
    )

    class ConflictException : IllegalStateException("云端词库已被另一台设备更新")

    fun pull(config: Config): Remote {
        val client = client(config)
        val output =
            client.getObject(
                GetObjectV2Input()
                    .setBucket(config.bucket)
                    .setKey(config.key),
            )
        val bytes = output.content.use { it.readBytes() }
        val parsed = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: error("TOS 里的 glossary.json 不是合法 JSON")
        return Remote(parsed, output.etag.orEmpty())
    }

    fun push(
        config: Config,
        glossary: JSONObject,
        baseEtag: String,
    ): Remote {
        val bytes = glossary.toString().toByteArray(Charsets.UTF_8)
        val client = client(config)
        val output =
            try {
                client.putObject(
                    PutObjectInput()
                        .setBucket(config.bucket)
                        .setKey(config.key)
                        .setContent(bytes.inputStream())
                        .setContentLength(bytes.size.toLong())
                        .setIfMatch(baseEtag),
                )
            } catch (e: TosServerException) {
                if (e.statusCode == 412 || e.code == "PreconditionFailed") {
                    throw ConflictException()
                }
                throw e
            }
        return Remote(glossary, output.etag.orEmpty())
    }

    fun head(config: Config): String {
        val client = client(config)
        return client.headObject(
            HeadObjectV2Input()
                .setBucket(config.bucket)
                .setKey(config.key),
        ).etag.orEmpty()
    }

    private fun client(config: Config): TOSV2 =
        TOSV2ClientBuilder().build(
            config.region.trim(),
            config.endpoint.trim(),
            config.accessKeyId.trim(),
            config.secretAccessKey.trim(),
        )
}
