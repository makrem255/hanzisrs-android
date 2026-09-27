package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Editable attributes of a learner that are not credentials and not settings.
 *
 * Exactly one row per user, enforced by the unique index on `userId`. The identity row
 * stays untouched by a profile edit, so a failure here can never lock a learner out.
 */
@Entity(
    tableName = "user_profiles",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId"], unique = true)
    ]
)
data class UserProfileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    /** Seed for the generated avatar; the avatar image itself is not stored in the database. */
    val avatarSeed: String = "",
    /** IANA zone id, e.g. `Europe/Berlin`. Daily buckets are cut in this zone. */
    val timezoneId: String = "UTC",
    val locale: String = "en",
    val bio: String = "",
    /**
     * Target exam date as days since the epoch, or null when unset.
     *
     * Stored as an epoch day rather than a timestamp so a study plan stays on the same
     * calendar day regardless of the device time zone it was created in.
     */
    val targetExamEpochDay: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Device-independent study settings for one learner, exactly one row per user.
 *
 * Split from [UserProfileEntity] so a settings write can be frequent and narrow while the
 * profile stays stable. The unique index on `userId` makes "one preference row per learner"
 * a database guarantee rather than an application convention.
 */
@Entity(
    tableName = "user_preferences",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId"], unique = true)
    ]
)
data class UserPreferenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val themeMode: String = StorageValues.ThemeMode.SYSTEM.storageValue,
    /** Speech rate multiplier; range is validated on write, not by a CHECK constraint. */
    val ttsSpeed: Double = 1.0,
    val dailyNewWordLimit: Int = 10,
    val dailyReviewLimit: Int = 60,
    val remindersEnabled: Boolean = false,
    /** Local hour of day, 0-23, at which the due-words reminder fires. */
    val reminderHour: Int = 19,
    val showPinyin: Boolean = true,
    val showStrokeOrder: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
