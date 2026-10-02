package com.example.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing and size scale.
 *
 * Every gap in the UI is one of these steps, so vertical rhythm is a decision made
 * once rather than re-derived at each call site. The steps are multiples of 4dp with
 * a 20dp screen gutter, which is generous enough to feel premium on a phone without
 * wasting horizontal space on a small one.
 */
object Dimens {
    // Spacing scale
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp

    /** The horizontal gutter every screen uses. */
    val screenH = 20.dp

    /** Bottom padding added to scrollable content so the last card clears the bar. */
    val contentBottom = 32.dp

    /** Minimum touch target. 48dp is the accessibility floor. */
    val touchTarget = 48.dp

    /** Height of a primary button. */
    val buttonHeight = 54.dp

    /** A standard icon inside a control. */
    val icon = 20.dp

    /** A larger, hero-adjacent icon. */
    val iconLarge = 26.dp

    /** The circular pronunciation control on the deck. */
    val audioButton = 64.dp

    /** A small status dot. */
    val dot = 8.dp

    /** The progress ring diameter on the dashboard. */
    val ring = 96.dp
}
