package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.data.model.LearningSessionEntity
import com.example.data.model.SessionCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningSessionDao {

    @Insert
    suspend fun insert(session: LearningSessionEntity): Long

    @Query("SELECT * FROM learning_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LearningSessionEntity?

    @Query("SELECT * FROM learning_sessions WHERE userId = :userId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveForUser(userId: Long): LearningSessionEntity?

    @Query("SELECT * FROM learning_sessions WHERE userId = :userId ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(userId: Long, limit: Int): Flow<List<LearningSessionEntity>>

    @Query("SELECT * FROM learning_sessions WHERE userId = :userId AND startedAt >= :since ORDER BY startedAt DESC")
    fun observeStartedSince(userId: Long, since: Long): Flow<List<LearningSessionEntity>>

    @Upsert
    suspend fun upsert(session: LearningSessionEntity)

    /** Scoped by `userId` so a session id from another learner cannot be closed or read. */
    @Query(
        """
        UPDATE learning_sessions
        SET status = :status, endedAt = :endedAt, reviewedCount = :reviewedCount,
            correctCount = :correctCount, durationMillis = :durationMillis
        WHERE id = :id AND userId = :userId
        """
    )
    suspend fun close(
        id: Long,
        userId: Long,
        status: String,
        endedAt: Long,
        reviewedCount: Int,
        correctCount: Int,
        durationMillis: Long
    ): Int

    @Query("SELECT COUNT(*) FROM learning_sessions WHERE userId = :userId AND status = 'COMPLETED'")
    fun observeCompletedCount(userId: Long): Flow<Int>
}

@Dao
interface SessionCardDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(cards: List<SessionCardEntity>): List<Long>

    @Query("SELECT * FROM session_cards WHERE sessionId = :sessionId ORDER BY sequence ASC")
    suspend fun getForSession(sessionId: Long): List<SessionCardEntity>

    @Query("SELECT * FROM session_cards WHERE sessionId = :sessionId AND vocabularyId = :vocabularyId LIMIT 1")
    suspend fun get(sessionId: Long, vocabularyId: Long): SessionCardEntity?

    /**
     * Records the answer on the card row.
     *
     * This is the only mutation a session card accepts, and it is the write that makes
     * `answeredAt` and `rating` non-null together: a card is either unanswered or answered.
     */
    @Query(
        """
        UPDATE session_cards
        SET rating = :rating, answeredAt = :answeredAt, responseTimeMillis = :responseTimeMillis,
            postIntervalDays = :postIntervalDays, postState = :postState
        WHERE id = :id AND rating IS NULL
        """
    )
    suspend fun recordAnswer(
        id: Long,
        rating: Int,
        answeredAt: Long,
        responseTimeMillis: Long,
        postIntervalDays: Int,
        postState: String
    ): Int

    @Query("SELECT COUNT(*) FROM session_cards WHERE sessionId = :sessionId AND rating IS NOT NULL")
    suspend fun countAnswered(sessionId: Long): Int
}
