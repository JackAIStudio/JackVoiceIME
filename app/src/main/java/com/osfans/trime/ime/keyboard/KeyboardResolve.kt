// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

/**
 * Pure resolvers for the keyboard geometry cascade (key -> keyboard config ->
 * theme style). The three legacy merge conventions are kept explicit so the
 * semantics stay testable without Android dependencies.
 *
 * Convention A: positive-wins for gaps and heights. A value > 0 overrides the
 * style default; 0 or null means "unset" and falls through to the style value
 * (which may itself be 0). [unit] converts the selected raw value exactly once.
 */
internal fun resolvePositive(
    config: Int?,
    style: Int,
    unit: (Int) -> Int = { it },
): Int = if (config != null && config > 0) unit(config) else unit(style)

/**
 * Convention B: non-negative-wins for corners and borders. The config uses -1
 * as the "absent" sentinel, while 0 is a legal value that overrides the style.
 */
internal fun resolveNonNegative(
    config: Float?,
    style: Float,
): Float = if (config != null && config >= 0f) config else style

/** Int overload of [resolveNonNegative]. */
internal fun resolveNonNegative(
    config: Int?,
    style: Int,
): Int = if (config != null && config >= 0) config else style

/**
 * Convention C: non-zero-wins for the text/hint/press offsets, cascading
 * key -> keyboard -> style. Negative values are legal and kept.
 */
internal fun resolveOffset(
    key: Float,
    keyboard: Float,
    style: Float,
): Float = if (key != 0f) {
    key
} else if (keyboard != 0f) {
    keyboard
} else {
    style
}

/**
 * Picks the landscape value when the keyboard is in landscape mode and the
 * theme provides one; the portrait value is the fallback in every other case.
 */
internal fun pickLandscape(
    value: Int,
    landscapeValue: Int,
    landscape: Boolean,
): Int = if (landscape && landscapeValue > 0) landscapeValue else value

/**
 * What ascii_mode should become when a keyboard layout is attached, or null to
 * leave it alone.
 *
 * Switching layouts must not switch languages: the user picks 中/英 in the
 * toolbar capsule, and an editor may force ASCII. A layout only overrides that
 * when it explicitly opts in with `reset_ascii_mode: true` (the `letter`
 * keyboard of the English schema does).
 *
 * Previously every layout carried a preference and applied it, so
 * `123 → 字母` flipped the language to English and straight back to Chinese,
 * which also committed any composition in progress.
 */
internal fun asciiModeOnAttach(
    resetAsciiMode: Boolean,
    layoutAsciiMode: Boolean,
    currentAsciiMode: Boolean,
): Boolean? = if (resetAsciiMode && layoutAsciiMode != currentAsciiMode) layoutAsciiMode else null
