package com.example.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads that answer "how far has this learner got?", spanning several tables at once.
 *
 * Separate from the table-owning DAOs because none of them should have to know about the others:
 * the number of words mastered is a fact about `srs_state` but it is only meaningful next to the
 * size of the collection, and the badge catalogue hangs both on the same rule.
 */
@Dao
interface ProgressDao {

    /**
     * The catalogue, each row carrying the learner's progress where a row exists.
     *
     * A `LEFT JOIN`, not an inner one: a badge the learner has never approached still has to
     * appear, because "3 of 50 words" is the useful thing to show someone who has three. An
     * inner join would only surface a badge once it was nearly earned, hiding exactly the badges
     * a learner could realistically still get.
     *
     * The unlocked timestamp is read only from the learner's row, so the same catalogue row is
     * unlocked for one learner and locked for another.
     */
    @Query(
        """
        SELECT a.id AS achievementId, a.code AS code, a.title AS title,
               a.description AS description, a.category AS category, a.tier AS tier,
               a.iconKey AS iconKey, a.metricKey AS metricKey, a.thresholdValue AS threshold,
               ua.id AS userAchievementId, ua.progressValue AS progressValue,
               ua.unlockedAt AS unlockedAt, ua.seenAt AS seenAt
        FROM achievements a
        LEFT JOIN user_achievements ua
               ON ua.achievementId = a.id AND ua.userId = :userId
        WHERE a.isActive = 1
        ORDER BY a.tier ASC, a.thresholdValue ASC, a.code ASC
        """
    )
    fun observeAchievementsWithProgress(userId: Long): Flow<List<AchievementProgressRow>>

    /** The same counts, for a screen to draw. */
    @Query(PROGRESS_COUNTS_SQL)
    fun observeProgressCounts(userId: Long): Flow<ProgressCountsRow>

    /**
     * The same counts, for the award pass.
     *
     * A second entry point rather than `observeProgressCounts(...).first()` so the pass can read
     * inside a transaction. Collecting a flow inside `withTransaction` is a deadlock waiting to
     * happen: Room's invalidation tracker will not deliver until the transaction commits, and the
     * transaction is waiting for the collect.
     */
    @Query(PROGRESS_COUNTS_SQL)
    suspend fun readProgressCounts(userId: Long): ProgressCountsRow

    companion object {
        /**
         * One query for every countable metric, rather than five.
         *
         * Each scalar subquery is a point lookup on a `userId` index, and the whole thing is a
         * single consistent read: five separate reads could see the learner mid-review, produce a
         * set of numbers that never coexisted, and congratulate them on a milestone defined by
         * the inconsistency.
         *
         * Nothing here is a stored counter. Every number is counted from the rows it describes,
         * so it cannot drift from the activity it claims to measure, and a badge and the
         * milestone beside it are always the same figure.
         *
         * `sessionId IS NOT NULL` is load-bearing rather than defensive. A review recorded
         * outside a session has no session, and counting each as its own session would invent a
         * study session per answer - which is precisely the number `SESSIONS_COMPLETED` names.
         *
         * `longestLength` rather than `currentLength`: a badge that says "study seven days in a
         * row" is a claim about history, and measuring the current run would make it permanently
         * unearnable after one missed day. That is the behaviour that turns a streak into a debt.
         */
        const val PROGRESS_COUNTS_SQL = """
            SELECT
                (SELECT COUNT(*) FROM user_vocabulary
                  WHERE userId = :userId AND status = 'ACTIVE') AS wordsCollected,
                (SELECT COUNT(*) FROM srs_state
                  WHERE userId = :userId AND state = 'MASTERED') AS wordsMastered,
                (SELECT COUNT(*) FROM review_log
                  WHERE userId = :userId) AS reviewsCompleted,
                (SELECT COUNT(DISTINCT sessionId) FROM review_log
                  WHERE userId = :userId AND sessionId IS NOT NULL) AS sessionsCompleted,
                (SELECT COALESCE(MAX(longestLength), 0) FROM streaks
                  WHERE userId = :userId) AS streakDays
        """
    }
}

/**
 * One line of [ProgressDao.observeAchievementsWithProgress].
 *
 * [userAchievementId], [progressValue], [unlockedAt] and [seenAt] are nullable because the join
 * may find no row; the catalogue columns are always present.
 */
data class AchievementProgressRow(
    val achievementId: Long,
    val code: String,
    val title: String,
    val description: String,
    val category: String,
    val tier: Int,
    val iconKey: String,
    val metricKey: String,
    val threshold: Int,
    val userAchievementId: Long?,
    val progressValue: Int?,
    val unlockedAt: Long?,
    val seenAt: Long?
)

/** The countable metrics for one learner, read in one consistent snapshot. */
data class ProgressCountsRow(
    val wordsCollected: Int,
    val wordsMastered: Int,
    val reviewsCompleted: Int,
    val sessionsCompleted: Int,
    val streakDays: Int
)
