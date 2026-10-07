package com.example

import com.example.ui.screens.SWIPE_COMMIT_THRESHOLD_DP
import com.example.ui.screens.SWIPE_FLING_VELOCITY_PX_PER_SEC
import com.example.ui.screens.SWIPE_MIN_DISPLACEMENT_PX
import com.example.ui.screens.SwipeOutcome
import com.example.ui.screens.decideSwipe
import com.example.ui.screens.flyOutDistancePx
import com.example.ui.screens.swipeThresholdPx
import com.example.ui.screens.swipeTiltDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole swipe policy, tested as the free function it is.
 *
 * A card gesture cannot be tested honestly end to end without a device: it needs a pointer, a
 * density, a frame clock and a database write, and a fake of three of those proves nothing about
 * the fourth. So the decision - the part that decides whether a grade gets written, whether a
 * word is skipped, and whether a card is left stranded - lives in [decideSwipe] over plain
 * floats, and that is what is under test here.
 *
 * Every case is written from the failure it prevents:
 *
 *  - a grade written for a card the learner never revealed (`canCommit` false)
 *  - a word skipped because a leftover offset cleared the threshold untouched (the
 *    displacement floor and the reversal rule)
 *  - a card that springs back when the learner clearly meant to answer it (the velocity rule)
 */
class CardSwipeDecisionTest {

    private val threshold = swipeThresholdPx(3f) // 84dp at 3x -> 252px
    private val minDisplacement = SWIPE_MIN_DISPLACEMENT_PX
    private val flingVelocity = SWIPE_FLING_VELOCITY_PX_PER_SEC

    private fun decide(
        offset: Float,
        velocity: Float = 0f,
        canCommit: Boolean = true,
        limit: Float = threshold,
    ) = decideSwipe(
        offsetPx = offset,
        velocityPxPerSec = velocity,
        canCommit = canCommit,
        thresholdPx = limit,
        minDisplacementPx = minDisplacement,
        flingVelocityPxPerSec = flingVelocity,
    )

    // ---- rule 1: the card has to be answerable -------------------------------------------

