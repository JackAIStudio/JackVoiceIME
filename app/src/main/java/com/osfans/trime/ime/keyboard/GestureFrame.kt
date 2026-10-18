/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

open class GestureFrame(context: Context) : FrameLayout(context) {

    private var touchId = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startTime = 0L

    private var isLongPressed = false
    private var slideActivated = false
    private var swipeTriggered = false
    private var trackpadActive = false

    private var longPressJob: Job? = null
    private var repeatJob: Job? = null
    private var doubleTapJob: Job? = null

    private var lastTapTime = 0L
    private var lastSwipeBehavior: KeyBehavior = KeyBehavior.CLICK

    private val lifecycleScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    var onClick: (() -> Unit)? = null
    var onDoubleClick: (() -> Unit)? = null
    var onLazyDoubleClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null

    var onSlide: ((dx: Int, dy: Int, x: Float, y: Float) -> Unit)? = null

    var onPress: (() -> Unit)? = null
    var onRelease: ((behavior: KeyBehavior, longPress: Boolean) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onMove: ((x: Float, y: Float, longPress: Boolean) -> Unit)? = null
    var onSwipe: ((behavior: KeyBehavior) -> Unit)? = null
    var onTrackpadState: ((active: Boolean) -> Unit)? = null

    var isRepeatable = false
    var isSlideCursor = false
    var isSlideDelete = false
    var isTrackpad = false

    var hasLongPress = false
    var hasDouble = false
    var hasLazyDouble = false
    var hasPopup = false

    init {
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
        // avoid gaining focus unexpectedly
        isFocusable = false
        isFocusableInTouchMode = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                touchId = (touchId + 1) and 0xFFFF
                val currentTouchId = touchId
                startX = x
                startY = y
                lastX = startX
                lastY = startY
                startTime = SystemClock.elapsedRealtime()

                endTrackpad()
                isLongPressed = false
                slideActivated = false
                swipeTriggered = false
                lastSwipeBehavior = KeyBehavior.CLICK

                drawableHotspotChanged(x, y)
                isPressed = true
                if (vibrateOnKeyPress) InputFeedbackManager.keyPressVibrate(this)
                onPress?.invoke()

                if (hasLongPress || isRepeatable || hasPopup || isTrackpad) {
                    startLongPressJob(currentTouchId)
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)

                val dx = x - startX
                val dy = y - startY

                onMove?.invoke(x, y, isLongPressed)

                // 长按空格后进入触控板：两个方向都按步长累计移动。
                if (trackpadActive) {
                    val stepX = getNStep(lastX, x, slideStepSize.toFloat())
                    val stepY = getNStep(lastY, y, slideStepSize.toFloat())
                    if (stepX != 0 || stepY != 0) {
                        slideActivated = true
                        onSlide?.invoke(stepX, stepY, x, y)
                        if (stepX != 0) lastX = x
                        if (stepY != 0) lastY = y
                    }
                    return true
                }

                if ((isSlideCursor || isSlideDelete) && onSlide != null && !isLongPressed && swipeTravel > 0) {
                    if (!slideActivated) {
                        if (abs(dx) >= swipeTravel) {
                            slideActivated = true
                            lastX = startX
                        }
                    }

                    if (slideActivated) {
                        val step = getNStep(lastX, x, slideStepSize.toFloat())
                        if (step != 0) {
                            onSlide?.invoke(step, 0, x, y)
                            lastX = x
                        }
                    }
                }

                if (!isLongPressed) {
                    val behavior = detectSwipe(dx, dy)
                    if (behavior != lastSwipeBehavior) {
                        lastSwipeBehavior = behavior
                        if (behavior != KeyBehavior.CLICK) {
                            onSwipe?.invoke(behavior)
                        }
                    }
                }

                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = x - startX
                val dy = y - startY

                isPressed = false
                if (vibrateOnKeyRelease) InputFeedbackManager.keyPressVibrate(this)
                cancelJobs()

                if (trackpadActive) {
                    val moved = slideActivated
                    endTrackpad()
                    if (moved) {
                        onCancel?.invoke()
                    } else {
                        // 只是长按、没有拖动：仍然按一次空格。
                        dispatchBehavior(KeyBehavior.CLICK, false)
                    }
                    return true
                }

                if (slideActivated) {
                    onSlide?.invoke(0, 0, x, y)
                    onCancel?.invoke()
                    return true
                }

                if (isLongPressed) {
                    dispatchBehavior(KeyBehavior.LONG_CLICK, true)
                    return true
                }

                if (swipeTriggered) {
                    dispatchBehavior(lastSwipeBehavior, false)
                    return true
                }

                if (!hasDouble && !hasLazyDouble) {
                    dispatchBehavior(KeyBehavior.CLICK, false)
                    return true
                }

                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTapTime
                if (delta <= doubleTapTimeout) {
                    lastTapTime = 0
                    doubleTapJob?.cancel()
                    if (hasDouble) {
                        dispatchBehavior(KeyBehavior.DOUBLE_CLICK, false)
                    } else {
                        dispatchBehavior(KeyBehavior.LAZY_DOUBLE_CLICK, false)
                    }
                } else {
                    lastTapTime = now
                    if (hasLazyDouble && !hasDouble) {
                        doubleTapJob = lifecycleScope.launch {
                            delay(doubleTapTimeout.toLong())
                            if (lastTapTime == now) {
                                lastTapTime = 0
                                dispatchBehavior(KeyBehavior.CLICK, false)
                            }
                        }
                    } else {
                        dispatchBehavior(KeyBehavior.CLICK, false)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                cancelJobs()

                isLongPressed = false
                slideActivated = false
                swipeTriggered = false
                endTrackpad()

                onCancel?.invoke()
                return true
            }
        }

        return true
    }

    private fun startLongPressJob(currentTouchId: Int) {
        longPressJob = lifecycleScope.launch {
            delay(longPressTimeout.toLong())
            if (touchId != currentTouchId) return@launch
            if (swipeTriggered || slideActivated) return@launch
            isLongPressed = true

            if (vibrateOnKeyPress) InputFeedbackManager.keyPressVibrate(this@GestureFrame, true)

            if (isTrackpad) {
                trackpadActive = true
                onTrackpadState?.invoke(true)
            } else if (isRepeatable) {
                startRepeatJob()
            } else {
                performLongClick()
            }
        }
    }

    private fun startRepeatJob() {
        repeatJob = lifecycleScope.launch {
            try {
                while (true) {
                    if (vibrateOnKeyRepeat) InputFeedbackManager.keyPressVibrate(this@GestureFrame)
                    dispatchBehavior(KeyBehavior.CLICK, true)
                    delay(repeatInterval.toLong())
                }
            } finally {
                onCancel?.invoke()
            }
        }
    }

    private fun detectSwipe(dx: Float, dy: Float): KeyBehavior {
        val absDx = abs(dx)
        val absDy = abs(dy)

        val distance = if (absDx > absDy) absDx else absDy
        val elapsed = SystemClock.elapsedRealtime() - startTime

        val velocity = if (elapsed > 0) {
            (distance / elapsed) * 1000f
        } else {
            0f
        }

        val isSwipe =
            (swipeTravel > 0 && distance >= swipeTravel) ||
                (swipeVelocity > 0 && velocity >= swipeVelocity)
        swipeTriggered = isSwipe

        if (!isSwipe) return KeyBehavior.CLICK
        return if (absDx > absDy) {
            if (dx > 0) KeyBehavior.SWIPE_RIGHT else KeyBehavior.SWIPE_LEFT
        } else {
            if (dy > 0) KeyBehavior.SWIPE_DOWN else KeyBehavior.SWIPE_UP
        }
    }

    private fun dispatchBehavior(
        behavior: KeyBehavior,
        longPress: Boolean,
    ) {
        onRelease?.invoke(behavior, longPress)
        when (behavior) {
            KeyBehavior.CLICK -> performClick()
            KeyBehavior.DOUBLE_CLICK -> onDoubleClick?.invoke()
            KeyBehavior.LAZY_DOUBLE_CLICK -> onLazyDoubleClick?.invoke()
            KeyBehavior.SWIPE_LEFT -> onSwipeLeft?.invoke()
            KeyBehavior.SWIPE_RIGHT -> onSwipeRight?.invoke()
            KeyBehavior.SWIPE_UP -> onSwipeUp?.invoke()
            KeyBehavior.SWIPE_DOWN -> onSwipeDown?.invoke()
            else -> {}
        }
    }

    private fun cancelJobs() {
        longPressJob?.cancel()
        repeatJob?.cancel()
        doubleTapJob?.cancel()
    }

    private fun endTrackpad() {
        if (!trackpadActive) return
        trackpadActive = false
        onTrackpadState?.invoke(false)
    }

    fun getNStep(start: Float, end: Float, step: Float): Int = (if (start < end) 1 else -1) *
        floor(abs(end - start) / step).toInt()

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        hasLongPress = l != null
        super.setOnLongClickListener(l)
    }

    override fun onDetachedFromWindow() {
        endTrackpad()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        onClick?.invoke()
        return true
    }

    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        onLongClick?.invoke()
        return handled || onLongClick != null
    }

    companion object {
        private val swipeTravel by AppPrefs.defaultInstance().keyboard.swipeTravel
        private val swipeVelocity by AppPrefs.defaultInstance().keyboard.swipeVelocity
        private val longPressTimeout by AppPrefs.defaultInstance().keyboard.longPressTimeout
        private val repeatInterval by AppPrefs.defaultInstance().keyboard.repeatInterval
        private val doubleTapTimeout by AppPrefs.defaultInstance().keyboard.doubleTapTimeout
        private val slideStepSize by AppPrefs.defaultInstance().keyboard.slideStepSize
        private val vibrateOnKeyPress by AppPrefs.defaultInstance().keyboard.vibrateOnKeyPress
        private val vibrateOnKeyRelease by AppPrefs.defaultInstance().keyboard.vibrateOnKeyRelease
        private val vibrateOnKeyRepeat by AppPrefs.defaultInstance().keyboard.vibrateOnKeyRepeat
    }
}
