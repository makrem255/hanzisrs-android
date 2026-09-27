package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.UserPreferenceEntity
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    suspend fun getForUser(userId: Long): UserProfileEntity?

    @Query("SELECT * FROM user_profiles WHERE userId = :userId LIMIT 1")
    fun observeForUser(userId: Long): Flow<UserProfileEntity?>

    @Query("SELECT COUNT(*) FROM user_profiles WHERE userId = :userId")
    suspend fun countForUser(userId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(profile: UserProfileEntity): Long

    // There is deliberately no `upsert(profile)` here. Room's `@Upsert` matches on the primary
    // key, and this table's primary key is a surrogate `id` while the row is actually
    // identified by the unique index on `userId`. A caller writing a profile the usual way --
    // `upsert(UserProfileEntity(userId = u, bio = "..."))`, with `id` left at its default of
    // zero -- matches no row to update, inserts a second row, and trips the unique index. That
    // is a constraint violation at runtime and it reads as though the write were idempotent,
    // which is the opposite of what the name promises. Inserting the one row a learner is
    // entitled to and then updating it by `userId` is two explicit operations, and both are
    // named for what they do.

    @Query(
        """
        UPDATE user_profiles
        SET bio = :bio, timezoneId = :timezoneId, locale = :locale,
            targetExamEpochDay = :targetExamEpochDay, avatarSeed = :avatarSeed, updatedAt = :now
        WHERE userId = :userId
        """
    )
    suspend fun updateForUser(
        userId: Long,
        bio: String,
        timezoneId: String,
        locale: String,
        targetExamEpochDay: Int?,
        avatarSeed: String,
        now: Long
    ): Int
}

@Dao
interface UserPreferenceDao {

    @Query("SELECT * FROM user_preferences WHERE userId = :userId LIMIT 1")
    suspend fun getForUser(userId: Long): UserPreferenceEntity?

    @Query("SELECT * FROM user_preferences WHERE userId = :userId LIMIT 1")
    fun observeForUser(userId: Long): Flow<UserPreferenceEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(preferences: UserPreferenceEntity): Long

    // No `upsert` here either, and for the reason given on `UserProfileDao`.

    @Query(
        """
        UPDATE user_preferences
        SET themeMode = :themeMode, ttsSpeed = :ttsSpeed, dailyNewWordLimit = :dailyNewWordLimit,
            dailyReviewLimit = :dailyReviewLimit, remindersEnabled = :remindersEnabled,
            reminderHour = :reminderHour, showPinyin = :showPinyin,
            showStrokeOrder = :showStrokeOrder, updatedAt = :now
        WHERE userId = :userId
        """
    )
    suspend fun updateForUser(
        userId: Long,
        themeMode: String,
        ttsSpeed: Double,
        dailyNewWordLimit: Int,
        dailyReviewLimit: Int,
        remindersEnabled: Boolean,
        reminderHour: Int,
        showPinyin: Boolean,
        showStrokeOrder: Boolean,
        now: Long
    ): Int
}
