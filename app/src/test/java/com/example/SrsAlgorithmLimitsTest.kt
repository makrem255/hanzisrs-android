package com.example

import com.example.data.model.SrsStateEntity
import com.example.data.srs.SrsAlgorithm
import com.example.data.srs.SrsRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the ease-factor and interval bounds, and for the
 * human-readable interval label.
 */
class SrsAlgorithmLimitsTest {

    @Test
    fun `ease factor does not grow without bound when every answer is easy`() {
        var review = SrsStateEntity(userVocabularyId = 1, vocabularyId = 1, userId = 1)

        repeat(10) {
            val result = SrsAlgorithm.calculateNextReview(review, SrsRating.EASY, now = 0L)
            assertTrue(
                "ease factor escaped its upper bound: ${result.easeFactor}",
                result.easeFactor <= 2.5
            )
            review = review.copy(
                intervalDays = result.intervalDays,
                repetitions = result.repetitions,
                easeFactor = result.easeFactor
            )
        }
    }

    @Test
    fun `ease factor never drops below the floor`() {
        var review = SrsStateEntity(userVocabularyId = 1, vocabularyId = 1, userId = 1, intervalDays = 10, repetitions = 5, easeFactor = 1.3)

        repeat(5) {
            val result = SrsAlgorithm.calculateNextReview(review, SrsRating.AGAIN, now = 0L)
            assertTrue("ease factor fell below 1.3: ${result.easeFactor}", result.easeFactor >= 1.3)
            review = review.copy(easeFactor = result.easeFactor)
        }
    }

    @Test
    fun `scheduled interval is capped so cards cannot drift years into the future`() {
        var review = SrsStateEntity(userVocabularyId = 1, vocabularyId = 1, userId = 1)

        repeat(12) {
            val result = SrsAlgorithm.calculateNextReview(review, SrsRating.EASY, now = 0L)
            assertTrue(
                "interval escaped its cap: ${result.intervalDays} days",
                result.intervalDays <= 1825
            )
            review = review.copy(
                intervalDays = result.intervalDays,
                repetitions = result.repetitions,
                easeFactor = result.easeFactor
            )
        }

        assertEquals(1825, review.intervalDays)
        assertEquals("5 years", SrsAlgorithm.calculateNextReview(review, SrsRating.EASY, now = 0L).nextReviewLabel)
    }

    @Test
    fun `interval label uses the singular for exactly one month`() {
        val result = SrsAlgorithm.calculateNextReview(
            currentReview = SrsStateEntity(userVocabularyId = 1, vocabularyId = 1, userId = 1, intervalDays = 25, repetitions = 3, easeFactor = 2.5),
            rating = SrsRating.HARD,
            now = 0L
        )

        // 25 days * 1.2 -> 30 days, which must read "1 month", not "1 months".
        assertEquals(30, result.intervalDays)
        assertEquals("1 month", result.nextReviewLabel)
    }

    @Test
    fun `interval label uses the singular for exactly one year`() {
        val result = SrsAlgorithm.calculateNextReview(
            currentReview = SrsStateEntity(userVocabularyId = 1, vocabularyId = 1, userId = 1, intervalDays = 300, repetitions = 8, easeFactor = 2.5),
            rating = SrsRating.GOOD,
            now = 0L
        )

        // max(300 + 1, 300 * 2.5) = 750 days, which must read "2 years".
        assertEquals(750, result.intervalDays)
        assertEquals("2 years", result.nextReviewLabel)
    }

    @Test
    fun `interval label reports fractional months rather than truncating`() {
        val result = SrsAlgorithm.calculateNextReview(
            currentReview = SrsStateEntity(userVocabularyId = 1, vocabularyId = 1, userId = 1, intervalDays = 40, repetitions = 3, easeFactor = 2.5),
            rating = SrsRating.HARD,
            now = 0L
        )

        // 40 days * 1.2 -> 48 days, roughly 1.6 months.
        assertEquals(48, result.intervalDays)
        assertEquals("1.6 months", result.nextReviewLabel)
    }
}
