package com.example

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.screens.ratingLiftFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Where the review screen's four rating buttons sit.
 *
 * They used to sit on the very bottom of the screen, hard against the navigation
 * bar, which is the furthest reach on the phone and the least comfortable place
 * for the one gesture a sitting is made of. They are now lifted into the lower
 * quarter of the screen - by a fraction of the height available, so the lift
 * adapts to the device instead of being a number that was right on one phone.
 *
 * These are the cases that decide whether that is true: a short viewport has no
 * room to give away, a tall one must not leave a band of dead space, and a small
 * phone must still get some.
 */
class RatingLiftTest {

    @Test
    fun `a short viewport leaves the row where it was`() {
        // Landscape, or a small phone: there is no room to give the card any of it,
        // and a fixed lift would push the buttons off the screen.
        assertEquals(0.dp, ratingLiftFor(maxHeight = 400.dp, shortViewport = true))
    }

    @Test
    fun `a normal screen lifts the row by a fraction of its height`() {
        val lift = ratingLiftFor(maxHeight = 700.dp, shortViewport = false)
        assertClose(105.dp, lift)
        assertTrue(
            "the lift must be a fraction of the height, not a fixed number",
            lift < 700.dp / 3
        )
    }

    @Test
    fun `a very tall screen does not leave a band of dead space`() {
        // A tablet, or a large phone in portrait: a fifth of the height would be a
        // lot of nothing under the buttons.
        val lift = ratingLiftFor(maxHeight = 1400.dp, shortViewport = false)
        assertEquals(110.dp, lift)
    }

    @Test
    fun `a small phone still gets some lift`() {
        val lift = ratingLiftFor(maxHeight = 560.dp, shortViewport = false)
        assertClose(84.dp, lift)
        assertTrue("the row must move on a small phone too", lift > 0.dp)
    }

    @Test
    fun `the lift never exceeds the height it is taken from`() {
        val heights = listOf<Dp>(300.dp, 480.dp, 640.dp, 800.dp, 1200.dp)
        for (height in heights) {
            val lift = ratingLiftFor(height, shortViewport = false)
            assertTrue("a lift of $lift cannot come out of a height of $height", lift < height)
        }
    }

    /**
     * The lift is a fraction of a height, and a fraction of a `Dp` is not exact in
     * binary floating point: a fifteenth of 700 is 105.00001. The rule is the
     * fraction, not the number, so the assertion carries a tolerance.
     */
    private fun assertClose(expected: Dp, actual: Dp, tolerance: Dp = 0.5.dp) {
        assertTrue(
            "expected $expected but was $actual",
            abs(actual.value - expected.value) <= tolerance.value
        )
    }
}
