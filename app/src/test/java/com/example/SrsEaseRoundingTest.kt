package com.example

import com.example.data.model.SrsStateEntity
import com.example.data.srs.SrsAlgorithm
import com.example.data.srs.SrsRating
import org.junit.Assert.assertEquals
import org.junit.Test
/**
 * Whether the ease factor is rounded or truncated, and whether it drifts.
 *
 * The line under test is `(newEase * 100.0).toInt() / 100.0`. `Double.toInt()` truncates toward
 * zero, so any product that lands a hair below the intended value — which binary floating point
 * makes routine, not exotic — loses up to 0.01 of ease. Ease is the one scheduler input that
 * compounds: it multiplies every later interval, so a 0.01 loss made on the first AGAIN is
 * still there after a hundred reviews.
 *
 * This was worth a test rather than a reading of the spec, because "2.5 - 0.20 is not 2.3" is
 * a claim about a particular pair of doubles and not about the code.
 */
class SrsEaseRoundingTest {

    private fun stateAt(ease: Double) = SrsStateEntity(
        userId = 1L,
        userVocabularyId = 1L,
        vocabularyId = 1L,
        intervalDays = 0,
        repetitions = 0,
        easeFactor = ease,
        dueDateMillis = 0L,
        state = "LEARNING"
    )

    @Test
    fun `an AGAIN from a fresh card lands on 2_30, not 2_29`() {
        val result = SrsAlgorithm.calculateNextReview(
            currentReview = stateAt(2.5),
            rating = SrsRating.AGAIN,
            now = 0L
        )

        assertEquals(
            "2.5 - 0.20 is 2.30; truncating the product stored 2.29 and every later interval " +
                "inherited the loss",
            2.30,
            result.easeFactor,
            1e-9
        )
    }

    @Test
    fun `ease never lands a rounding step below the value it was computed from`() {
        // Walk the sequence a learner actually produces: each AGAIN takes exactly 0.20 off,
        // and the band floor at 1.30 stops it going further. Every step is compared against
        // the exact decimal arithmetic it is meant to be storing, so a truncation is caught
        // wherever it happens rather than only at the one pair of doubles that made it
        // visible. Before the fix this read 2.29, 2.09, 1.89, 1.69, 1.49, 1.30...
        //
        // The list is the *results*, not the starting value: the loop rates a card and then
        // checks what came out, so it begins with what the first AGAIN from 2.50 produces. An
        // earlier version of this list led with 2.50 and reported a clean-looking mismatch
        // cascade — "intended 2.5 but stored 2.3" on every step — which reads exactly like the
        // truncation bug it was written to catch, and was in fact the test being one step out.
        val expectedResults = listOf(2.30, 2.10, 1.90, 1.70, 1.50, 1.30, 1.30, 1.30)
        var ease = 2.50
        val mismatches = mutableListOf<String>()

        expectedResults.forEach { expected ->
            ease = SrsAlgorithm.calculateNextReview(
                currentReview = stateAt(ease),
                rating = SrsRating.AGAIN,
                now = 0L
            ).easeFactor
            if (kotlin.math.abs(ease - expected) > 1e-9) {
                mismatches += "expected $expected but got $ease"
            }
        }

        assertEquals(
            "ease drifted from the decimal values it is computed from: " + mismatches.joinToString("; "),
            emptyList<String>(),
            mismatches
        )
    }

    @Test
    fun `ease stays inside the declared band after a long run of failures`() {
        var ease = 2.50
        repeat(60) {
            ease = SrsAlgorithm.calculateNextReview(
                currentReview = stateAt(ease),
                rating = SrsRating.AGAIN,
                now = 0L
            ).easeFactor
        }

        assertEquals("the floor holds however many times a card is failed", 1.3, ease, 1e-9)
    }
}
