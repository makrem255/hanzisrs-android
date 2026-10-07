package com.example

import com.example.ui.screens.CardSwipeState
import com.example.ui.screens.SwipePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-writer guarantees that the swipe fix exists to provide.
 *
 * ## Why these are the tests that matter
 *
 * The original deck had three intermittent defects that all came from one cause: the card's
 * horizontal offset had several writers and nothing arbitrated between them. A detached fling
 * coroutine kept writing after the card underneath had changed, so the next card was stranded
 * off-screen; the leftover offset then cleared the commit threshold on the *next* gesture
 * without anyone dragging it, so words were skipped; and a cancelled gesture left the card
 * exactly where it stopped.
 *
 * All three are invisible in a screenshot and all three are races against an animation. So they
 * are tested at the level where they actually live: one state object, one scope, and the
 * ordering between "something wrote the offset" and "the coroutine that was going to write it".
 *
 * ## Why no test here lets an animation run
 *
 * `Animatable.animateTo` needs a `MonotonicFrameClock`, which a plain JVM test does not have and
 * does not want one - faking frames would be testing the fake. Every coroutine in these tests is
 * cancelled *before* the scheduler is allowed to run it, which is exactly the property being
 * asserted: a settle that has been superseded must never execute at all, rather than being
 * executed and then corrected.
 */
class CardSwipeStateTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher)

    private fun state(initialIndex: Int = 0) = CardSwipeState(scope, initialIndex)

    @After
    fun tearDown() {
        // Anything left queued is dropped rather than run: running it would enter an animation
        // with no frame clock, and there is nothing left to assert by then anyway.
        scope.cancel()
    }

    // ---- one writer at a time ------------------------------------------------------------

    @Test
    fun `a drag moves the card and marks it as being held`() {
        val card = state()

        card.dragBy(140f)

        assertEquals(140f, card.offsetX, 0.001f)
        assertEquals(SwipePhase.Dragging, card.phase)
    }

    @Test
    fun `drag accumulates rather than replacing`() {
        val card = state()

        card.dragBy(100f)
        card.dragBy(-30f)

        assertEquals(70f, card.offsetX, 0.001f)
    }

    @Test
    fun `a committed card cannot be grabbed`() {
        val card = state()
        card.dragBy(300f)
        card.flingOut(targetPx = 3000f)

        card.dragBy(250f)

        assertEquals(
            "dragging a card that is already leaving is how one swipe consumed two words",
            300f,
            card.offsetX,
            0.001f,
        )
        assertEquals(SwipePhase.Committed, card.phase)
    }

    // ---- the stranded-card regression ----------------------------------------------------

    @Test
    fun `a deck change cancels an in-flight fly-out that would otherwise write over it`() {
        val card = state(initialIndex = 0)

        card.dragBy(320f)
        card.flingOut(targetPx = 3000f) // queued, not yet running
        assertEquals(SwipePhase.Committed, card.phase)

        // The grade landed and the deck advanced - the exact moment the old code was overrun.
        card.onDeckStateChanged(index = 1, isRating = false)

        // Let the scheduler do whatever it still had pending. The superseded fly-out must not
        // run: if it did, it would write its interpolation over the reset and strand the new
        // card off-screen.
        dispatcher.scheduler.runCurrent()

        assertEquals(
            "the new card must start where the learner can see it",
            0f,
            card.offsetX,
            0.001f,
        )
        assertEquals(SwipePhase.Idle, card.phase)
        assertEquals(1, card.cardIndex)
    }

    @Test
    fun `a fly-out that was cancelled can never resume later`() {
        val card = state()

        card.dragBy(200f)
        card.flingOut(targetPx = 2500f)
        card.onDeckStateChanged(index = 1, isRating = false)
        dispatcher.scheduler.runCurrent()

        // A second settle from a later gesture, then one more scheduler pass.
        card.dragBy(50f)
        dispatcher.scheduler.runCurrent()

        assertEquals(50f, card.offsetX, 0.001f)
        assertEquals(SwipePhase.Dragging, card.phase)
    }

    // ---- a partially-completed grade is not left parked off-screen ------------------------

    @Test
    fun `a refused grade springs the card back instead of stranding it`() {
        val card = state()
        card.dragBy(400f)
        card.flingOut(targetPx = 3000f)

        // The write came back and the deck did NOT advance: the grade was refused. This is the
        // one path that used to have no reset at all, leaving a card parked in mid-air with no
        // card behind it.
        card.onDeckStateChanged(index = 0, isRating = false)

        assertEquals(
            "a refused grade must begin the return, not leave the card where it stopped",
            SwipePhase.Returning,
            card.phase,
        )
    }

    @Test
    fun `a grade still being written holds the card where the fly-out put it`() {
        val card = state()
        card.dragBy(400f)
        card.flingOut(targetPx = 3000f)

        card.onDeckStateChanged(index = 0, isRating = true)

        assertEquals(
            "springing back mid-write would visibly snap the card while the grade is pending",
            SwipePhase.Committed,
            card.phase,
        )
    }

    // ---- a new gesture takes over from an abandoned one -----------------------------------

    @Test
    fun `a drag that interrupts a spring-back is not overwritten by it`() {
        val card = state()
        card.dragBy(120f)
        card.springBack() // queued
        card.dragBy(30f) // takes over, cancels the spring

        dispatcher.scheduler.runCurrent()

        assertEquals(
            "the abandoned spring must not write its interpolation over the learner's drag",
            150f,
            card.offsetX,
            0.001f,
        )
        assertEquals(SwipePhase.Dragging, card.phase)
    }

    @Test
    fun `springing back from rest is immediate and schedules nothing`() {
        val card = state()

        card.springBack()
        dispatcher.scheduler.runCurrent()

        assertEquals(0f, card.offsetX, 0.001f)
        assertEquals(SwipePhase.Idle, card.phase)
    }

    @Test
    fun `an index change recentres even mid-drag`() {
        val card = state(initialIndex = 3)
        card.dragBy(-500f)

        card.onDeckStateChanged(index = 4, isRating = false)
        dispatcher.scheduler.runCurrent()

        assertEquals(0f, card.offsetX, 0.001f)
        assertEquals(SwipePhase.Idle, card.phase)
        assertEquals(4, card.cardIndex)
    }

    @Test
    fun `an unchanged deck during a write is not mistaken for a change`() {
        val card = state(initialIndex = 2)
        card.dragBy(400f)
        card.flingOut(targetPx = 3000f)

        card.onDeckStateChanged(index = 2, isRating = true)
        card.onDeckStateChanged(index = 2, isRating = true)

        assertEquals(
            "a repeated state emission while the write is in flight must not reset anything",
            SwipePhase.Committed,
            card.phase,
        )
        assertTrue(card.offsetX != 0f)
    }
}
