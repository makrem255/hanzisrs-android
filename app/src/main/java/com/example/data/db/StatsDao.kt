package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.data.model.AchievementEntity
import com.example.data.model.DailyStatEntity
import com.example.data.model.StreakEntity
import com.example.data.model.UserAchievementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyStatDao {

    @Query("SELECT * FROM daily_stats WHERE userId = :userId AND dateEpochDay = :epochDay LIMIT 1")
    suspend fun get(userId: Long, epochDay: Int): DailyStatEntity?

    @Query("SELECT * FROM daily_stats WHERE userId = :userId ORDER BY dateEpochDay DESC LIMIT :limit")
    fun observeRecent(userId: Long, limit: Int): Flow<List<DailyStatEntity>>

    @Query("SELECT * FROM daily_stats WHERE userId = :userId AND dateEpochDay >= :fromEpochDay ORDER BY dateEpochDay ASC")
    fun observeSince(userId: Long, fromEpochDay: Int): Flow<List<DailyStatEntity>>

    @Query("SELECT COALESCE(SUM(reviewsCompleted), 0) FROM daily_stats WHERE userId = :userId")
    fun observeLifetimeReviews(userId: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(studyMillis), 0) FROM daily_stats WHERE userId = :userId")
    fun observeLifetimeStudyMillis(userId: Long): Flow<Long>

    /**
     * The roll-up is written with an explicit upsert rather than an increment, keyed on the
     * unique `(userId, dateEpochDay)`. Recomputing a day from `review_log` and writing the
     * total makes the job idempotent: running it twice produces the same row, whereas an
     * increment would double-count.
     */
    @Upsert
    suspend fun upsert(stat: DailyStatEntity)

    @Query("DELETE FROM daily_stats WHERE userId = :userId AND dateEpochDay < :beforeEpochDay")
    suspend fun pruneBefore(userId: Long, beforeEpochDay: Int): Int
}

@Dao
interface StreakDao {

    @Query("SELECT * FROM streaks WHERE userId = :userId LIMIT 1")
    suspend fun get(userId: Long): StreakEntity?

    @Query("SELECT * FROM streaks WHERE userId = :userId LIMIT 1")
    fun observe(userId: Long): Flow<StreakEntity?>

    @Upsert
    suspend fun upsert(streak: StreakEntity)

    /**
     * IGNORE, so creating the one row a learner is entitled to is idempotent and a second
     * caller cannot produce a duplicate streak despite the unique index.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(streak: StreakEntity): Long
}

@Dao
interface AchievementDao {

    @Query("SELECT * FROM achievements WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): AchievementEntity?

    @Query("SELECT * FROM achievements WHERE isActive = 1 ORDER BY tier ASC, code ASC")
    fun observeActive(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements WHERE isActive = 1 AND metricKey = :metricKey ORDER BY thresholdValue ASC")
    suspend fun getActiveByMetric(metricKey: String): List<AchievementEntity>

    @Query("SELECT * FROM achievements WHERE isActive = 1")
    suspend fun getAllActive(): List<AchievementEntity>

    /** IGNORE, so seeding the catalogue twice keeps the original ids. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(achievements: List<AchievementEntity>): List<Long>
}

@Dao
interface UserAchievementDao {

    @Query("SELECT * FROM user_achievements WHERE userId = :userId AND achievementId = :achievementId LIMIT 1")
    suspend fun get(userId: Long, achievementId: Long): UserAchievementEntity?

    @Query("SELECT * FROM user_achievements WHERE userId = :userId ORDER BY id ASC")
    fun observeForUser(userId: Long): Flow<List<UserAchievementEntity>>

    @Query(
        """
        SELECT ua.* FROM user_achievements ua
        JOIN achievements a ON a.id = ua.achievementId
        WHERE ua.userId = :userId AND ua.unlockedAt IS NOT NULL
        ORDER BY ua.unlockedAt DESC
        """
    )
    fun observeUnlocked(userId: Long): Flow<List<UserAchievementEntity>>

    @Query("SELECT COUNT(*) FROM user_achievements WHERE userId = :userId AND unlockedAt IS NOT NULL")
    fun observeUnlockedCount(userId: Long): Flow<Int>

    /**
     * Records progress, unlocking on the same statement when the threshold is met.
     *
     * Guarded by `unlockedAt IS NULL` so re-running the award pass cannot move an unlock's
     * timestamp, which would make a badge look newly earned every time the app opened.
     */
    @Query(
        """
        INSERT INTO user_achievements (userId, achievementId, progressValue, unlockedAt, seenAt)
        VALUES (:userId, :achievementId, :progressValue,
                CASE WHEN :progressValue >= :threshold THEN :now ELSE NULL END,
                NULL)
        ON CONFLICT(userId, achievementId) DO UPDATE SET
            progressValue = MAX(progressValue, :progressValue),
            unlockedAt = COALESCE(unlockedAt,
                CASE WHEN :progressValue >= :threshold THEN :now ELSE NULL END)
        """
    )
    suspend fun recordProgress(
        userId: Long,
        achievementId: Long,
        progressValue: Int,
        threshold: Int,
        now: Long
    ): Long

    @Query("UPDATE user_achievements SET seenAt = :seenAt WHERE id = :id AND userId = :userId AND seenAt IS NULL")
    suspend fun markSeen(id: Long, userId: Long, seenAt: Long): Int
}
