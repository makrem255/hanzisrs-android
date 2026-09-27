package com.example.data.model

/**
 * The closed vocabularies for every column this database keeps as TEXT.
 *
 * Room's `@Entity` DSL can express NOT NULL, foreign keys and unique indices, but it
 * cannot express CHECK constraints, so SQLite would happily accept any string in these
 * columns. Declaring the legal values in one place lets the write path reject bad data
 * with a typed error ([com.example.data.repository.Validation]) instead of letting a
 * typo become an unreadable row, and gives readers a single place to map storage text
 * back to a closed set.
 *
 * Each `fromStorage` is total: an unrecognised stored value is reported as `null` rather
 * than silently mapped to a default, so corrupt or stale data is visible instead of
 * being disguised as a valid state.
 */
object StorageValues {

    /** How a learner proves who they are. */
    enum class AuthType(val storageValue: String) {
        EMAIL("EMAIL"),
        PHONE("PHONE"),
        ANONYMOUS("ANONYMOUS");

        companion object {
            fun fromStorage(value: String?): AuthType? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /** Where a piece of lexical content came from, so the UI can be honest about trust. */
    enum class ContentProvenance(val storageValue: String) {
        /** Authored and checked by a human in this project. */
        CURATED("CURATED"),
        /** Produced by the on-device AI generation path and not yet confirmed. */
        AI_GENERATED("AI_GENERATED"),
        /** Imported from an external dataset. */
        IMPORTED("IMPORTED"),
        /** Migrated from a build that predates provenance tracking. */
        LEGACY_IMPORT("LEGACY_IMPORT"),
        UNKNOWN("UNKNOWN");

        companion object {
            fun fromStorage(value: String?): ContentProvenance? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /**
     * Scheduling state of one card for one learner.
     *
     * The storage text must stay identical to the literals emitted by
     * [com.example.data.srs.SrsAlgorithm]; `SrsCardStateContractTest` enforces that.
     */
    enum class CardState(val storageValue: String) {
        NEW("NEW"),
        LEARNING("LEARNING"),
        REVIEW("REVIEW"),
        MASTERED("MASTERED");

        companion object {
            fun fromStorage(value: String?): CardState? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /** How a word entered a learner's personal collection. */
    enum class VocabularySource(val storageValue: String) {
        MANUAL("MANUAL"),
        AI_GENERATED("AI_GENERATED"),
        STARTER("STARTER"),
        IMPORTED("IMPORTED");

        companion object {
            fun fromStorage(value: String?): VocabularySource? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /** Whether a learner still wants a word in their rotation. */
    enum class EnrollmentStatus(val storageValue: String) {
        ACTIVE("ACTIVE"),
        /** Kept for history but excluded from due queues. */
        SUSPENDED("SUSPENDED");

        companion object {
            fun fromStorage(value: String?): EnrollmentStatus? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /** What a study session was for. */
    enum class SessionType(val storageValue: String) {
        REVIEW("REVIEW"),
        LEARN("LEARN"),
        MIXED("MIXED");

        companion object {
            fun fromStorage(value: String?): SessionType? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /** Lifecycle of a study session. */
    enum class SessionStatus(val storageValue: String) {
        ACTIVE("ACTIVE"),
        COMPLETED("COMPLETED"),
        ABANDONED("ABANDONED");

        companion object {
            fun fromStorage(value: String?): SessionStatus? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /** UI theme preference, kept in the database rather than global settings. */
    enum class ThemeMode(val storageValue: String) {
        SYSTEM("SYSTEM"),
        LIGHT("LIGHT"),
        DARK("DARK");

        companion object {
            fun fromStorage(value: String?): ThemeMode? = entries.firstOrNull { it.storageValue == value }
        }
    }

    /**
     * Identifier of the algorithm that last wrote a scheduling row.
     *
     * Rows written by the v1 to v2 migration are stamped [LEGACY] so a future scheduler
     * can tell an imported schedule it has never observed from one it computed itself.
     */
    object SchedulerVersion {
        const val LEGACY = 0
        const val SM2 = 1
    }
}
