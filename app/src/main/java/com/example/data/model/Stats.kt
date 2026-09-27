package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per learner per calendar day: the pre-aggregated study totals.
 *
 * Aggregating at write time keeps the history chart and the streak rule off the review log,
 * which grows without bound. The unique key `(userId, dateEpochDay)` is what makes the
 * nightly roll-up idempotent — re-running it updates the same row instead of double
 * counting.
 */
@Entity(
    tableName = "daily_stats",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId", "dateEpochDay"], unique = true)
    ]
)
data class DailyStatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    /**
     * Days since 1970-01-01 in the learner's own time zone.
     *
     * An epoch day rather than a timestamp, so a day's boundary is a plain comparison and
     * cannot be shifted by a device time-zone change after the fact.
     */
    val dateEpochDay: Int,
    val reviewsCompleted: Int = 0,
    val correctReviews: Int = 0,
    val againReviews: Int = 0,
    val newWordsIntroduced: Int = 0,
    val newWordsMastered: Int = 0,
    val studyMillis: Long = 0,
    val sessionCount: Int = 0
) {
    /** Share of answers that were not AGAIN, or null when the day has no answers yet. */
    val accuracyRate: Double?
        get() = if (reviewsCompleted <= 0) null else correctReviews.toDouble() / reviewsCompleted
}

/**
 * The learner's current and best consecutive-days run.
 *
 * One row per learner, kept as running totals rather than recomputed from `daily_stats` on
 * every read. `lastStudyEpochDay` is what distinguishes "studied yesterday, streak alive"
 * from "skipped a day, streak broken" in a single comparison.
 */
@Entity(
    tableName = "streaks",
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
data class StreakEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val currentLength: Int = 0,
    val longestLength: Int = 0,
    val totalActiveDays: Int = 0,
    /** Null when the streak has never been started. */
    val lastStudyEpochDay: Int? = null,
    val lastStudyMillis: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
