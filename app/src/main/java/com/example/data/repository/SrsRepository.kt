package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.DailyStatEntity
import com.example.data.model.ReviewLogEntity
import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.model.StreakEntity
import com.example.data.srs.SrsAlgorithm
import com.example.data.srs.SrsCalculationResult
import com.example.data.srs.SrsRating
import kotlinx.coroutines.flow.Flow

/**
 * Why a review could not be recorded.
 *
 * The swipe deck shows these; a failure must never advance the deck, or the learner loses the
 * answer they just gave.
 */
sealed interface ReviewFailure {
    data class NotEnrolled(val message: String) : ReviewFailure
    data class InvalidResult(val error: ValidationError) : ReviewFailure
}

/** Either the new schedule or the reason it was refused. */
sealed interface ReviewOutcome {
    data class Recorded(
        val result: SrsCalculationResult,
        val state: SrsStateEntity
    ) : ReviewOutcome

    data class Rejected(val failure: ReviewFailure) : ReviewOutcome
}

data class SrsStats(
    val totalWords: Int,
    val dueToday: Int,
    val newCount: Int,
    val learningCount: Int,
    val reviewCount: Int,
    val masteredCount: Int,
    val retentionRate: Int,
    val dailyStreakDays: Int
)

/**
 * Applies a rating: advances the schedule, appends to the history, updates the day's totals
 * and extends the streak.
 *
 * All of that happens in one transaction on purpose. The scheduler state and the review log
 * are two views of the same event: if they were written separately, a crash between them would
 * leave a card that moved forward with no record of why, and every later retention figure
 * derived from the log would be quietly wrong.
 */
class SrsRepository(private val database: AppDatabase) {

    private val srsDao = database.srsStateDao()
    private val reviewLogDao = database.reviewLogDao()
    private val enrollmentDao = database.userVocabularyDao()
    private val dailyStatDao = database.dailyStatDao()
    private val streakDao = database.streakDao()

    fun getAllReviewsForUser(userId: Long): Flow<List<SrsStateEntity>> =
        srsDao.observeForUser(userId)

    suspend fun getReviewForWord(userVocabularyId: Long): SrsStateEntity? =
        srsDao.getByEnrollment(userVocabularyId)

    /**
     * Records one answer.
     *
     * @param userVocabularyId the enrolment, not the shared content: a learner can only ever
     *   address their own card, and the foreign key from `srs_state` enforces it even if this
     *   method is called with someone else's id.
     * @param now the instant the answer is attributed to. Injectable so the streak rule, which
     *   is defined in whole days, can be tested at a chosen date instead of only today.
     */
    suspend fun processReview(
        userVocabularyId: Long,
        userId: Long,
        rating: SrsRating,
        sessionId: Long? = null,
        responseTimeMillis: Long = 0,
        now: Long = System.currentTimeMillis()
    ): ReviewOutcome = database.withTransaction {
        val enrollment = enrollmentDao.getById(userVocabularyId)
        if (enrollment == null || enrollment.userId != userId) {
            return@withTransaction ReviewOutcome.Rejected(
                ReviewFailure.NotEnrolled("That card is not in your collection.")
            )
        }

        val existing = srsDao.get(userId, userVocabularyId)
        val result = SrsAlgorithm.calculateNextReview(existing, rating, now)

        Validator.validateSchedulerState(
            state = result.state,
            lastRating = rating.value,
            intervalDays = result.intervalDays,
            easeFactor = result.easeFactor
        )?.let { return@withTransaction ReviewOutcome.Rejected(ReviewFailure.InvalidResult(it)) }

        val previousInterval = existing?.intervalDays ?: 0
        val previousEase = existing?.easeFactor ?: WordRepository.DEFAULT_EASE_FACTOR
        val previousState = existing?.state ?: StorageValues.CardState.NEW.storageValue
        val previousDue = existing?.dueDateMillis ?: now

        val updated = SrsStateEntity(
            userId = userId,
            userVocabularyId = userVocabularyId,
            vocabularyId = enrollment.vocabularyId,
            intervalDays = result.intervalDays,
            repetitions = result.repetitions,
            easeFactor = result.easeFactor,
            dueDateMillis = result.dueDateMillis,
            lastReviewMillis = now,
            state = result.state,
            lastRating = rating.value,
            lapses = (existing?.lapses ?: 0) + if (rating == SrsRating.AGAIN) 1 else 0,
            totalReviews = (existing?.totalReviews ?: 0) + 1,
            schedulerVersion = StorageValues.SchedulerVersion.SM2,
            updatedAt = now
        )
        srsDao.upsert(updated)

        // Append-only. `elapsedMillis` is how late the answer was, which is the single most
        // useful input to a personalised scheduler later on.
        reviewLogDao.append(
            ReviewLogEntity(
                userId = userId,
                vocabularyId = enrollment.vocabularyId,
                sessionId = sessionId,
                reviewedAt = now,
                rating = rating.value,
                previousIntervalDays = previousInterval,
                newIntervalDays = result.intervalDays,
                previousEaseFactor = previousEase,
                newEaseFactor = result.easeFactor,
                previousState = previousState,
                newState = result.state,
                elapsedMillis = (now - previousDue).coerceAtLeast(0),
                responseTimeMillis = responseTimeMillis.coerceAtLeast(0),
                schedulerVersion = StorageValues.SchedulerVersion.SM2
            )
        )

        recordActivity(userId, rating, result, now)

        ReviewOutcome.Recorded(result, updated)
    }

