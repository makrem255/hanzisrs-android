package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "srs_reviews",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("wordId", unique = true),
        Index("userId"),
        Index("dueDateMillis")
    ]
)
data class SrsReviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val wordId: Long,
    val userId: Long,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val easeFactor: Double = 2.5,
    val dueDateMillis: Long = System.currentTimeMillis(),
    val lastReviewMillis: Long = 0L,
    val state: String = "NEW", // "NEW", "LEARNING", "REVIEW", "MASTERED"
    val lastRating: Int = 0, // 1=Again, 2=Hard, 3=Good, 4=Easy
    val totalReviews: Int = 0
)
