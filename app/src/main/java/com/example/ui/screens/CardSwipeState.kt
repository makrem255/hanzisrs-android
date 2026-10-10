package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How far, in **density-independent pixels**, a card has to travel before a drag counts as a
 * grade.
 *
 * Roughly a fifth of a phone's width. It used to be `250f` fed straight into the pixel-based
 * `Modifier.offset`, which made the same gesture mean different things on different screens:
 * 250px is about 83dp on a 3x phone - the intended distance - but about 167dp on a 1.5x one,
 * where reaching it meant dragging the card most of the way across the display. Expressing it
 * in dp and converting once is what makes the threshold the same physical distance everywhere.
 */
internal const val SWIPE_COMMIT_THRESHOLD_DP = 84f

/**
 * The smallest displacement, in **pixels**, a drag must cover before velocity is allowed to
 * commit it.
 *
 * Without this a scroll that picks up a little horizontal drift, or a mis-touch that flicked
 * sideways for a few pixels, would be graded on velocity alone. It sits above the touch slop
 * that already gates the gesture, so it never rejects a genuine flick - only movement too small
 * to have been meant as one.
 */
internal const val SWIPE_MIN_DISPLACEMENT_PX = 40f

/**
 * How fast, in **pixels per second**, a horizontal fling has to be to commit without covering
 * the full threshold.
 *
 * A quick flick across a phone screen covers somewhere between 800 and 3000 px/s, and by the
 * time this is read the gesture is already known to be horizontal. Below it the drag is a
 * deliberate placement and distance decides instead.
 */
internal const val SWIPE_FLING_VELOCITY_PX_PER_SEC = 1200f

/** Maximum tilt, in degrees, the card may reach while travelling. */
private const val MAX_SWIPE_TILT_DEGREES = 24f

/** Pixels of sideways travel per degree of tilt while dragging. */
private const val TILT_DIVISOR_PX = 40f

/**
 * What a finished drag decided to do.
 *
 * Separate from the grade it produces so the decision - which is the part worth testing - never
 * has to touch the spaced-repetition types, and so "return to centre" is expressible without
 * pretending a grade exists.
 */
enum class SwipeOutcome {
    /** Below threshold and no flick: the card goes back where it started and nothing is written. */
    ReturnToCentre,

    /** Travelled or flung right, which the deck reads as [com.example.data.srs.SrsRating.GOOD]. */
    CommitRight,

    /** Travelled or flung left, which the deck reads as [com.example.data.srs.SrsRating.HARD]. */
    CommitLeft,
}

/**
 * Decides whether a completed drag grades the card, returns it, or which way it committed.
 *
 * Deliberately a free function over primitives: this is the whole swipe policy, so it can be
 * exercised directly instead of through a gesture, an animation and a database.
 *
 * The three rules, in order:
 *
 *  1. **The card has to be answerable.** [canCommit] is
 *     [com.example.data.srs.ReviewDeckState.canRate] - the same predicate `beginRating()`
 *     refuses on. Reading it here rather than re-deriving "is it revealed?" inside the gesture
 *     keeps the swipe and the four buttons agreeing about when a grade may be written; a swipe
 *     used to grade a card the learner had never uncovered.
 *  2. **A flick wins.** A fast fling commits in the direction of travel even if it fell short of
 *     [thresholdPx], which is what makes a quick swipe and a slow one feel like the same gesture.
 *     A flick that reverses direction - dragged right, thrown back left - is a cancellation and
 *     returns, even if it had passed the threshold on the way out.
 *  3. **Otherwise distance decides**, against the density-independent threshold.
 *
 * Velocity and offset must agree for a flick to commit: a card parked past the threshold and
 * then flicked the other way is the learner changing their mind, not grading it.
 *
 * @param offsetPx final horizontal displacement, in pixels
 * @param velocityPxPerSec horizontal velocity at release, in pixels per second
 * @param canCommit whether the deck would accept a grade for this card right now
 * @param thresholdPx distance that commits on its own, in pixels
 * @param minDisplacementPx displacement below which velocity is ignored, in pixels
 * @param flingVelocityPxPerSec velocity that commits on its own, in pixels per second
 */
fun decideSwipe(
    offsetPx: Float,
    velocityPxPerSec: Float,
    canCommit: Boolean,
    thresholdPx: Float,
    minDisplacementPx: Float = SWIPE_MIN_DISPLACEMENT_PX,
    flingVelocityPxPerSec: Float = SWIPE_FLING_VELOCITY_PX_PER_SEC,
): SwipeOutcome {
    if (!canCommit) return SwipeOutcome.ReturnToCentre
    if (abs(offsetPx) < minDisplacementPx) return SwipeOutcome.ReturnToCentre

    if (abs(velocityPxPerSec) >= flingVelocityPxPerSec) {
        return when {
            // Direction of the throw, not of the drag. They disagree when the learner started to
            // swipe and changed their mind, and that gesture is a cancellation.
            velocityPxPerSec * offsetPx <= 0f -> SwipeOutcome.ReturnToCentre
            offsetPx > 0f -> SwipeOutcome.CommitRight
            else -> SwipeOutcome.CommitLeft
        }
    }

    return when {
        offsetPx >= thresholdPx -> SwipeOutcome.CommitRight
        offsetPx <= -thresholdPx -> SwipeOutcome.CommitLeft
        else -> SwipeOutcome.ReturnToCentre
    }
}

