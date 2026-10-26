/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.AppearanceManager
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.voice.VoiceAudio
import com.osfans.trime.ime.voice.VoiceHistory
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.worker.BackgroundSyncWork
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import splitties.systemservices.userManager
import timber.log.Timber
import java.io.File
import kotlin.system.exitProcess

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    val coroutineScope = MainScope() + CoroutineName("TrimeApplication")

    private val rimeIntentReceiver = RimeIntentReceiver()

    /**
     * 外观模式变化（设置里切换，或「自动」时系统深浅色变了）后，
     * 让 Rime 主题切到对应的深/浅配色。`AppearanceManager` 里的监听器是弱引用，
     * 必须由 Application 强引用住。
     */
    @Keep
    private val onAppearanceChangeListener =
        AppearanceManager.OnChangeListener { night ->
            runCatching { ColorManager.onSystemNightModeChange(night) }
                .onFailure { Timber.w(it, "Failed to apply appearance to the theme") }
        }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            RimeDaemon.getFirstSessionOrNull()?.also {
                // try to shutdown rime gracefully
                RimeDaemon.stopRime()
            }
            exitProcess(0)
        }
    }

    private fun registerBroadcastReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    var isDirectBootMode = false
        private set

    val directBootAwareContext: Context
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

    val externalFilesDir: File
        get() = with(directBootAwareContext) {
            getExternalFilesDir(null) ?: filesDir
        }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val ctx = directBootAwareContext

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                val crashTime = System.currentTimeMillis()
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPrefs.getLong(lastCrashTimePrefKey, -1L)
                sharedPrefs.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                startActivity(
                    Intent(ctx, LogActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra(LogActivity.FROM_CRASH, true)
                        // avoid transaction overflow
                        val truncated =
                            e.stackTraceToString().let {
                                if (it.length > MAX_STACKTRACE_SIZE) {
                                    it.take(MAX_STACKTRACE_SIZE) + "<truncated>"
                                } else {
                                    it
                                }
                            }
                        putExtra(LogActivity.CRASH_STACK_TRACE, truncated)
                    },
                )
                exitProcess(10)
            }
        }
        instance = this
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(
                    object : Timber.DebugTree() {
                        override fun createStackElementTag(element: StackTraceElement): String = "${super.createStackElementTag(element)}|${element.fileName}:${element.lineNumber}"

                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            super.log(
                                priority,
                                "[${Thread.currentThread().name}] ${tag?.substringBefore('|')}",
                                "${tag?.substringAfter('|')}] $message",
                                t,
                            )
                        }
                    },
                )
            } else {
                Timber.plant(
                    object : Timber.Tree() {
                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            if (priority < Log.INFO) return
                            Log.println(priority, "[${Thread.currentThread().name}]", message)
                        }
                    },
                )
            }
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

            Timber.d("isDirectBootMode=$isDirectBootMode")

            val appPrefs = AppPrefs.initDefault(sharedPreferences)
            // 外观模式要在任何 Activity 起来之前定下来：AppCompat 的 DayNight 和
            // 输入法自己的配色都从这里取。默认「自动」，跟随系统。外观出问题不该
            // 把后面的 Rime 初始化一起带走，所以单独兜一层。
            runCatching {
                AppearanceManager.init(this)
                AppearanceManager.addOnChangedListener(onAppearanceChangeListener)
            }.onFailure { Timber.w(it, "Failed to initialize appearance mode") }
            // JackVoice：键盘震动改成默认开。老安装里这个键可能已经被 Trime 的老默认值写成 false，
            // 这里做一次性迁移；之后用户自己在设置里关掉就不会再被覆盖。
            if (!sharedPreferences.getBoolean(HAPTICS_MIGRATION_KEY, false)) {
                appPrefs.keyboard.vibrateOnKeyPress.setValue(true)
                sharedPreferences.edit(commit = true) { putBoolean(HAPTICS_MIGRATION_KEY, true) }
            }
            // 上次录音被打断（进程被杀 / 输入法被回收）：把半截 WAV 救回来，
            // 并在历史里补一条失败记录。万物留痕——那段声音不该就这么没了。
            runCatching {
                val recovered = VoiceAudio.recoverInterrupted(ctx)
                if (recovered.isNotEmpty()) VoiceHistory.adoptOrphans(ctx, recovered)
            }.onFailure { Timber.w(it, "恢复上次中断的录音失败") }
            // record last pid for crash logs
            appPrefs.internal.pid.apply {
                val currentPid = Process.myPid()
                lastPid = getValue()
                Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
                setValue(currentPid)
            }
            ClipboardHelper.init(ctx)
            CollectionHelper.init(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDirectBootMode) {
                AppPrefs.defaultInstance().syncToDeviceEncryptedStorage()
                ThemeManager.syncToDeviceEncryptedStorage()
            }
            installRimePatches()
            registerBroadcastReceiver()
            startWorkManager()
        } catch (e: Exception) {
            e.fillInStackTrace()
            return
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            // 「自动」模式下系统深浅色变了，才需要通知主题和输入法重绘；
            // 显式选了浅色/深色就保持原样。
            AppearanceManager.onConfigurationChanged(newConfig)
        } catch (e: Exception) {
            Timber.w(e, "Something wrong on configuration changed")
        }
    }

    /**
     * 把 luna_pinyin 的默认状态改成「简体输出 + 只留中英/繁简两个开关」。
     *
     * 以前是直接改 submodule 里的 `luna_pinyin.schema.yaml`：那个仓指向 Rime 官方，
     * 我们写不回去，换台机器 clone 下来这处改动就没了。现在改成写一份 Rime 标准的
     * `*.custom.yaml` 到用户目录，仓库自身是自洽的。
     */
    private fun installRimePatches() {
        val dir = File(externalFilesDir, "rime").also { it.mkdirs() }
        val patch = File(dir, "luna_pinyin.custom.yaml")
        if (patch.exists()) return
        runCatching {
            patch.writeText(
                """
                patch:
                  switches:
                    - name: ascii_mode
                      reset: 0
                      states: [ 中文, 西文 ]
                    - name: simplification
                      reset: 1
                      states: [ 繁体, 简体 ]
                """.trimIndent() + "\n",
            )
        }.onFailure { Timber.w(it, "写入 luna_pinyin 补丁失败") }
    }

    private fun startWorkManager() {
        coroutineScope.launch {
            BackgroundSyncWork.start(directBootAwareContext)
        }
    }

    companion object {
        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() = instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid

        private const val MAX_STACKTRACE_SIZE = 128000

        private const val HAPTICS_MIGRATION_KEY = "jackvoice_haptics_default_v1"

        /**
         * This permission is requested by com.android.shell, makes it possible to start
         * deploy from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a com.osfans.trime.action.DEPLOY
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}
