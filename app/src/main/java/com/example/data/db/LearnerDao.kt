package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.data.model.ReviewLogEntity
import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.model.UserVocabularyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserVocabularyDao {

    @Query("SELECT * FROM user_vocabulary WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UserVocabularyEntity?

    @Query("SELECT * FROM user_vocabulary WHERE userId = :userId AND vocabularyId = :vocabularyId LIMIT 1")
    suspend fun getByVocabulary(userId: Long, vocabularyId: Long): UserVocabularyEntity?

    @Query("SELECT * FROM user_vocabulary WHERE userId = :userId AND status = 'ACTIVE' ORDER BY addedAt DESC")
    fun observeForUser(userId: Long): Flow<List<UserVocabularyEntity>>

    @Query("SELECT COUNT(*) FROM user_vocabulary WHERE userId = :userId AND status = 'ACTIVE'")
    fun observeActiveCount(userId: Long): Flow<Int>

    /**
     * ABORT, not REPLACE.
     *
     * The unique index on `(userId, vocabularyId)` is the duplicate guard, and this is the
     * only statement that reports the conflict. REPLACE would silently delete the existing
     * enrolment and cascade away its `srs_state` row, taking the learner's review schedule
     * with it — the exact data loss this table exists to prevent.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(enrollment: UserVocabularyEntity): Long

    @Upsert
    suspend fun upsert(enrollment: UserVocabularyEntity)

    @Query("UPDATE user_vocabulary SET customNote = :note WHERE id = :id AND userId = :userId")
    suspend fun setNote(id: Long, userId: Long, note: String): Int

    @Query("UPDATE user_vocabulary SET isStarred = :starred WHERE id = :id AND userId = :userId")
    suspend fun setStarred(id: Long, userId: Long, starred: Boolean): Int

    @Query("UPDATE user_vocabulary SET status = :status WHERE id = :id AND userId = :userId")
    suspend fun setStatus(id: Long, userId: Long, status: String): Int

    @Query("DELETE FROM user_vocabulary WHERE id = :id AND userId = :userId")
    suspend fun deleteForUser(id: Long, userId: Long): Int

    @Query("SELECT COUNT(*) FROM user_vocabulary WHERE userId = :userId")
    suspend fun countForUser(userId: Long): Int
}

@Dao
interface SrsStateDao {

    @Query("SELECT * FROM srs_state WHERE userId = :userId AND userVocabularyId = :userVocabularyId LIMIT 1")
    suspend fun get(userId: Long, userVocabularyId: Long): SrsStateEntity?

    @Query("SELECT * FROM srs_state WHERE userId = :userId ORDER BY dueDateMillis ASC")
    fun observeForUser(userId: Long): Flow<List<SrsStateEntity>>

    @Query("SELECT COUNT(*) FROM srs_state WHERE userId = :userId AND dueDateMillis <= :now")
    fun observeDueCount(userId: Long, now: Long): Flow<Int>

    @Query("SELECT * FROM srs_state WHERE userId = :userId AND state = :state")
    fun observeByState(userId: Long, state: String): Flow<List<SrsStateEntity>>

    /**
     * Creates the initial scheduling row for an enrolled word.
     *
     * The composite foreign key into `user_vocabulary` is what actually prevents an unowned
     * row. This `WHERE` is a courtesy that keeps the failure mode friendly: an id the learner
     * does not own matches no row, so the insert affects zero rows and the repository reports
     * a typed error instead of the caller catching a constraint exception.
     *
     * Callers must test the result with `<= 0`, not `== 0`. Room does not report "inserted
     * nothing" as zero here: it maps a zero-row `INSERT ... SELECT` to `-1`, because there is no
     * `last_insert_rowid` to read back. An `== 0` check would pass on a failed insert and
     * silently leave a card unscheduled, which is the exact failure this method exists to
     * prevent. On success the value is the new row's id.
     *
     * The two literals below are written out rather than interpolated from
     * [StorageValues.CardState] and [StorageValues.SchedulerVersion]: an enum entry's
     * `storageValue` is not a compile-time constant and so cannot be spliced into an
     * annotation. This statement only ever creates a brand-new card, so `NEW` is the only
     * state it can be responsible for.
     */
    @Query(
        """
        INSERT INTO srs_state (
            userId, userVocabularyId, vocabularyId, intervalDays, repetitions, easeFactor,
            dueDateMillis, lastReviewMillis, state, lastRating, lapses, totalReviews,
            schedulerVersion, updatedAt
        )
        SELECT :userId, uv.id, uv.vocabularyId, :intervalDays, :repetitions, :easeFactor,
               :dueDateMillis, 0, 'NEW', 0, 0, 0, 1, :now
        FROM user_vocabulary uv
        WHERE uv.userId = :userId AND uv.id = :userVocabularyId
        """
    )
    suspend fun insertIfEnrolled(
        userId: Long,
        userVocabularyId: Long,
        intervalDays: Int,
        repetitions: Int,
        easeFactor: Double,
        dueDateMillis: Long,
        now: Long
    ): Long

    @Query("SELECT * FROM srs_state WHERE userVocabularyId = :userVocabularyId LIMIT 1")
    suspend fun getByEnrollment(userVocabularyId: Long): SrsStateEntity?

    /** The composite primary key makes this an insert-or-replace of exactly one card's state. */
    @Upsert
    suspend fun upsert(state: SrsStateEntity)

    @Query("DELETE FROM srs_state WHERE userId = :userId AND userVocabularyId = :userVocabularyId")
    suspend fun delete(userId: Long, userVocabularyId: Long): Int
}

@Dao
interface ReviewLogDao {

    /**
     * Append-only by design: this DAO has no update and no delete.
     *
     * A review history that can be edited is not a history, and this table is the input a
     * personalised scheduler and the retention statistics are derived from.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun append(entry: ReviewLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun appendAll(entries: List<ReviewLogEntity>): List<Long>

    @Query("SELECT * FROM review_log WHERE userId = :userId ORDER BY reviewedAt DESC LIMIT :limit")
    fun observeRecent(userId: Long, limit: Int): Flow<List<ReviewLogEntity>>

    @Query(
        """
        SELECT * FROM review_log
        WHERE userId = :userId AND vocabularyId = :vocabularyId
        ORDER BY reviewedAt DESC
        """
    )
    suspend fun getForCard(userId: Long, vocabularyId: Long): List<ReviewLogEntity>

    // Presentation order lives on `session_cards`, not here; within a session the log is
    // ordered by when each answer actually arrived.
    @Query("SELECT * FROM review_log WHERE sessionId = :sessionId ORDER BY reviewedAt ASC")
    suspend fun getForSession(sessionId: Long): List<ReviewLogEntity>

    @Query("SELECT COUNT(*) FROM review_log WHERE userId = :userId")
    suspend fun countForUser(userId: Long): Int

    /**
     * How many answers of each rating this learner has ever given.
     *
     * Grouped in SQL rather than by loading the log and counting in Kotlin: this is a lifetime
     * total, and the only column XP needs is the rating. A `GROUP BY rating` is served by the
     * `userId` index and returns at most four rows, where the log itself grows without bound.
     */
    @Query("SELECT rating, COUNT(*) AS tally FROM review_log WHERE userId = :userId GROUP BY rating")
    fun observeRatingTallies(userId: Long): Flow<List<RatingTallyRow>>
}

/**
 * One row of [ReviewLogDao.observeRatingTallies].
 *
 * [rating] is the 1-4 integer `review_log` stores, resolved back to `SrsRating` by the caller.
 * The second column is named `tally` rather than `count` because `count` is a SQL aggregate's own
 * name and a projection column called `count` is a trap for anyone extending the query later.
 */
data class RatingTallyRow(
    val rating: Int,
    val tally: Int
)
