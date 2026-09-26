package com.example.data.srs

import com.example.data.model.SrsReviewEntity
import kotlin.math.max

enum class SrsRating(val value: Int, val label: String, val description: String) {
    AGAIN(1, "Again", "Forgot completely (<1 day)"),
    HARD(2, "Hard", "Difficult recall (~1.2x)"),
    GOOD(3, "Good", "Normal recall (~2.5x)"),
    EASY(4, "Easy", "Mastered effortlessly (>3.5x)")
}

data class SrsCalculationResult(
    val intervalDays: Int,
    val repetitions: Int,
    val easeFactor: Double,
    val dueDateMillis: Long,
    val state: String,
    val nextReviewLabel: String
)

object SrsAlgorithm {
    private const val ONE_DAY_MILLIS = 86_400_000L
    private const val AGAIN_DELAY_MILLIS = 10 * 60_000L
    private const val MIN_EASE_FACTOR = 1.3
    private const val MASTERED_INTERVAL_THRESHOLD = 21

    fun calculateNextReview(
        currentReview: SrsReviewEntity?,
        rating: SrsRating,
        now: Long = System.currentTimeMillis()
    ): SrsCalculationResult {
        val currentRepetitions = currentReview?.repetitions ?: 0
        val currentInterval = currentReview?.intervalDays ?: 0
        val currentEase = currentReview?.easeFactor ?: 2.5

        val newRepetitions: Int
        val newInterval: Int
        var newEase: Double
        val newState: String

        when (rating) {
            SrsRating.AGAIN -> {
                newRepetitions = 0
                newInterval = 0
                newEase = max(MIN_EASE_FACTOR, currentEase - 0.20)
                newState = "LEARNING"
            }
            SrsRating.HARD -> {
                newRepetitions = currentRepetitions + 1
                newInterval = if (currentInterval <= 1) 1 else max(2, (currentInterval * 1.2).toInt())
                newEase = max(MIN_EASE_FACTOR, currentEase - 0.15)
                newState = if (newInterval >= MASTERED_INTERVAL_THRESHOLD) "MASTERED" else "LEARNING"
            }
            SrsRating.GOOD -> {
                newRepetitions = currentRepetitions + 1
                newInterval = when (newRepetitions) {
                    1 -> 1
                    2 -> 3
                    else -> max(currentInterval + 1, (currentInterval * currentEase).toInt())
                }
                newEase = currentEase
                newState = if (newInterval >= MASTERED_INTERVAL_THRESHOLD) "MASTERED" else "REVIEW"
            }
            SrsRating.EASY -> {
                newRepetitions = currentRepetitions + 1
                newInterval = when (newRepetitions) {
                    1 -> 4
                    2 -> 7
                    else -> max(currentInterval + 2, (currentInterval * currentEase * 1.35).toInt())
                }
                newEase = currentEase + 0.15
                newState = if (newInterval >= MASTERED_INTERVAL_THRESHOLD) "MASTERED" else "REVIEW"
            }
        }

        // Format nice human-readable interval label (e.g., "1d", "3d", "1.2mo")
        val label = when {
            rating == SrsRating.AGAIN -> "10 min"
            newInterval == 1 -> "1 day"
            newInterval < 30 -> "$newInterval days"
            newInterval < 365 -> "${newInterval / 30} months"
            else -> "${newInterval / 365} years"
        }

        val dueDateMillis = if (rating == SrsRating.AGAIN) {
            now + AGAIN_DELAY_MILLIS
        } else {
            now + (newInterval * ONE_DAY_MILLIS)
        }

        return SrsCalculationResult(
            intervalDays = newInterval,
            repetitions = newRepetitions,
            easeFactor = (newEase * 100.0).toInt() / 100.0,
            dueDateMillis = dueDateMillis,
            state = newState,
            nextReviewLabel = label
        )
    }
}
