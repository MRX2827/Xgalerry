/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The player spring, late corner blend and drag thresholds are adapted from
 * AMORX / RhythmPlayerSheet.kt.
 */
package io.github.mrx2827.xgalerry

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

internal val viewerSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.001f
)

internal const val DismissFraction = 0.30f
internal const val DismissVelocityDp = 1000

internal fun cornerRetention(expansion: Float): Float {
    val blend = ((expansion - 0.82f) / 0.18f).coerceIn(0f, 1f)
    return 1f - blend * blend * blend
}
