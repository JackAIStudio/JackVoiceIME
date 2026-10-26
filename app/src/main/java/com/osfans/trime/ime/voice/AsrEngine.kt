/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 听写引擎。只做两档：云端（豆包）和本地（sherpa-onnx）。
 *
 * 不叫 Online/Offline：本地也能完全离线，云端也能在没网时先录音。
 * 用户看到的就是「云端 / 本地」，内部不要再用网络状态反推引擎。
 */
enum class AsrEngine(
    val id: String,
    val label: String,
) {
    Cloud("cloud", "云端"),
    Local("local", "本地"),
    ;

    companion object {
        fun fromId(id: String?): AsrEngine = entries.firstOrNull { it.id == id } ?: Cloud
    }
}
