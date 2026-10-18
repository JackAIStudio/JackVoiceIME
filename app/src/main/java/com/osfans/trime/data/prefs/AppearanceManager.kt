/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.appContext
import com.osfans.trime.util.isNightMode

/**
 * 全局外观模式：自动 / 浅色 / 深色。
 *
 * 这是**唯一**的模式来源：主界面（AppCompat 的 DayNight）、键盘主题配色、
 * 以及 JackVoice 自己手搭的键盘 UI 都从这里取「现在是不是深色」，
 * 不再各读各的 `uiMode`。默认 [AppPrefs.Advanced.UiMode.AUTO]，跟随系统。
 *
 * 系统深浅色变化时，[TrimeApplication] 调用 [onConfigurationChanged]，这里只在
 * 「自动」模式下把变化转给监听者；显式选了浅色/深色就不受系统影响。
 */
object AppearanceManager {
    fun interface OnChangeListener {
        /** [isNight] 是**生效后**的深浅色，不是系统原始的。 */
        fun onAppearanceChanged(isNight: Boolean)
    }

    private val onChangeListeners = WeakHashSet<OnChangeListener>()

    private var currentMode = AppPrefs.Advanced.UiMode.AUTO
    private var systemNight = false
    private var lastNight: Boolean? = null
    private var initialized = false

    private lateinit var uiModePreference: PreferenceDelegate<AppPrefs.Advanced.UiMode>

    /**
     * 偏好本身是 [WeakHashSet] 弱引用，监听器必须由这个 object 强引用住，
     * 否则第一次 GC 之后就收不到设置了。
     */
    @Keep
    private val modeListener =
        PreferenceDelegate.OnChangeListener<AppPrefs.Advanced.UiMode> { _, value ->
            currentMode = value
            applyAppCompatMode()
            notifyIfChanged()
        }

    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        val prefs = AppPrefs.defaultInstance()
        uiModePreference = prefs.advanced.uiMode
        currentMode = uiModePreference.getValue()
        systemNight = appContext.resources.configuration.isNightMode()
        lastNight = effectiveNight()
        applyAppCompatMode()
        uiModePreference.registerOnChangeListener(modeListener)
        initialized = true
    }

    fun mode(): AppPrefs.Advanced.UiMode = currentMode

    /**
     * 当前是否该用深色。[configuration] 用于初始化阶段（主题加载、进程配置变化）
     * 传入当时那份配置；不传就用 Application 已经收到的系统深浅色状态。
     * 显式浅色/深色永远覆盖系统。
     */
    fun isNight(configuration: Configuration? = null): Boolean = when (currentMode) {
        AppPrefs.Advanced.UiMode.AUTO -> {
            configuration?.isNightMode()
                ?: if (initialized) systemNight else appContext.resources.configuration.isNightMode()
        }

        AppPrefs.Advanced.UiMode.LIGHT -> false

        AppPrefs.Advanced.UiMode.DARK -> true
    }

    fun addOnChangedListener(listener: OnChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnChangeListener) {
        onChangeListeners.remove(listener)
    }

    fun onConfigurationChanged(configuration: Configuration) {
        val night = configuration.isNightMode()
        if (night == systemNight) return
        systemNight = night
        if (currentMode == AppPrefs.Advanced.UiMode.AUTO) notifyIfChanged()
    }

    private fun effectiveNight(): Boolean = when (currentMode) {
        AppPrefs.Advanced.UiMode.AUTO -> systemNight
        AppPrefs.Advanced.UiMode.LIGHT -> false
        AppPrefs.Advanced.UiMode.DARK -> true
    }

    private fun notifyIfChanged() {
        val night = effectiveNight()
        if (lastNight == night) return
        lastNight = night
        onChangeListeners.forEach { it.onAppearanceChanged(night) }
    }

    private fun applyAppCompatMode() {
        val nightMode =
            when (currentMode) {
                AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
