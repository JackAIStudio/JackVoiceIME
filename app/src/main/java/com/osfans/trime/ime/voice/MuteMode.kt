/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * 说话时怎么按住别的 App 的声音。三档，从「完全不碰」到「最彻底」。
 *
 * ## 为什么一个开关不够
 *
 * 原来只有音频焦点一种做法。焦点是**协商**，不是强制：守规矩的播放器
 * （音乐、长视频）收到 `AUDIOFOCUS_LOSS` 会自己暂停，但**直播类应用
 * 常常根本不申请焦点**——华为测过 100 款应用，直播类未申请率达 39%
 * （[华为开发者联盟](https://developer.huawei.com/consumer/cn/forum/topic/41597864)）。
 *
 * B 站「视频能停、直播照响」就是这个差异：
 *
 * - 视频走 `MediaPlayer` / ExoPlayer，守焦点协议，也本来就有「暂停」概念；
 * - 直播用自家推流内核，图省事不申请焦点，而且**没有暂停这个概念**——
 *   你让它暂停，观众就漏看了一段，所以它宁可硬播。
 *
 * 对这类应用，协商毫无约束力，只有动系统音量才按得住。
 */
enum class MuteMode(
    val id: String,
    val label: String,
    val hint: String,
) {
    /** 什么都不做，说完就能听见自己在说什么之外的一切。 */
    Off("off", "不处理", "别的应用照常出声"),

    /** 只申请音频焦点。一个字节的系统音量都不动，最保守，但按不住直播。 */
    Focus("focus", "焦点协商", "音乐、视频会让出声音；直播类常常不理睬"),

    /**
     * 焦点 + 把媒体音量归零，说完原样恢复。
     *
     * 这是唯一对直播有效的办法：音量是系统层的，播放器没有拒绝的余地。
     * 代价是音量条会真的落到 0，所以恢复必须做到一字不差——
     * 见 [DictationAudioMute] 里的崩溃自愈。
     */
    Zero("zero", "音量归零", "直播也会静音；说完恢复原音量"),
    ;

    companion object {
        /** 默认档位：直播场景是刚需，而恢复有兜底。 */
        val Default: MuteMode = Zero

        fun fromId(id: String?): MuteMode = entries.firstOrNull { it.id == id } ?: Default

        /** 旧版本只有一个布尔开关：开 = 焦点 + 静音，关 = 什么都不做。 */
        fun fromLegacy(enabled: Boolean): MuteMode = if (enabled) Zero else Off
    }
}
