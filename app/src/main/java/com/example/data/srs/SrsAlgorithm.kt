package com.example.data.srs

import com.example.data.model.SrsStateEntity
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

/**
 * The bounds the scheduler is allowed to work within.
 *
 * This is the contract between [SrsAlgorithm] and everything that stores or checks a
 * scheduling result, so it is defined once:
 * [com.example.data.repository.Validator] refuses to write a value outside the ease band,
 * and `SrsAlgorithmBoundsTest` asserts the algorithm never produces one. If the band were
 * duplicated in both places, a later change to one would silently disagree with the other.
 */
object SrsAlgorithmLimits {
    /**
     * Lower bound on the ease factor. SM-2 lets a failed recall drive ease down without limit,
     * after which a card can never be scheduled out of a short interval again.
     */
    const val MIN_EASE_FACTOR = 1.3

    /**
     * Upper bound on the ease factor. Every EASY rating adds 0.15, so without a ceiling a
     * learner who always taps "Easy" grows the interval multiplier without limit and is pushed
     * into intervals measured in years.
     */
    const val MAX_EASE_FACTOR = 2.5

    /** Ceiling on a scheduled interval, so no card lands years into the future. */
    const val MAX_INTERVAL_DAYS = 365 * 5

    /** An interval at or beyond this counts as MASTERED. */
    const val MASTERED_INTERVAL_THRESHOLD = 21
}

object SrsAlgorithm {
    private const val ONE_DAY_MILLIS = 86_400_000L
    private const val AGAIN_DELAY_MILLIS = 10 * 60_000L

    fun calculateNextReview(
        currentReview: SrsStateEntity?,
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
                newEase = max(SrsAlgorithmLimits.MIN_EASE_FACTOR, currentEase - 0.20)
                newState = "LEARNING"
            }
            SrsRating.HARD -> {
                newRepetitions = currentRepetitions + 1
                newInterval = if (currentInterval <= 1) 1 else max(2, (currentInterval * 1.2).toInt())
                newEase = max(SrsAlgorithmLimits.MIN_EASE_FACTOR, currentEase - 0.15)
                newState = if (newInterval >= SrsAlgorithmLimits.MASTERED_INTERVAL_THRESHOLD) "MASTERED" else "LEARNING"
            }
            SrsRating.GOOD -> {
                newRepetitions = currentRepetitions + 1
                newInterval = when (newRepetitions) {
                    1 -> 1
                    2 -> 3
                    else -> max(currentInterval + 1, (currentInterval * currentEase).toInt())
                }
                newEase = currentEase
                newState = if (newInterval >= SrsAlgorithmLimits.MASTERED_INTERVAL_THRESHOLD) "MASTERED" else "REVIEW"
            }
            SrsRating.EASY -> {
                newRepetitions = currentRepetitions + 1
                newInterval = when (newRepetitions) {
                    1 -> 4
                    2 -> 7
                    else -> max(currentInterval + 2, (currentInterval * currentEase * 1.35).toInt())
                }
                newEase = min(SrsAlgorithmLimits.MAX_EASE_FACTOR, currentEase + 0.15)
                newState = if (newInterval >= SrsAlgorithmLimits.MASTERED_INTERVAL_THRESHOLD) "MASTERED" else "REVIEW"
            }
        }

        // Cap the scheduled interval so a long streak of easy ratings cannot push a
        // card years into the future.
        val scheduledInterval = min(newInterval, SrsAlgorithmLimits.MAX_INTERVAL_DAYS)

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
