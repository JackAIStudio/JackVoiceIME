/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import timber.log.Timber

/**
 * 「我现在到底能不能拿到麦克风」——把系统能告诉我们的每一件事都问出来。
 *
 * ## 为什么需要这个
 *
 * 系统把麦克风静音时，`AudioRecord.read()` **不会报错**，它老实返回全零 PCM。
 * 于是界面上的波形柱一格都不动，用户却看不到任何解释——这正是
 * 「点了录音，柱子不跳，也没提示」的由来。录音线程自己只能看见「音量是 0」，
 * 分不清是**没人说话**、**被别的应用占了**、还是**系统把麦克风全局关了**。
 *
 * 这里把这几件事分开查，各自给一句人话。
 *
 * ## 为什么拿不到「是谁」
 *
 * [AudioRecordingConfiguration.getClientPackageName] 只对持有
 * `MODIFY_AUDIO_ROUTING` 的应用返回包名——那是 signature 级权限，第三方应用申请不到，
 * 拿到的永远是空串。所以这里**不假装能说出是哪个应用**，只回答：
 * 有没有别人在录、对方是不是隐私敏感源、我自己是不是被静音了。
 *
 * 想知道具体是谁，用户下拉通知栏点那颗绿色麦克风图标就能看到，那是系统 UI 的活。
 */
object MicStatus {
    /**
     * 麦克风拿不到的原因。`null` 表示「看起来没问题」。
     *
     * 顺序就是判定优先级：能确定的程度从高到低。
     */
    enum class Reason(
        /** 直接给用户看的整句话。 */
        val message: String,
        /** 给出这句之后，还要不要提示「去通知栏看是谁」。 */
        val pointsToIndicator: Boolean = false,
    ) {
        /** 录音权限被拒 / AppOps 不是 foreground。 */
        PermissionDenied("JackVoice 没有麦克风权限，去系统设置里允许"),

        /** Android 12+ 的全局麦克风开关被关了。 */
        MicMuted("系统的麦克风总开关关着，在快捷设置里打开"),

        /** 我们自己正被系统静音（别的应用把麦克风抢走了）。 */
        SelfSilenced(
            "麦克风被其他应用占用，先结束对方的录音",
            pointsToIndicator = true,
        ),

        /** 有别人在录，而且我们还没开始录。 */
        OthersRecording(
            "其他应用正在录音，可能抢走麦克风",
            pointsToIndicator = true,
        ),

        /** 录音已经开起来了，但拿到的全是零。 */
        AllSilent(
            "麦克风没有采到声音，可能被其他应用占用或硬件故障",
            pointsToIndicator = true,
        ),

        ;

        /** 面板上那句短提示：丢掉末尾的解释，只留判断。 */
        val short: String
            get() =
                when (this) {
                    PermissionDenied -> "没有麦克风权限"
                    MicMuted -> "系统麦克风已关闭"
                    SelfSilenced -> "麦克风被占用"
                    OthersRecording -> "其他应用在录音"
                    AllSilent -> "麦克风没有声音"
                }
    }

    /**
     * 一次探测的结果。
     *
     * @param reason 判定出来的原因；`null` 表示没发现问题。
     * @param others 别人的录音会话数（不含自己）
     * @param privacySensitive 别人的会话里有没有隐私敏感源（`VOICE_COMMUNICATION` /
     *   `CAMCORDER`）。隐私敏感源的优先级高于我们，**哪怕我们界面在最前面也照样被静音**，
     *   所以这一项要单独标出来。
     */
    data class Snapshot(
        val reason: Reason?,
        val others: Int = 0,
        val privacySensitive: Boolean = false,
    ) {
        val ok: Boolean get() = reason == null
    }

    /**
     * 开录**之前**探一次。
     *
     * 只回答「能不能开始」，不看别人录没录——别人在录不等于我们录不到
     * （普通应用之间还有界面优先级的说法），贸然拦下来反而会挡住本来能用的场景。
     * 真正的失败由 [start] 之后的全零判定来抓。
     */
    fun probeBeforeStart(ctx: Context): Snapshot {
        permissionProblem(ctx)?.let { return Snapshot(it) }
        if (isMicMuted(ctx)) return Snapshot(Reason.MicMuted)
        val others = othersRecording(ctx)
        return Snapshot(
            reason = null,
            others = others.size,
            privacySensitive = others.any { it.isPrivacySensitiveSource() },
        )
    }