/**
 * Converts the density-independent commit threshold into the pixels [decideSwipe] compares with.
 *
 * One conversion point, so the dp constant above and the pixel arithmetic in the gesture can
 * never drift apart.
 */
fun swipeThresholdPx(density: Float): Float = SWIPE_COMMIT_THRESHOLD_DP * density

/** The tilt the card should show for [offsetPx], clamped so a fly-out does not spin it. */
fun swipeTiltDegrees(offsetPx: Float): Float =
    (offsetPx / TILT_DIVISOR_PX).coerceIn(-MAX_SWIPE_TILT_DEGREES, MAX_SWIPE_TILT_DEGREES)

/**
 * Where a committed card is thrown to, in pixels: clear of the display it is leaving.
 *
 * Was a flat `900f` - about five sixths of a 1080px screen but barely half of a 1440px one, so
 * on the denser displays a card finished its fly-out still partly visible, parked at the edge as
 * though the gesture had stalled. Scaling to the display guarantees it leaves.
 */
fun flyOutDistancePx(screenWidthPx: Float): Float = screenWidthPx * 1.15f

/**
 * Minimum gap between two accepted tap-to-advance taps, in milliseconds.
 *
 * One tap advances one card; without a floor, a double-tap's second tap lands on the *next*
 * card and advances again before the learner has seen it. 350ms is above a deliberate
 * double-tap interval and below anything that makes single taps feel gated.
 */
const val TAP_ADVANCE_WINDOW_MS = 350L

/**
 * Whether a tap at [nowMs] may advance the deck given the last accepted tap at [lastTapMs].
 *
 * Pure so the guard is unit-tested rather than trusted: time passes as arguments. A tap is
 * accepted when no tap has ever been accepted ([lastTapMs] still its initial
 * [Long.MIN_VALUE]) or when the window has elapsed since the last one. The never-tapped
 * case is explicit rather than derived from the subtraction, because `nowMs -
 * Long.MIN_VALUE` overflows to a negative and would refuse the very first tap.
 */
fun shouldAcceptTap(lastTapMs: Long, nowMs: Long, windowMs: Long = TAP_ADVANCE_WINDOW_MS): Boolean =
    lastTapMs == Long.MIN_VALUE || nowMs - lastTapMs >= windowMs

/**
 * Whether a tap has anywhere to go: anywhere before the final card.
 *
 * Tapping the last card is a no-op, not an advance - and a no-op must not vibrate, or the
 * learner is told something happened when nothing did. The deck is empty or finished when
 * there is no index below the end.
 */
fun canTapAdvance(index: Int, size: Int): Boolean = size > 0 && index < size - 1

/** Where the top card is in its lifecycle. */
enum class SwipePhase {
    /** At rest, or after a settle has finished. Drags are accepted. */
    Idle,

    /** Under the learner's finger. */
    Dragging,

    /** Animating back to centre after a gesture that did not commit. Drags are accepted. */
    Returning,

    /** A grade was accepted and the card is on its way off-screen. Drags are refused. */
    Committed,
}

/**
 * The single owner of the top card's horizontal offset.
 *
 * ## Why this exists
 *
 * The offset used to be a bare `Float` written by two places that knew nothing about each other:
 * the drag handler, and a fling launched on its own coroutine in `onDragEnd`. Nothing ever
 * cancelled that fling. It produced three defects, all intermittent because they depended on
 * whether a local database write landed before or after a ~300ms spring:
 *
 *  - **The next card was stranded off-screen.** A rating advances the deck, and the per-card
 *    reset zeroed the offset - but the still-running fling wrote its interpolated value over that
 *    reset on every subsequent frame, then stopped. The new card was left wherever the old card's
 *    fly-out ended, usually invisible.
 *  - **Words were skipped.** That same leftover offset was the starting point for the next drag,
 *    so a fresh gesture began several hundred pixels out and cleared the threshold on release
 *    without the learner having dragged it. Another grade, another advance, another card gone.
 *  - **A cancelled gesture froze the card.** There was no `onDragCancel`, and the gesture
 *    detector was keyed on the reveal flag, so revealing restarted it and silently abandoned any
 *    drag in progress - leaving the card exactly where it was.
 *
 * Those are one bug in three costumes: the offset had several writers and no arbitration. This
 * class is the arbitration. At most one settle coroutine exists at a time, it is cancelled before
 * anything else touches the offset, and [onDeckStateChanged] is the only thing that reacts to the
 * deck moving on.
 *
 * ## Why the offset is no longer `rememberSaveable`
 *
 * It was saveable so a rotation mid-drag would not lose the learner's place. What it actually
 * restored on a rotation that landed mid-*fly-out* was a card parked hundreds of pixels off
 * screen with nothing left to bring it back - precisely the stranded state above. The deck index
 * lives in the view model and survives rotation on its own, and the first [onDeckStateChanged]
 * after a recreation re-centres, so saving the offset bought nothing and could only reintroduce
 * the bug.
 */
