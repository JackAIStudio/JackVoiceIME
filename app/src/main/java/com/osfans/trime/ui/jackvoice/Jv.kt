/*
 * SPDX-FileCopyrightText: 2026 Jack
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.jackvoice

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.osfans.trime.R

/** JackVoice 主界面用的一小组构建块。原生 View 手搭，配色对齐 Typeless。 */
object Jv {
    const val BG = 0xFFFFFFFF.toInt()
    const val CARD = 0xFFF5F5F5.toInt()
    const val CARD_STRONG = 0xFFEFEFEF.toInt()
    const val TEXT = 0xFF111111.toInt()
    const val TEXT2 = 0xFF8A8A8A.toInt()
    const val ACCENT = 0xFF2B6BFE.toInt()
    const val DANGER = 0xFFE5484D.toInt()
    const val DIVIDER = 0xFFEDEDED.toInt()

    fun dp(
        ctx: Context,
        v: Int,
    ): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun round(
        color: Int,
        radiusPx: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }

    fun text(
        ctx: Context,
        value: String,
        sizeSp: Float,
        color: Int = TEXT,
        bold: Boolean = false,
    ): TextView =
        TextView(ctx).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    /** 页面大标题，对应 Typeless 各页顶部那个粗体标题。 */
    fun pageTitle(
        ctx: Context,
        value: String,
    ): TextView =
        text(ctx, value, 30f, TEXT, bold = true).apply {
            setPadding(dp(ctx, 20), dp(ctx, 22), dp(ctx, 20), dp(ctx, 12))
        }

    fun sectionLabel(
        ctx: Context,
        value: String,
    ): TextView =
        text(ctx, value, 13f, TEXT2).apply {
            setPadding(dp(ctx, 24), dp(ctx, 18), dp(ctx, 24), dp(ctx, 8))
        }

    /** 一张圆角卡片，竖向堆内容。 */
    fun card(
        ctx: Context,
        color: Int = CARD,
        radiusDp: Int = 18,
    ): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(color, dp(ctx, radiusDp))
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(ctx, 16)
                    marginEnd = dp(ctx, 16)
                    topMargin = dp(ctx, 8)
                }
        }

    /** 卡片里的一行：标题 +（可选）副标题 +（可选）右侧箭头/值。 */
    fun row(
        ctx: Context,
        title: String,
        subtitle: String? = null,
        trailing: String? = null,
        chevron: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): View {
        val box =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14))
                if (onClick != null) {
                    isClickable = true
                    setOnClickListener { onClick() }
                }
            }
        val column =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
        column.addView(text(ctx, title, 16f))
        if (!subtitle.isNullOrEmpty()) {
            column.addView(
                text(ctx, subtitle, 13f, TEXT2).apply {
                    setPadding(0, dp(ctx, 5), 0, 0)
                },
            )
        }
        box.addView(
            column,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (trailing != null) {
            box.addView(text(ctx, trailing, 15f, TEXT2))
        }
        if (chevron) {
            box.addView(
                icon(ctx, R.drawable.ic_baseline_arrow_right_24, TEXT2),
                LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)).apply {
                    marginStart = dp(ctx, 8)
                },
            )
        }
        return box
    }

    /** 统计格：大字 + 单位 + 小标签。 */
    fun statCell(
        ctx: Context,
        value: String,
        unit: String,
        label: String,
    ): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
            val head =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(text(ctx, value, 27f, TEXT, bold = true))
                    if (unit.isNotEmpty()) {
                        addView(
                            text(ctx, unit, 14f, TEXT).apply {
                                setPadding(dp(ctx, 4), 0, 0, dp(ctx, 3))
                            },
                        )
                    }
                }
            addView(head)
            addView(
                text(ctx, label, 13f, TEXT2).apply {
                    setPadding(0, dp(ctx, 8), 0, 0)
                },
            )
        }

    fun icon(
        ctx: Context,
        resId: Int,
        tint: Int,
    ): ImageView =
        ImageView(ctx).apply {
            setImageResource(resId)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    /** 小圆按钮里的图标（列表右侧的复制/删除）。 */
    fun iconButton(
        ctx: Context,
        resId: Int,
        tint: Int,
        onClick: () -> Unit,
    ): ImageView =
        icon(ctx, resId, tint).apply {
            background = round(CARD_STRONG, dp(ctx, 17))
            val pad = dp(ctx, 8)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 主按钮（Typeless 那种蓝色实心胶囊）。 */
    fun primaryButton(
        ctx: Context,
        label: String,
        onClick: () -> Unit,
    ): TextView =
        text(ctx, label, 16f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            background = round(ACCENT, dp(ctx, 24))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(ctx, 48),
                ).apply {
                    marginStart = dp(ctx, 16)
                    marginEnd = dp(ctx, 16)
                    topMargin = dp(ctx, 12)
                }
        }

    fun divider(ctx: Context): View =
        View(ctx).apply {
            setBackgroundColor(DIVIDER)
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(ctx, 1),
                ).apply {
                    marginStart = dp(ctx, 18)
                    marginEnd = dp(ctx, 18)
                }
        }

    /** 页面外壳：可滚动 + 统一内边距。 */
    fun page(content: View): ScrollView =
        ScrollView(content.context).apply {
            isFillViewport = true
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    fun column(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 28))
        }

    fun tintedDrawable(
        ctx: Context,
        resId: Int,
        tint: Int,
    ) = ContextCompat.getDrawable(ctx, resId)?.apply { setTint(tint) }

    /** 把秒数格式化成 Typeless 那种「1 hr 43 min」。 */
    fun humanDuration(millis: Long): Pair<String, String> {
        val totalMinutes = (millis / 60000L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours" to "hr $minutes min" else "$minutes" to "min"
    }

    fun humanCount(value: Int): Pair<String, String> =
        if (value >= 1000) {
            val k = value / 1000.0
            val s = if (k >= 10) "${k.toInt()}" else String.format("%.1f", k)
            s to "K 字"
        } else {
            "$value" to "字"
        }
}