    /**
     * Puts a card back to the start of its schedule without inventing a review.
     *
     * Deliberately appends nothing: forgetting a card on purpose is not a graded answer, and
     * letting it into `review_log` would corrupt every retention figure computed from it.
     */
    suspend fun resetCard(userVocabularyId: Long, userId: Long, now: Long = System.currentTimeMillis()): Boolean =
        database.withTransaction {
            val existing = srsDao.get(userId, userVocabularyId) ?: return@withTransaction false
            srsDao.upsert(
                existing.copy(
                    intervalDays = 1,
                    repetitions = 0,
                    easeFactor = WordRepository.DEFAULT_EASE_FACTOR,
                    dueDateMillis = now,
                    lastReviewMillis = 0L,
                    state = StorageValues.CardState.NEW.storageValue,
                    lastRating = 0,
                    schedulerVersion = StorageValues.SchedulerVersion.SM2,
                    updatedAt = now
                )
            )
            enrollmentDao.setStatus(
                userVocabularyId,
                userId,
                StorageValues.EnrollmentStatus.ACTIVE.storageValue
            )
            true
        }

    // ---- derived aggregates -----------------------------------------------------------------

    /**
     * Folds one answer into the day's totals and the streak, inside the caller's transaction.
     *
     * The day is bucketed by epoch day in the learner's own zone. Doing the arithmetic on the
     * stored row rather than with an `UPDATE ... SET x = x + 1` keeps the row reconstructable:
     * the totals are a cache of `review_log`, and a cache you can recompute is one you can
     * repair.
     */
    private suspend fun recordActivity(
        userId: Long,
        rating: SrsRating,
        result: SrsCalculationResult,
        now: Long
    ) {
        val epochDay = epochDayOf(now)
        val existing = dailyStatDao.get(userId, epochDay)
        val reviews = (existing?.reviewsCompleted ?: 0) + 1
        dailyStatDao.upsert(
            DailyStatEntity(
                id = existing?.id ?: 0,
                userId = userId,
                dateEpochDay = epochDay,
                reviewsCompleted = reviews,
                correctReviews = (existing?.correctReviews ?: 0) + if (rating == SrsRating.AGAIN) 0 else 1,
                againReviews = (existing?.againReviews ?: 0) + if (rating == SrsRating.AGAIN) 1 else 0,
                newWordsIntroduced = existing?.newWordsIntroduced ?: 0,
                newWordsMastered = (existing?.newWordsMastered ?: 0) +
                    if (result.state == StorageValues.CardState.MASTERED.storageValue) 1 else 0,
                studyMillis = (existing?.studyMillis ?: 0) + estimateStudyMillis(result),
                sessionCount = existing?.sessionCount ?: 0
            )
        )

        advanceStreak(userId, epochDay, now)
    }

    /**
     * Extends, restarts or closes the streak.
     *
     * The three cases are distinguished by how many days have passed since the last study day:
     * same day is a no-op, the next day extends, and a gap of two or more resets the current
     * run to 1. A streak is therefore only ever broken by a genuinely missed day, not by
     * opening the app twice.
     */
    private suspend fun advanceStreak(userId: Long, epochDay: Int, now: Long) {
        val existing = streakDao.get(userId)
        val lastDay = existing?.lastStudyEpochDay
        val previousLength = existing?.currentLength ?: 0
        val previousTotal = existing?.totalActiveDays ?: 0
        val previousLongest = existing?.longestLength ?: 0

        val current = when {
            // First study day on record.
            lastDay == null -> 1
            // Already studied today: the day is counted, the run is not.
            lastDay == epochDay -> previousLength.coerceAtLeast(1)
            // Studied yesterday: this is an ordinary extension.
            lastDay == epochDay - 1 -> previousLength + 1
            // A day was missed, so the previous run is over.
            else -> 1
        }

        streakDao.upsert(
            StreakEntity(
                id = existing?.id ?: 0,
                userId = userId,
                currentLength = current,
                longestLength = maxOf(previousLongest, current),
                // Studying twice in one day must not inflate the all-time day count.
                totalActiveDays = if (lastDay == epochDay) maxOf(previousTotal, 1) else previousTotal + 1,
                lastStudyEpochDay = epochDay,
                lastStudyMillis = now,
                updatedAt = now
            )
        )
    }

    /**
     * Days since 1970-01-01 in UTC.
     *
     * The learner's own zone is the correct boundary for a study day, but it is not available
     * to a pure calculation, so this uses UTC and the roll-up job is what reconciles a day
     * against a profile's `timezoneId`. Getting this wrong costs a day's bucket, never data.
     */
    private fun epochDayOf(millis: Long): Int = Math.floorDiv(millis, 86_400_000L).toInt()

    private fun estimateStudyMillis(result: SrsCalculationResult): Long {
        val thinkTime = 4_000L
        val revealTime = 2_500L
        return if (result.intervalDays == 0) thinkTime / 2 else thinkTime + revealTime
    }
}
