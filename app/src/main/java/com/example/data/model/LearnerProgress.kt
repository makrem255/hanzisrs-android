package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One vocabulary entry as it exists for one learner.
 *
 * This row replaced per-user copies of the word itself. Content lives once in
 * [VocabularyEntity] and is shared; everything learner-specific — when it was added, why,
 * whether it is starred, the learner's own note — lives here. That split is what makes
 * "two learners studying the same word" cost one content row instead of two.
 *
 * The unique key `(userId, vocabularyId)` is the isolation boundary: a learner can hold a
 * given entry at most once, and this is the row every other per-learner table hangs off.
 */
@Entity(
    tableName = "user_vocabulary",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocabularyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // The unique key on the enrolment itself, and the composite key that
        // SrsStateEntity's foreign key targets, so the database can prove that a scheduling
        // row's user, enrolment and content all agree.
        Index(value = ["userId", "id", "vocabularyId"], unique = true),
        Index(value = ["userId", "vocabularyId"], unique = true),
        Index(value = ["userId"]),
        Index(value = ["vocabularyId"])
    ]
)
data class UserVocabularyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val vocabularyId: Long,
    val source: String = StorageValues.VocabularySource.MANUAL.storageValue,
    val status: String = StorageValues.EnrollmentStatus.ACTIVE.storageValue,
    val customNote: String = "",
    val isStarred: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    /** Last time the learner opened the detail view; null if never opened. */
    val lastOpenedAt: Long? = null
)

/**
 * The scheduler's current position for one card of one learner.
 *
 * Mutable by design: exactly one row per enrolment, overwritten on every rating. The full
 * history is in [ReviewLogEntity]; this row is the hot, index-light projection the due queue
 * reads, so the common query never touches the log.
 *
 * The composite primary key `(userId, userVocabularyId)` is the reason the due query can be
 * served by `(userId, dueDateMillis)` alone.
 *
 * The composite foreign key into `user_vocabulary (userId, id, vocabularyId)` is the important
 * one. It makes three facts unwriteable rather than merely documented:
 *  - a scheduling row cannot exist for a word the learner has not enrolled in;
 *  - its `userId` cannot name a different learner than the enrolment's;
 *  - the denormalised `vocabularyId` cannot drift from the enrolment's.
 *
 * It also makes the cascade work in the right direction: removing a word from a learner's
 * collection deletes the schedule with it, while `review_log` — which points at the shared
 * content, not the enrolment — is deliberately left alone, so a learner's history survives
 * them dropping a word.
 */
@Entity(
    tableName = "srs_state",
    primaryKeys = ["userId", "userVocabularyId"],
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = UserVocabularyEntity::class,
            parentColumns = ["userId", "id", "vocabularyId"],
            childColumns = ["userId", "userVocabularyId", "vocabularyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // Serves the due queue: "my cards due at or before now, soonest first".
        Index(value = ["userId", "dueDateMillis"]),
        Index(value = ["userId", "state"]),
        Index(value = ["vocabularyId"])
        // No index on (userId, userVocabularyId, vocabularyId) is declared, and Room's
        // annotation processor will say so. It is already covered: the composite primary key
        // is a strict prefix of those columns and is unique, so a cascade deleting an
        // enrolment finds at most one child through it. A second index here would be
        // redundant storage on the table the due queue reads most often.
    ]
)
data class SrsStateEntity(
    val userId: Long,
    /** The `user_vocabulary` row this schedule belongs to; also the learner-scoped handle. */
    val userVocabularyId: Long,
    /** Denormalised from the enrolment so the due queue needs no second hop. */
    val vocabularyId: Long,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val easeFactor: Double = 2.5,
    val dueDateMillis: Long = System.currentTimeMillis(),
    val lastReviewMillis: Long = 0L,
    val state: String = StorageValues.CardState.NEW.storageValue,
    /** 0 = never rated, otherwise 1-4 matching [com.example.data.srs.SrsRating]. */
    val lastRating: Int = 0,
    /** Times the card fell back to a learning step. Feeds the retention estimate. */
    val lapses: Int = 0,
    val totalReviews: Int = 0,
    /** Which algorithm wrote this row; see [StorageValues.SchedulerVersion]. */
    val schedulerVersion: Int = StorageValues.SchedulerVersion.SM2,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One immutable record of one graded answer.
 *
 * Append-only: the DAO exposes inserts and reads, never updates or deletes, so this table is
 * the trustworthy input for retention analysis and for a personalised scheduler. Both
 * `srs_state` and the log are written in the same transaction, so they cannot disagree.
 */
@Entity(
    tableName = "review_log",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VocabularyEntity::class,
            parentColumns = ["id"],
            childColumns = ["vocabularyId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = LearningSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            // SET NULL: a review stays valid after its session row is pruned.
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["userId", "reviewedAt"]),
        Index(value = ["vocabularyId", "reviewedAt"]),
        Index(value = ["sessionId"])
    ]
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long,
    val vocabularyId: Long,
    val sessionId: Long? = null,
    val reviewedAt: Long,
    /** 1-4 matching [com.example.data.srs.SrsRating]. */
    val rating: Int,
    val previousIntervalDays: Int = 0,
    val newIntervalDays: Int = 0,
    val previousEaseFactor: Double = 2.5,
    val newEaseFactor: Double = 2.5,
    val previousState: String = StorageValues.CardState.NEW.storageValue,
    val newState: String = StorageValues.CardState.NEW.storageValue,
    /** Milliseconds between the card becoming due and being answered; the FSRS stability input. */
    val elapsedMillis: Long = 0,
    val responseTimeMillis: Long = 0,
    val schedulerVersion: Int = StorageValues.SchedulerVersion.SM2
)
