package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The catalogue of badges the app can award, and the rule for each.
 *
 * Definitions are global content, not per-learner state: a badge is the same for everyone,
 * so it is defined once and referenced by [UserAchievementEntity]. Keeping the rule here as
 * data (`metricKey` plus `thresholdValue`) means a new badge is a seeded row rather than a
 * code change, and no learner can be handed a badge that has no definition behind it.
 */
@Entity(
    tableName = "achievements",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["metricKey"])
    ]
)
data class AchievementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Stable external key used by seed scripts and remote config, e.g. `STREAK_7`. */
    val code: String,
    val title: String,
    val description: String = "",
    val category: String = "GENERAL",
    /** 1 = bronze upward; used for grouping and ordering in the UI. */
    val tier: Int = 1,
    val iconKey: String = "",
    /** Which measured quantity the rule reads, e.g. `STREAK_DAYS`, `WORDS_MASTERED`. */
    val metricKey: String = "",
    /** Value of [metricKey] at which the badge is earned. */
    val thresholdValue: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A learner's progress toward, and unlock of, one badge.
 *
 * The unique key `(userId, achievementId)` makes awarding idempotent: re-running the award
 * pass updates the existing row instead of creating a second unlock for the same badge.
 * `unlockedAt` stays null while the badge is still in progress, which is why progress is
 * tracked for locked badges too rather than only on unlock.
 */
@Entity(
    tableName = "user_achievements",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AchievementEntity::class,
            parentColumns = ["id"],
            childColumns = ["achievementId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId", "achievementId"], unique = true),
        Index(value = ["userId"]),
        // Leading the cascade from the shared badge catalogue.
        Index(value = ["achievementId"])
    ]
)
data class UserAchievementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val achievementId: Long,
    /** Latest measured value, whether or not the threshold has been reached. */
    val progressValue: Int = 0,
    val unlockedAt: Long? = null,
    /** When the learner opened the award notification; null if never shown. */
    val seenAt: Long? = null
)
