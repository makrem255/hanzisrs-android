package com.example.ui.theme

import androidx.compose.ui.graphics.Brush

/**
 * Gradient tokens.
 *
 * Gradients are used sparingly and always to say something: the accent sweep marks
 * the primary action, the AI sweep marks the tutor, the mint sweep marks mastery.
 * There is no decorative rainbow, and no gradient is applied to a surface that does
 * not carry one of those meanings.
 */

/** The brand sweep: electric blue into cyan. The primary-action identity. */
val AccentSweep: Brush = Brush.linearGradient(
    colors = listOf(AccentPrimary, AccentCyan)
)

/** The AI sweep: violet into blue. */
val AiSweep: Brush = Brush.linearGradient(
    colors = listOf(AccentViolet, AccentPrimary)
)

/** The mastery sweep: mint into cyan. */
val MintSweep: Brush = Brush.linearGradient(
    colors = listOf(AccentMint, AccentCyan)
)

/** The review sweep: amber into a warm red. */
val AmberSweep: Brush = Brush.linearGradient(
    colors = listOf(AccentAmber, AccentRed)
)

/** A quiet vertical sheen for hero cards: the top step into the card colour. */
val HeroSheen: Brush = Brush.verticalGradient(
    colors = listOf(DarkSurfaceElevated, DarkSurfaceCard)
)
