package com.example.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The app's vocabulary of haptic feedback.
 *
 * A wrapper rather than raw `performHapticFeedback` calls at each site for the same reason the
 * colour palette is tokens: the *choice* of haptic is a design decision, and scattering
 * `HapticFeedbackConstants.LONG_PRESS` through the codebase would mean the "grade recorded" and
 * "opened a menu" sensations could quietly drift apart, or that a future call site could pick a
 * constant that does not exist on the minimum SDK.
 *
 * ## Why this goes through [View] rather than `LocalHapticFeedback`
 *
 * `LocalHapticFeedback` only exposes two types in this version of Compose - long-press and text
 * handle - neither of which is a good fit for a light confirmation tick. Going to the view also
 * means the feedback is routed through `HapticFeedbackConstants`, and therefore obeys the
 * system's "touch feedback" and accessibility settings: a learner who has asked their phone not
 * to vibrate does not get vibrated by this app.
 *
 * Every constant used here predates API 24, so none of them need a version guard.
 */
class Haptics internal constructor(private val view: View) {

    /**
     * A light tick. Used for moments that acknowledge an input without claiming it succeeded:
     * selecting an option, moving between cards.
     */
    fun lightTick() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * A firm confirmation. Reserved for a completed, meaningful action - a recorded grade, a
     * recognised word, a finished session - so it stays meaningful.
     */
    fun confirm() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** A soft tick that is quieter than [lightTick], for stepping through a value or a list. */
    fun selection() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

/**
 * Remembers the [Haptics] for this composition.
 *
 * Keyed on the view, so an activity recreation gets a fresh handle rather than one pointing at a
 * detached view - a detached view silently does nothing, which would read as the feature having
 * stopped working.
 */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