class CardSwipeState internal constructor(
    private val scope: CoroutineScope,
    initialIndex: Int,
) {
    /** Horizontal displacement of the card, in pixels. Written only by this class. */
    var offsetX by mutableFloatStateOf(0f)
        private set

    /** What the card is currently doing. */
    var phase by mutableStateOf(SwipePhase.Idle)
        private set

    /** The deck index this state believes is on screen. Not observable: nothing composes from it. */
    internal var cardIndex: Int = initialIndex
        private set

    /** The settle currently in flight, if any. There is never more than one. */
    private var settleJob: Job? = null

    /**
     * Applies a drag delta.
     *
     * Refused outright while [SwipePhase.Committed]: that card is leaving, and letting it be
     * grabbed would either drag a graded card back over the deck or start the next gesture from
     * an offset belonging to the previous card - which is how a swipe came to clear two words
     * without the learner touching the second one.
     */
    fun dragBy(delta: Float) {
        if (phase == SwipePhase.Committed) return
        cancelSettle()
        offsetX += delta
        phase = SwipePhase.Dragging
    }

    /** Springs back to centre after a gesture that did not commit, or after a refused grade. */
    fun springBack() {
        if (offsetX == 0f) {
            cancelSettle()
            phase = SwipePhase.Idle
            return
        }
        phase = SwipePhase.Returning
        animateTo(0f, then = SwipePhase.Idle)
    }

    /**
     * Throws the card off-screen. The phase stays [SwipePhase.Committed] once it lands so the
     * card cannot be grabbed while the grade is being written; [onDeckStateChanged] releases it.
     */
    fun flingOut(targetPx: Float) {
        phase = SwipePhase.Committed
        animateTo(targetPx, then = SwipePhase.Committed)
    }

    /**
     * Snaps to centre with no animation, cancelling anything in flight.
     *
     * Used when the card underneath has already changed: there is nothing to animate, because
     * whatever was flying out is no longer what is on screen. The cancel comes *first*, which is
     * the whole point - resetting the offset while the old fling still runs just gets overwritten.
     */
    fun recentre() {
        cancelSettle()
        offsetX = 0f
        phase = SwipePhase.Idle
    }

    /**
     * Applies one deck-state change to the offset. The only bridge between the two.
     *
     * Two cases, and between them they cover everything the old code missed:
     *
     *  - **The index moved.** A different card is on screen, so whatever the previous one was
     *    doing is cancelled and the new one starts at rest.
     *  - **The index did not move, the write is no longer in flight, and we had committed.** The
     *    grade was refused, so the deck never advanced - and nothing else will ever recentre this
     *    card. It is parked off-screen with no card behind it. Spring it back. The per-card reset
     *    is keyed on the index, so this was the one path with no reset at all.
     *
     * Every other combination - mid-drag, mid-write, at rest - is a no-op, which is why this can
     * be wired to the whole deck state instead of to a set of hand-picked transitions.
     */
    fun onDeckStateChanged(index: Int, isRating: Boolean) {
        if (index != cardIndex) {
            cardIndex = index
            recentre()
        } else if (!isRating && phase == SwipePhase.Committed) {
            springBack()
        }
    }

    private fun animateTo(targetPx: Float, then: SwipePhase) {
        cancelSettle()
        val start = offsetX
        settleJob = scope.launch {
            Animatable(start).animateTo(targetPx, spring()) { offsetX = value }
            // Reached only if the animation was not cancelled, so a cancelled settle can never
            // write a phase belonging to the card that cancelled it.
            phase = then
        }
    }

    private fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
    }
}

/**
 * Remembers a [CardSwipeState] bound to this composition's lifetime.
 *
 * The scope is a key as well as a dependency: when the composition goes away its scope is
 * cancelled, and any fling still running goes with it rather than outliving the screen.
 */
@Composable
fun rememberCardSwipeState(initialIndex: Int = 0): CardSwipeState {
    val scope = rememberCoroutineScope()
    return remember(scope) { CardSwipeState(scope, initialIndex) }
}
