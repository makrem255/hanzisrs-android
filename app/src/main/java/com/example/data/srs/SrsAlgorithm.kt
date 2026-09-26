package com.example.data.srs

import com.example.data.model.SrsReviewEntity
import kotlin.math.max
import kotlin.math.min

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

    /**
     * Upper bound on the ease factor. Without this, every EASY rating adds 0.15 and
     * the interval multiplier grows without limit, so a learner who always taps
     * "Easy" is pushed into intervals measured in years. SM-2 keeps ease in a band.
     */
    private const val MAX_EASE_FACTOR = 2.5
    private const val MAX_INTERVAL_DAYS = 365 * 5
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
                newEase = min(MAX_EASE_FACTOR, currentEase + 0.15)
                newState = if (newInterval >= MASTERED_INTERVAL_THRESHOLD) "MASTERED" else "REVIEW"
            }
        }

        // Cap the scheduled interval so a long streak of easy ratings cannot push a
        // card years into the future.
        val scheduledInterval = min(newInterval, MAX_INTERVAL_DAYS)

        // Human-readable interval label, rounded and correctly pluralised
        // (e.g. 45 days -> "1.5 months", 365 days -> "1 year").
        val label = when {
            rating == SrsRating.AGAIN -> "10 min"
            scheduledInterval == 1 -> "1 day"
            scheduledInterval < 30 -> "$scheduledInterval days"
            scheduledInterval < 365 -> formatMonths(scheduledInterval)
            else -> formatYears(scheduledInterval)
        }

        val dueDateMillis = if (rating == SrsRating.AGAIN) {
            now + AGAIN_DELAY_MILLIS
        } else {
            now + (scheduledInterval * ONE_DAY_MILLIS)
        }

        return SrsCalculationResult(
            intervalDays = scheduledInterval,
            repetitions = newRepetitions,
            easeFactor = (newEase * 100.0).toInt() / 100.0,
            dueDateMillis = dueDateMillis,
            state = newState,
            nextReviewLabel = label
        )
    }

    /** Formats a day count under a year as months, using integer maths to avoid float formatting artefacts. */
    private fun formatMonths(days: Int): String {
        val tenths = (days * 10 + 15) / 30
        return if (tenths < 20) {
            val whole = tenths / 10
            val frac = tenths % 10
            if (frac == 0) "$whole month" else "$whole.$frac months"
        } else {
            "${tenths / 10} months"
        }
    }

    private fun formatYears(days: Int): String {
        val years = days / 365
        return if (years == 1) "1 year" else "$years years"
    }
}
