package com.example.data.review

import com.example.data.model.WordWithSrs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The rules of the Random Review arcade: the entry gate, the recent-selection memory, the
 * word-wheel timing, the wheel palette, and what counts as a pronunciation attempt.
 *
 * Pure by construction - no database, no view model, no composable - so every rule here is
 * directly unit-tested. The screen and the view model only execute what these functions decide.
 */

/** Words a learner must have saved before the wheel is allowed to spin. */
const val RANDOM_REVIEW_MIN_VOCABULARY = 5

/** How many recently selected words the wheel remembers and avoids. */
const val RANDOM_REVIEW_HISTORY_SIZE = 5

/** Valid pronunciation attempts against one word before the help state opens. */
const val RANDOM_REVIEW_MAX_ATTEMPTS = 3

/** How long the green success state holds before the next word, in milliseconds. */
const val RANDOM_REVIEW_SUCCESS_HOLD_MS = 500L

/** Full wheel duration for a fresh START, in milliseconds. Always under the 5s budget. */
const val SELECTION_FULL_MS = 3200L

/** Shorter wheel for automatic advances after a success. Same rules, less waiting. */
const val SELECTION_SHORT_MS = 1500L

/** Steps in the full wheel. */
const val SELECTION_FULL_STEPS = 14

/** Steps in the short wheel. */
const val SELECTION_SHORT_STEPS = 10

/** Whether the saved collection is large enough to start. Rechecked on every START press. */
fun meetsReviewMinimum(vocabulary: List<WordWithSrs>): Boolean =
    vocabulary.size >= RANDOM_REVIEW_MIN_VOCABULARY

/**
 * Records a selection in the recent history.
 *
 * Appends immediately - the spec requires the history to update the moment a word is drawn,
 * not when its attempts resolve - and keeps only the newest [RANDOM_REVIEW_HISTORY_SIZE]
 * entries, so the memory can neither grow nor go stale.
 */
fun updateRecentHistory(history: List<Long>, pickedId: Long): List<Long> =
    (history + pickedId).takeLast(RANDOM_REVIEW_HISTORY_SIZE)

/**
 * The pool the wheel cycles through and draws from: studied words first, the whole collection
 * only when nothing has been graded yet. Mirrors [pickRandomWord] so the animation previews
 * exactly the population the final draw comes from.
 */
fun selectionPool(vocabulary: List<WordWithSrs>): List<WordWithSrs> {
    val learned = vocabulary.filter { it.isLearned }
    return learned.ifEmpty { vocabulary }
}

/**
 * The per-step dwell times of the wheel, in milliseconds: a linear ramp from fast to slow.
 *
 * The deceleration is in the *schedule*, not in any audio rate or animation scale: step `i`
 * holds for `firstMs + i * step`, so the slowdown the learner sees is the slowdown they hear
 * (one tick per step) by construction. The sum is exactly the budget, never over it.
 *
 * @param totalMs the whole animation including its slowdown; must exceed `steps * firstMs`
 * @param steps how many words flash past
 * @param firstMs the opening dwell; later dwells only grow from here
 */
fun selectionSchedule(totalMs: Long, steps: Int, firstMs: Long = 60L): List<Long> {
    require(steps > 0) { "a wheel with no steps shows nothing" }
    val remaining = (totalMs - steps * firstMs).coerceAtLeast(0L)
    // Sum of 0..steps-1 is steps*(steps-1)/2; dividing the remainder over it makes the total
    // exact and the ramp strictly increasing whenever there is anything to divide.
    val slope = if (steps > 1) remaining.toDouble() / (steps * (steps - 1) / 2) else 0.0
    return List(steps) { i -> firstMs + (slope * i).toLong() }
}

/**
 * Whether a recogniser result counts as a pronunciation attempt against the current word.
 *
 * Only a completed, valid utterance counts: something was actually heard ([hypothesis] is
 * non-blank, which excludes [RecognitionVerdict.NothingHeard] by construction) and the
 * comparison ran against the word on screen ([forWordId] matches). Permission denials, busy
 * microphones, network failures and stale results for a previous word never increment the
 * counter, so a learner is never told they mispronounced a word the app failed to hear.
 */
fun countsAsAttempt(hypothesis: String?, forWordId: Long, currentWordId: Long): Boolean =
    forWordId == currentWordId && !hypothesis.isNullOrBlank()

/** Whether [attempts] valid attempts open the help state. */
fun helpRevealed(attempts: Int): Boolean = attempts >= RANDOM_REVIEW_MAX_ATTEMPTS

// ---------------------------------------------------------------------------
// The wheel palette: fifteen monochrome steps, white hanzi on top of every one.
// ---------------------------------------------------------------------------
//
// The wheel used to cycle fifteen hues. In the monochrome system the liveliness
// comes from brightness steps instead: dark charcoal greys that keep the wheel
// moving while holding contrast. Each entry is verified by
// `RandomArcadeTest.palette_holds_contrast_against_white` - a step that fails
// does not ship, it gets darkened until it passes. White text needs dark
// ground; the variety is in the stepping, which the eye reads as motion
// together with the cycling words.

/** One wheel background, as ARGB. */
@JvmInline
value class WheelColor(val argb: Int)

/** The fifteen wheel backgrounds: charcoal greys in ascending steps. */
val SELECTION_PALETTE: List<WheelColor> = listOf(
    WheelColor(0xFF101010.toInt()),
    WheelColor(0xFF161616.toInt()),
    WheelColor(0xFF1C1C1C.toInt()),
    WheelColor(0xFF212121.toInt()),
    WheelColor(0xFF262626.toInt()),
    WheelColor(0xFF2B2B2B.toInt()),
    WheelColor(0xFF303030.toInt()),
    WheelColor(0xFF343434.toInt()),
    WheelColor(0xFF383838.toInt()),
    WheelColor(0xFF3C3C3C.toInt()),
    WheelColor(0xFF404040.toInt()),
    WheelColor(0xFF444444.toInt()),
    WheelColor(0xFF484848.toInt()),
    WheelColor(0xFF4B4B4B.toInt()),
    WheelColor(0xFF4E4E4E.toInt()),
).also {
    check(it.size == 15) { "the wheel palette must hold exactly 15 entries, found ${it.size}" }
}

private fun channelLuminance(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/** Relative luminance of an ARGB colour, per WCAG. */
fun relativeLuminance(argb: Int): Double {
    val r = channelLuminance((argb shr 16) and 0xFF)
    val g = channelLuminance((argb shr 8) and 0xFF)
    val b = channelLuminance(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * WCAG contrast ratio between two ARGB colours. White hanzi is large bold display type, for
 * which 3:1 is the legibility floor; the palette aims well above it.
 */
fun contrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
    val lighter = max(relativeLuminance(foregroundArgb), relativeLuminance(backgroundArgb))
    val darker = min(relativeLuminance(foregroundArgb), relativeLuminance(backgroundArgb))
    return (lighter + 0.05) / (darker + 0.05)
}

/** Minimum contrast the wheel guarantees for white hanzi on any entry. */
const val WHEEL_MIN_CONTRAST = 3.0

/** White hanzi, as ARGB. */
const val HANZI_WHITE_ARGB = 0xFFFFFFFF.toInt()
