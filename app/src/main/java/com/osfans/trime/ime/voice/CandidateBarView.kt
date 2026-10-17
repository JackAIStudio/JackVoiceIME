/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.ime.keyboard.InputFeedbackManager

/**
 * 候选条：浮在键盘上方的那一条（原来 Trime 画预编辑/方案提示的位置）。
 *
 * 左边是正在拼的拼音，中间是候选，右边 `⌄` 展开整页候选。
 * 背景和键盘同色、只做上方圆角，看起来就像键盘向上长出来一截。
 */
class CandidateBarView(
    context: Context,
) : LinearLayout(context) {
    var onCandidatePick: ((Int) -> Unit)? = null
    var onExpandTap: (() -> Unit)? = null

    private val pinyinLabel =
        TextView(context).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(120)
            visibility = GONE
        }

    private val candidates =
        CandidateStripView(context).apply {
            onPick = { index -> onCandidatePick?.invoke(index) }
        }

    private val expandButton =
        TextView(context).apply {
            text = "\u2304"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                InputFeedbackManager.keyPressVibrate(this)
                onExpandTap?.invoke()
            }
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(BACKGROUND)
                val r = dp(16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        addView(
            pinyinLabel,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(16)
            },
        )
        addView(
            candidates,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        addView(
            expandButton,
            LayoutParams(dp(40), LayoutParams.MATCH_PARENT),
        )
    }

    fun update(
        items: Array<CandidateProto>,
        highlighted: Int,
    ) {
        candidates.update(items, highlighted)
        scrollTo(0, 0)
    }

    /** 顶栏左边那截拼写（行内预编辑的内容）。 */
    fun setComposingText(text: String) {
        pinyinLabel.text = text
        pinyinLabel.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    /** ⌄ / ⌃：展开状态时箭头朝上，再点收起。 */
    fun setExpanded(expanded: Boolean) {
        expandButton.text = if (expanded) "\u2303" else "\u2304"
    }

    fun clear() {
        candidates.clear()
        setComposingText("")
        setExpanded(false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val HEIGHT_DP = 44

        private const val BACKGROUND = 0xFFF1F1F1.toInt()
        private const val TEXT_SECONDARY = 0xFF8A8A8A.toInt()
    }
}
