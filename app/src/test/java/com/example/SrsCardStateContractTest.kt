package com.example

import com.example.data.model.StorageValues
import com.example.data.repository.ValidationError
import com.example.data.repository.Validator
import com.example.data.srs.SrsAlgorithm
import com.example.data.srs.SrsAlgorithmLimits
import com.example.data.srs.SrsRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract between the scheduler and the `srs_state` column vocabulary.
 *
 * `SrsAlgorithm` returns its state as a `String`, and that string is written straight into the
 * database. `StorageValues.CardState` is the only place the legal set is written down, and the
 * repository refuses to store anything outside it. If the algorithm ever emitted a state the
 * enum does not list, the row would be unreadable by every filter in the app, so the two are
 * pinned together here instead of being kept in step by convention.
 */
class SrsCardStateContractTest {

    /**
     * A fixed instant for every scheduling walk below.
     *
     * The ease band and the state vocabulary are properties of the rating sequence alone — `now`
     * only reaches the due date — so pinning it changes nothing about what is being asserted and
     * makes the walk mean the same thing on every run, on any machine, at any hour.
     */
    private val FIXED_NOW = 1_700_000_000_000L

    @Test
    fun `the algorithm only emits states the schema vocabulary declares`() {
        val ratings = SrsRating.entries
        var current: com.example.data.model.SrsStateEntity? = null

        // Walk a long, varied history so every branch of the state machine is exercised.
        repeat(60) { step ->
            val rating = ratings[step % ratings.size]
            val result = SrsAlgorithm.calculateNextReview(current, rating, now = step * 86_400_000L)
            assertNotNull(
                "step $step with $rating produced state \"${result.state}\", which is not a declared CardState",
                StorageValues.CardState.fromStorage(result.state)
            )
            current = com.example.data.model.SrsStateEntity(
                userId = 1,
                userVocabularyId = 1,
                vocabularyId = 1,
                intervalDays = result.intervalDays,
                repetitions = result.repetitions,
                easeFactor = result.easeFactor,
                dueDateMillis = result.dueDateMillis,
                state = result.state
            )
        }
    }

    @Test
    fun `the algorithm stays inside the declared ease band`() {
        val ratings = SrsRating.entries
        var current: com.example.data.model.SrsStateEntity? = null

        repeat(120) { step ->
            // `now` is explicit rather than defaulted. It used to have a
            // `System.currentTimeMillis()` default, so this loop was walking 120 schedules
            // against a clock that moved underneath it — a contract test on the scheduler's
            // bounds could pass at 23:59 and fail at 00:01. A fixed instant makes the walk
            // mean the same thing on every run.
            val result = SrsAlgorithm.calculateNextReview(
                currentReview = current,
                rating = ratings[step % ratings.size],
                now = FIXED_NOW
            )
            assertTrue(
                "ease ${result.easeFactor} left [${SrsAlgorithmLimits.MIN_EASE_FACTOR}, ${SrsAlgorithmLimits.MAX_EASE_FACTOR}]",
                result.easeFactor in SrsAlgorithmLimits.MIN_EASE_FACTOR..SrsAlgorithmLimits.MAX_EASE_FACTOR
            )
            current = com.example.data.model.SrsStateEntity(
                userId = 1,
                userVocabularyId = 1,
                vocabularyId = 1,
                intervalDays = result.intervalDays,
                repetitions = result.repetitions,
                easeFactor = result.easeFactor,
                dueDateMillis = result.dueDateMillis,
                state = result.state
            )
        }
    }

    @Test
    fun `the validator accepts exactly what the algorithm produces`() {
        val ratings = SrsRating.entries
        var current: com.example.data.model.SrsStateEntity? = null

        repeat(60) { step ->
            val rating = ratings[step % ratings.size]
            val result = SrsAlgorithm.calculateNextReview(
                currentReview = current,
                rating = rating,
                now = FIXED_NOW
            )
            assertNull(
                "the validator rejected a state the algorithm produced: ${result.state}",
                Validator.validateSchedulerState(
                    state = result.state,
                    lastRating = rating.value,
                    intervalDays = result.intervalDays,
                    easeFactor = result.easeFactor
                )
            )
            current = com.example.data.model.SrsStateEntity(
                userId = 1,
                userVocabularyId = 1,
                vocabularyId = 1,
                intervalDays = result.intervalDays,
                repetitions = result.repetitions,
                easeFactor = result.easeFactor,
                dueDateMillis = result.dueDateMillis,
                state = result.state
            )
        }
    }

    @Test
    fun `an unknown state is rejected rather than stored`() {
        val error = Validator.validateSchedulerState(
            state = "SOMETHING_ELSE",
            lastRating = 3,
            intervalDays = 5,
            easeFactor = 2.5
        )

        assertTrue(error is ValidationError.NotAllowed)
    }

    @Test
    fun `a rating outside the declared range is rejected`() {
        val tooHigh = Validator.validateSchedulerState("REVIEW", lastRating = 5, intervalDays = 5, easeFactor = 2.5)
        val negative = Validator.validateSchedulerState("REVIEW", lastRating = -1, intervalDays = 5, easeFactor = 2.5)

        assertTrue(tooHigh is ValidationError.OutOfRange)
        assertTrue(negative is ValidationError.OutOfRange)
    }

    @Test
    fun `an ease factor outside the shared band is rejected`() {
        val tooHigh = Validator.validateSchedulerState("REVIEW", 3, 5, SrsAlgorithmLimits.MAX_EASE_FACTOR + 0.01)
        val tooLow = Validator.validateSchedulerState("REVIEW", 3, 5, SrsAlgorithmLimits.MIN_EASE_FACTOR - 0.01)

        assertTrue(tooHigh is ValidationError.OutOfRange)
        assertTrue(tooLow is ValidationError.OutOfRange)
    }

    @Test
    fun `ease bounds are the same numbers in one place`() {
        // Guards against the band being duplicated and drifting apart.
        assertEquals(1.3, SrsAlgorithmLimits.MIN_EASE_FACTOR, 0.0001)
        assertEquals(2.5, SrsAlgorithmLimits.MAX_EASE_FACTOR, 0.0001)
    }
}