    /**
     * 录音**进行中**：波形不动了，查一下为什么。
     *
     * @param rec 正在录的那个对象，用来问「我是不是被静音了」。传 `null` 就只查全局状态。
     */
    @Suppress("MissingPermission")
    fun probeWhileRecording(
        ctx: Context,
        rec: android.media.AudioRecord? = null,
    ): Snapshot {
        permissionProblem(ctx)?.let { return Snapshot(it) }
        if (isMicMuted(ctx)) return Snapshot(Reason.MicMuted)

        // 此刻**我们自己也在这份列表里**（录音已经开起来了），而排除不掉自己的那一路
        // （见 othersRecording 的注释），所以这里一律减一。
        val all = othersRecording(ctx)
        val others = (all.size - 1).coerceAtLeast(0)
        val privacy = all.any { it.isPrivacySensitiveSource() }

        // 先问自己的这一路：系统直接说「你这路被静音了」，最没有歧义。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mine = runCatching { rec?.activeRecordingConfiguration }.getOrNull()
            if (mine != null && runCatching { mine.isClientSilenced }.getOrDefault(false)) {
                return Snapshot(Reason.SelfSilenced, others = others, privacySensitive = privacy)
            }
        }

        // 没被系统点名静音，但确实一个字都收不到。
        // 此时有另一个隐私敏感源在录，基本可以断定是被它压住了。
        return Snapshot(
            if (privacy && others > 0) Reason.SelfSilenced else Reason.AllSilent,
            others = others,
            privacySensitive = privacy && others > 0,
        )
    }

    // ── 底层查询 ────────────────────────────────────────────────────────

    /**
     * 权限 / AppOps 问题。
     *
     * 只看权限清单是不够的：`pm grant` 之后 `RECORD_AUDIO` 会是 `granted=true`，
     * 但 AppOps 里仍是 `ignore`，录音照样被 audio policy 拒掉。
     * 真正的开关是 AppOps 的 `Uid mode` 要落在 `foreground`（或 `allow`）上。
     * 详见 JACKVOICE.md 里「录音放行看的是 AppOps，不是权限清单」。
     */
    private fun permissionProblem(ctx: Context): Reason? {
        val granted =
            runCatching {
                ctx.checkPermission(Manifest.permission.RECORD_AUDIO, Process.myPid(), Process.myUid())
            }.getOrNull() == PackageManager.PERMISSION_GRANTED
        if (!granted) return Reason.PermissionDenied

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ops = runCatching { ctx.getSystemService(AppOpsManager::class.java) }.getOrNull()
            val mode =
                runCatching {
                    ops?.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        ctx.packageName,
                    )
                }.getOrNull()
            // MODE_FOREGROUND 只在我们身处前台时放行——录音本来就要求前台，
            // 走到这里说明前台状态没拿到（比如输入法进程被降级）。
            if (mode != null && mode != AppOpsManager.MODE_ALLOWED && mode != AppOpsManager.MODE_FOREGROUND) {
                Timber.w("AppOps RECORD_AUDIO 不是放行状态：$mode")
                return Reason.PermissionDenied
            }
        }
        return null
    }

    /** Android 12+ 的整机麦克风开关。老系统没有这个概念，一律当作没关。 */
    private fun isMicMuted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val am = runCatching { ctx.getSystemService(AudioManager::class.java) }.getOrNull()
        return runCatching { am?.isMicrophoneMute == true }.getOrDefault(false)
    }

    /** 正在录音的其他应用。没有权限拿不到包名，但条数和音源是能看到的。 */
    private fun othersRecording(ctx: Context): List<AudioRecordingConfiguration> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val am = runCatching { ctx.getSystemService(AudioManager::class.java) }.getOrNull() ?: return emptyList()
        val configs = runCatching { am.activeRecordingConfigurations }.getOrNull() ?: return emptyList()
        // 这里**排除不掉自己**：`getClientUid()` 是 @hide，第三方应用调不到；
        // `getClientPackageName()` 又没有 MODIFY_AUDIO_ROUTING 就返回空串。
        // 所以返回的是「系统眼里所有在录的」，调用方自己判断要不要算上自己那一路。
        return configs.filter { runCatching { it.clientAudioSource }.getOrDefault(-1) != 0 }
    }

    /**
     * 隐私敏感音源：`VOICE_COMMUNICATION` / `CAMCORDER`。
     *
     * 这两种源的优先级压过普通应用的一切理由（包括「我的界面在最前面」），
     * 微信按住说话走的就是 `VOICE_COMMUNICATION`。
     */
    private fun AudioRecordingConfiguration.isPrivacySensitiveSource(): Boolean {
        val src = runCatching { clientAudioSource }.getOrDefault(-1)
        return src == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
            src == MediaRecorder.AudioSource.CAMCORDER
    }
}
