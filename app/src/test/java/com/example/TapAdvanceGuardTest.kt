package com.example

import com.example.ui.screens.TAP_ADVANCE_WINDOW_MS
import com.example.ui.screens.canTapAdvance
import com.example.ui.screens.shouldAcceptTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap-to-advance on the review deck, as pure rules.
 *
 * The tap itself is detected by the framework's `clickable` sitting beside the drag
 * detector - a drag past the touch slop cancels the tap, a tap never reaches the slop,
 * so the two cannot both fire for one gesture, and that classification is Compose's,
 * not something a JVM test could honestly reproduce. What *is* ours, and what is
 * tested here, is everything around it: the double-tap floor and the question of
 * whether a tap has anywhere to go. The advancement itself reuses `goToNextCard`,
 * whose clamping, write-in-flight refusal and empty-deck behaviour are already
 * pinned by `ReviewDeckStateTest`.
 */
class TapAdvanceGuardTest {

    // ---- the double-tap floor ------------------------------------------------------------

    @Test
    fun `the first tap is always accepted`() {
        assertTrue(shouldAcceptTap(Long.MIN_VALUE, 1_000L))
        assertTrue(
            "a tap long after any previous tap is a first tap in every way that matters",
            shouldAcceptTap(lastTapMs = 1_000L, nowMs = 1_000L + 60_000L)
        )
    }

    @Test
    fun `a second tap inside the window is refused`() {
        assertFalse(shouldAcceptTap(lastTapMs = 1_000L, nowMs = 1_000L + TAP_ADVANCE_WINDOW_MS - 1))
    }

    @Test
    fun `a tap exactly on the window edge is accepted`() {
        assertTrue(shouldAcceptTap(lastTapMs = 1_000L, nowMs = 1_000L + TAP_ADVANCE_WINDOW_MS))
    }

    @Test
    fun `a tap after the window is accepted`() {
        assertTrue(shouldAcceptTap(lastTapMs = 1_000L, nowMs = 1_000L + 5_000L))
    }

    @Test
    fun `the window is a third of a second`() {
        assertEquals(
            "above a deliberate double-tap, below anything that feels gated",
            350L,
            TAP_ADVANCE_WINDOW_MS
        )
    }

    // ---- whether a tap has anywhere to go --------------------------------------------------

    @Test
    fun `any card before the last advances`() {
        assertTrue(canTapAdvance(index = 0, size = 5))
        assertTrue(canTapAdvance(index = 3, size = 5))
    }

    @Test
    fun `the last card does not advance`() {
        assertFalse(
            "vibrating for a tap that moved nothing would report a phantom advance",
            canTapAdvance(index = 4, size = 5)
        )
    }

    @Test
    fun `an empty deck does not advance`() {
        assertFalse(canTapAdvance(index = 0, size = 0))
    }
}
