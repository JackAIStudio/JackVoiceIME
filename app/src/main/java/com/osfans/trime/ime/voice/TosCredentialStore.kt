/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * TOS 凭据不放进默认 SharedPreferences。
 *
 * Auto Backup 默认会备份 shared_prefs，长-lived AK/SK 跟着上 Google 备份并不合适。
 * 这里落在 [Context.getNoBackupFilesDir] 的应用私有目录里，系统备份不会带走。
 * 文件权限由 Android 应用沙箱保证；后续如果要再加密，可以在这一层接 Keystore。
 */
object TosCredentialStore {
    data class Credentials(
        val accessKeyId: String,
        val secretAccessKey: String,
    )

    private const val FILE_NAME = "tos-credentials.json"

    @Synchronized
    fun load(ctx: Context): Credentials? {
        val file = file(ctx)
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val accessKeyId = json.optString("accessKeyId").trim()
        val secretAccessKey = json.optString("secretAccessKey").trim()
        return Credentials(accessKeyId, secretAccessKey)
    }

    @Synchronized
    fun save(
        ctx: Context,
        accessKeyId: String,
        secretAccessKey: String,
    ) {
        val ak = accessKeyId.trim()
        val sk = secretAccessKey.trim()
        if (ak.isEmpty() && sk.isEmpty()) {
            file(ctx).delete()
            return
        }
        val body =
            JSONObject()
                .put("accessKeyId", ak)
                .put("secretAccessKey", sk)
                .toString()
        val target = file(ctx)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "$FILE_NAME.tmp")
        temp.writeText(body, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(body, Charsets.UTF_8)
            temp.delete()
        }
    }

    @Synchronized
    fun setAccessKey(
        ctx: Context,
        value: String,
    ) {
        val current = load(ctx)
        val ak = value.trim()
        save(ctx, ak, current?.secretAccessKey.orEmpty())
    }

    @Synchronized
    fun setSecretKey(
        ctx: Context,
        value: String,
    ) {
        val current = load(ctx)
        val sk = value.trim()
        save(ctx, current?.accessKeyId.orEmpty(), sk)
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, FILE_NAME)
}