    @Test
    fun `an unrevealed card never commits no matter how far it travelled`() {
        assertEquals(
            "a swipe grading a card the learner had not uncovered fabricates a review",
            SwipeOutcome.ReturnToCentre,
            decide(offset = threshold * 3f, velocity = 6000f, canCommit = false),
        )
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = -threshold * 3f, velocity = -6000f, canCommit = false),
        )
    }

    // ---- the displacement floor ----------------------------------------------------------

    @Test
    fun `a touch with almost no travel cannot commit on velocity alone`() {
        assertEquals(
            "a mis-touch that twitched sideways must not be graded",
            SwipeOutcome.ReturnToCentre,
            decide(offset = 4f, velocity = 4000f),
        )
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = -4f, velocity = -4000f),
        )
    }

    @Test
    fun `the displacement floor does not reject a genuine short flick`() {
        val justOverFloor = minDisplacement + 1f
        assertEquals(
            SwipeOutcome.CommitRight,
            decide(offset = justOverFloor, velocity = flingVelocity * 2f),
        )
        assertEquals(
            SwipeOutcome.CommitLeft,
            decide(offset = -justOverFloor, velocity = -flingVelocity * 2f),
        )
    }

    // ---- rule 2: a flick wins ------------------------------------------------------------

    @Test
    fun `a fast flick commits even when it fell short of the distance`() {
        assertEquals(
            "a quick swipe and a slow one have to feel like the same gesture",
            SwipeOutcome.CommitRight,
            decide(offset = 60f, velocity = flingVelocity * 1.5f),
        )
        assertEquals(
            SwipeOutcome.CommitLeft,
            decide(offset = -60f, velocity = -flingVelocity * 1.5f),
        )
    }

    @Test
    fun `a throw in the opposite direction to the travel is a cancellation`() {
        // Parked past the threshold on the way out, then thrown back: the learner changed their
        // mind. Committing here would record a grade for a gesture they withdrew.
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = threshold + 40f, velocity = -flingVelocity * 2f),
        )
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = -(threshold + 40f), velocity = flingVelocity * 2f),
        )
    }

    @Test
    fun `velocity exactly at the fling threshold still commits`() {
        assertEquals(
            SwipeOutcome.CommitRight,
            decide(offset = 50f, velocity = flingVelocity),
        )
    }

    // ---- rule 3: otherwise distance decides ----------------------------------------------

    @Test
    fun `a slow drag past the threshold commits`() {
        assertEquals(
            SwipeOutcome.CommitRight,
            decide(offset = threshold, velocity = 100f),
        )
        assertEquals(
            SwipeOutcome.CommitLeft,
            decide(offset = -threshold, velocity = -100f),
        )
    }

    @Test
    fun `a slow drag short of the threshold returns to centre`() {
        assertEquals(
            "the partial swipe has to spring back without recording anything",
            SwipeOutcome.ReturnToCentre,
            decide(offset = threshold - 1f, velocity = 50f),
        )
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = -(threshold - 1f), velocity = -50f),
        )
    }

    @Test
    fun `a released card sitting exactly at the origin does nothing`() {
        assertEquals(SwipeOutcome.ReturnToCentre, decide(offset = 0f, velocity = 0f))
    }

    @Test
    fun `velocity between the floor and the fling threshold is not treated as a flick`() {
        // Just below fling speed: distance alone decides, so a short drag returns.
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = 60f, velocity = flingVelocity - 1f),
        )
        // The same offset past threshold still commits - velocity was never needed.
        assertEquals(
            SwipeOutcome.CommitRight,
            decide(offset = threshold + 1f, velocity = flingVelocity - 1f),
        )
    }

    // ---- density and display independence ------------------------------------------------

    @Test
    fun `the commit distance is the same physical distance on every screen`() {
        assertEquals(84f * 2f, swipeThresholdPx(2f), 0.001f)
        assertEquals(84f * 2.75f, swipeThresholdPx(2.75f), 0.001f)
        assertEquals(
            "the dp constant is the single source of the threshold",
            SWIPE_COMMIT_THRESHOLD_DP,
            swipeThresholdPx(1f),
            0.001f,
        )
    }

    @Test
    fun `the same gesture decides the same way at every density`() {
        // The same physical drag - 84dp plus a hair - at two very different densities, each
        // with the threshold its own density produces, which is exactly how the gesture calls it.
        val lowThreshold = swipeThresholdPx(1.5f)
        val highThreshold = swipeThresholdPx(3.5f)
        val lowDensity = decide(offset = lowThreshold + 2f, limit = lowThreshold)
        val highDensity = decide(offset = highThreshold + 2f, limit = highThreshold)
        assertEquals(SwipeOutcome.CommitRight, lowDensity)
        assertEquals(
            "a threshold expressed in raw pixels makes the same drag mean two things",
            lowDensity,
            highDensity,
        )
        // And the same drag that falls short returns at both, not just one.
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = lowThreshold - 2f, limit = lowThreshold),
        )
        assertEquals(
            SwipeOutcome.ReturnToCentre,
            decide(offset = highThreshold - 2f, limit = highThreshold),
        )
    }

    @Test
    fun `the thrown card always clears the display it is leaving`() {
        for (width in listOf(1080f, 1440f, 2400f)) {
            val flyOut = flyOutDistancePx(width)
            assertTrue(
                "a card thrown less than one screen width can finish visible and parked",
                flyOut > width,
            )
        }
        // Proportional, so a denser phone is not given the same flat throw.
        assertEquals(
            flyOutDistancePx(2000f) / flyOutDistancePx(1000f),
            2f,
            0.0001f,
        )
    }

    @Test
    fun `tilt is clamped so a fly-out does not spin the card`() {
        assertEquals(24f, swipeTiltDegrees(4000f), 0.001f)
        assertEquals(-24f, swipeTiltDegrees(-4000f), 0.001f)
        assertEquals(0f, swipeTiltDegrees(0f), 0.001f)
        // Direction is preserved below the clamp.
        assertTrue(swipeTiltDegrees(120f) > 0f)
        assertTrue(swipeTiltDegrees(-120f) < 0f)
    }
}
