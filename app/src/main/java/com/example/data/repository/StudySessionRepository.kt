package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.LearningSessionEntity
import com.example.data.model.SessionCardEntity
import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.srs.SrsRating

/**
 * Opens, records and closes a sitting of study.
 *
 * The `learning_sessions` and `session_cards` tables already existed, with the columns, the
 * indices and the constraints a session needs - and nothing had ever written to them. This is the
 * writer, and it exists because two things that are already displayed depend on it: a session has
 * to be a countable thing for a badge to hang on, and a session's result has to be a stored fact
 * for a completion summary to report rather than a set of numbers a screen kept in memory.
 *
 * Every write is a transaction, and a session is only ever closed once. `close` is guarded on
 * `status = 'ACTIVE'`, so an abandoned session and a finished one cannot both claim the same
 * answers, and the totals on a session are recomputed from its own card rows rather than
 * incremented - running this twice produces the same numbers instead of double them.
 */
class StudySessionRepository(
    private val database: AppDatabase
) {

    private val sessionDao = database.learningSessionDao()
    private val cardDao = database.sessionCardDao()

    /**
     * Opens a session and records the cards it will present.
     *
     * The cards are inserted up front rather than on presentation so the session's `targetCount`
     * is a real commitment and `(sessionId, vocabularyId)` is a real duplicate guard: a word
     * cannot appear twice in one sitting, and the insert is the thing that says so.
     *
     * @param vocabularyIds the cards, in presentation order, as the shared content ids. Order is
     *   preserved as `sequence`, which is what the session card's own index makes unambiguous.
     * @return the session id, or null if the deck was empty - there is no sitting of zero cards,
     *   and writing one would put a row in the table that `SESSIONS_COMPLETED` counts.
     */
    suspend fun begin(
        userId: Long,
        type: StorageValues.SessionType,
        vocabularyIds: List<Long>,
        now: Long
    ): Long? = database.withTransaction {
        if (vocabularyIds.isEmpty()) return@withTransaction null

        // Reopening rather than accumulating: a learner who abandons a sitting and starts again
        // should have one session recorded, not a trail of empty ones.
        sessionDao.getActiveForUser(userId)?.let { stale ->
            sessionDao.close(
                id = stale.id,
                userId = userId,
                status = StorageValues.SessionStatus.ABANDONED.storageValue,
                endedAt = now,
                reviewedCount = 0,
                correctCount = 0,
                durationMillis = 0
            )
        }

        val sessionId = sessionDao.insert(
            LearningSessionEntity(
                userId = userId,
                sessionType = type.storageValue,
                status = StorageValues.SessionStatus.ACTIVE.storageValue,
                targetCount = vocabularyIds.size,
                startedAt = now
            )
        )
        cardDao.insertAll(
            vocabularyIds.mapIndexed { sequence, vocabularyId ->
                SessionCardEntity(
                    sessionId = sessionId,
                    vocabularyId = vocabularyId,
                    sequence = sequence,
                    presentedAt = now
                )
            }
        )
        sessionId
    }

    /**
     * Records one answer on its session card.
     *
     * Scoped to the session and to a card that has not been answered, because the unique indices
     * make a second answer a constraint failure and a double-tap should not be able to produce
     * one. Returns false when the card was already answered or does not belong to the session, so
     * the caller can treat it as "nothing to do" rather than an error.
     *
     * @param schedule the card's scheduling state *after* the review, which is the only version
     *   worth storing: what the learner is about to be shown next.
     */
    suspend fun recordAnswer(
        sessionId: Long,
        userId: Long,
        vocabularyId: Long,
        rating: SrsRating,
        answeredAt: Long,
        responseTimeMillis: Long,
        schedule: SrsStateEntity
    ): Boolean = database.withTransaction {
        val card = cardDao.get(sessionId, vocabularyId) ?: return@withTransaction false
        val written = cardDao.recordAnswer(
            id = card.id,
            rating = rating.value,
            answeredAt = answeredAt,
            responseTimeMillis = responseTimeMillis.coerceAtLeast(0),
            postIntervalDays = schedule.intervalDays,
            postState = schedule.state
        )
        written > 0
    }

    /**
     * Closes a session, recomputing its totals from the answers it actually holds.
     *
     * @param status [StorageValues.SessionStatus.COMPLETED] when the learner worked through the
     *   deck, [StorageValues.SessionStatus.ABANDONED] when they left partway. The two are
     *   distinguished because "studied 8 cards" and "studied 8 of 20 cards" are different facts
     *   and a summary that shows both as the same number is lying about one of them.
     * @return the closed session, or null if it was not active - which is how a double close
     *   reports itself.
     */
    suspend fun end(
        sessionId: Long,
        userId: Long,
        status: StorageValues.SessionStatus,
        now: Long
    ): LearningSessionEntity? = database.withTransaction {
        val session = sessionDao.getById(sessionId) ?: return@withTransaction null
        if (session.userId != userId) return@withTransaction null
        if (session.status != StorageValues.SessionStatus.ACTIVE.storageValue) return@withTransaction null

        val answered = cardDao.getForSession(sessionId).filter { it.rating != null }
        val correct = answered.count { card ->
            SrsRating.fromValue(card.rating!!)?.let { it != SrsRating.AGAIN } ?: false
        }
        val duration = (now - session.startedAt).coerceAtLeast(0)

        sessionDao.close(
            id = sessionId,
            userId = userId,
            status = status.storageValue,
            endedAt = now,
            reviewedCount = answered.size,
            correctCount = correct,
            durationMillis = duration
        )
        sessionDao.getById(sessionId)
    }

    /**
     * The session a learner currently has open, if any.
     *
     * Used to reattach to a session after the process was killed, which is the reason the table
     * is persisted at all rather than held in a view model.
     */
    suspend fun activeFor(userId: Long): LearningSessionEntity? = sessionDao.getActiveForUser(userId)
}
