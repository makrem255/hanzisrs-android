package com.example

import com.example.data.model.SrsReviewEntity
import com.example.data.srs.SrsAlgorithm
import com.example.data.srs.SrsRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrsAlgorithmTest {
  @Test
  fun `again returns a card to a short learning interval`() {
    val now = 1_000_000L

    val result = SrsAlgorithm.calculateNextReview(
      currentReview = SrsReviewEntity(wordId = 1, userId = 1, intervalDays = 3, repetitions = 2),
      rating = SrsRating.AGAIN,
      now = now,
    )

    assertEquals(0, result.intervalDays)
    assertEquals("10 min", result.nextReviewLabel)
    assertEquals(now + 600_000L, result.dueDateMillis)
    assertEquals("LEARNING", result.state)
  }

  @Test
  fun `good answers grow review intervals predictably`() {
    val result = SrsAlgorithm.calculateNextReview(
      currentReview = SrsReviewEntity(wordId = 1, userId = 1, intervalDays = 3, repetitions = 2, easeFactor = 2.5),
      rating = SrsRating.GOOD,
      now = 0L,
    )

    assertEquals(7, result.intervalDays)
    assertEquals("7 days", result.nextReviewLabel)
    assertTrue(result.dueDateMillis > 0L)
    assertEquals("REVIEW", result.state)
  }
}
