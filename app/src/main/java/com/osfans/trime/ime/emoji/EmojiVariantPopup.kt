/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.emoji

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ui.jackvoice.JvColors

/**
 * 长按 emoji 弹出来的变体条：肤色、性别。
 *
 * 为什么是 [PopupWindow] 而不是塞进键盘布局里：
 * 它要浮在候选条**上方**，那块地方属于宿主 App 的窗口，不在输入法自己的布局里。
 * 输入法窗口本身可以弹 PopupWindow（它带着 IME 的 window token），
 * `androidx.emoji2` 的 emoji picker 也是这么做的。
 *
 * 没有变体的 emoji（🍕、🚀 这类）不弹，所以调用方要先问 [EmojiIndex.variantsOf]。
 */
class EmojiVariantPopup(
    private val context: Context,
) {
    private var popup: PopupWindow? = null

    /**
     * 在 [anchor] 上方弹出一条变体。
     *
     * @param base 被长按的那个 emoji 本身。**它排第一格**——用户长按常常只是想
     *   确认「还有没有别的样子」，第一格给本体最好按，也方便反悔。
     * @param variants 变体列表（不含本体）。
     * @param onPick 选中某一个（可能是本体）。
     */
    fun show(
        anchor: View,
        base: String,
        variants: List<String>,
        onPick: (String) -> Unit,
    ) {
        val all = (listOf(base) + variants).distinct()
        if (all.size <= 1) return
        dismiss()

        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(JvColors.keyBg)
                    }
                elevation = dp(8).toFloat()
            }

        all.forEach { emoji ->
            row.addView(
                android.widget.TextView(context).apply {
                    text = emoji
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    isClickable = true
                    setOnClickListener {
                        InputFeedbackManager.keyPressVibrate(this)
                        dismiss()
                        onPick(emoji)
                    }
                },
            )
        }

        val window =
            PopupWindow(
                row,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                false,
            ).apply {
                isOutsideTouchable = true
                isTouchable = true
                // 没有背景的话，点外面在 API < 23 上不会自动收起来
                setBackgroundDrawable(GradientDrawable().apply { setColor(0x00000000) })
                elevation = dp(8).toFloat()
            }

        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = row.measuredWidth
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val x = (location[0] + anchor.width / 2 - width / 2).coerceAtLeast(dp(4))
        val y = location[1] - row.measuredHeight - dp(6)

        runCatching { window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y.coerceAtLeast(0)) }
            .onFailure {
                // 拿不到 window token（比如键盘正要收）就安静地不弹，别弹个 Toast 打断输入
                window.dismiss()
                return
            }
        popup = window
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
