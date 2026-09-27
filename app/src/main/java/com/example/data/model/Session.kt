package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One sitting of study, from the first card shown to the last answer recorded.
 *
 * Persisting the session (rather than holding it in memory) is what lets a streak, a daily
 * total and an interrupted session all be reconstructed after the process is killed.
 */
@Entity(
    tableName = "learning_sessions",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId", "startedAt"]),
        Index(value = ["userId", "status"])
    ]
)
data class LearningSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val sessionType: String = StorageValues.SessionType.REVIEW.storageValue,
    val status: String = StorageValues.SessionStatus.ACTIVE.storageValue,
    val targetCount: Int = 0,
    val reviewedCount: Int = 0,
    /** Answers rated HARD, GOOD or EASY. */
    val correctCount: Int = 0,
    val durationMillis: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null
)

/**
 * One card as presented in one session, together with the answer given.
 *
 * This is the session's answer record. The two unique indices give the two invariants that
 * matter for scoring: a word appears at most once per session, and the recorded order is
 * unambiguous, so `sequence` can be trusted as the presentation order.
 */
@Entity(
    tableName = "session_cards",
    foreignKeys = [
        ForeignKey(
            entity = LearningSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocabularyId"],
            // RESTRICT: shared content must outlive a learner's session record.
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["sessionId", "vocabularyId"], unique = true),
        Index(value = ["sessionId", "sequence"], unique = true),
        // Leading the RESTRICT check that stops shared content being deleted while a
        // session still references it.
        Index(value = ["vocabularyId"])
    ]
)
data class SessionCardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val vocabularyId: Long,
    /** Zero-based presentation order within the session. */
    val sequence: Int,
    /** Null until answered; also null for a card shown and then skipped. */
    val rating: Int? = null,
    val responseTimeMillis: Long = 0,
    val preIntervalDays: Int = 0,
    val postIntervalDays: Int = 0,
    val preState: String = StorageValues.CardState.NEW.storageValue,
    val postState: String = StorageValues.CardState.NEW.storageValue,
    val presentedAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null
)
