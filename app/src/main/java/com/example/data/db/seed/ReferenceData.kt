package com.example.data.db.seed

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AchievementEntity
import com.example.data.model.LearningLevelEntity

/**
 * The rows every install needs before any learner data can reference them.
 *
 * These live in the database rather than being hard-coded at each call site because
 * `vocabulary.levelId` is NOT NULL with a RESTRICT foreign key: a word cannot be stored
 * without a level, and the level catalogue cannot be derived from the words themselves.
 * The same reasoning applies to the achievement catalogue, which every
 * `user_achievements` row points at.
 *
 * Both sets are inserted with `INSERT OR IGNORE`, so seeding is idempotent and safe to run on
 * every launch. The identifiers are `code` and `ordinal`, not the surrogate ids, because
 * surrogate ids are assigned by SQLite and must never be written down.
 */
object ReferenceData {

    /**
     * HSK 1-6 plus a catch-all.
     *
     * `GENERAL` exists so a word with an unrecognised or missing level still has somewhere to
     * live; `ordinal` 0 keeps it first so it is the natural fallback for a nearest-level
     * lookup.
     */
    val learningLevels: List<LearningLevelEntity> = listOf(
        LearningLevelEntity(
            code = "GENERAL",
            ordinal = 0,
            title = "Unassigned",
            description = "Words not yet placed on the HSK ladder, including anything a learner adds themselves.",
            targetWordCount = 0
        ),
        LearningLevelEntity(
            code = "HSK1",
            ordinal = 1,
            title = "HSK 1",
            description = "Beginner. Everyday nouns, numbers, and simple personal information.",
            targetWordCount = 150
        ),
        LearningLevelEntity(
            code = "HSK2",
            ordinal = 2,
            title = "HSK 2",
            description = "Elementary. Basic verbs, adjectives, and routine transactions.",
            targetWordCount = 300
        ),
        LearningLevelEntity(
            code = "HSK3",
            ordinal = 3,
            title = "HSK 3",
            description = "Lower intermediate. Wider vocabulary and longer sentences.",
            targetWordCount = 600
        ),
        LearningLevelEntity(
            code = "HSK4",
            ordinal = 4,
            title = "HSK 4",
            description = "Upper intermediate. Abstract topics and written Chinese.",
            targetWordCount = 1000
        ),
        LearningLevelEntity(
            code = "HSK5",
            ordinal = 5,
            title = "HSK 5",
            description = "Advanced. Formal writing, idioms, and specialised vocabulary.",
            targetWordCount = 1800
        ),
        LearningLevelEntity(
            code = "HSK6",
            ordinal = 6,
            title = "HSK 6",
            description = "Mastery. Near-native reading and academic register.",
            targetWordCount = 2600
        )
    )

    /**
     * The badge catalogue. `metricKey` names a quantity the award pass measures, and
     * `thresholdValue` is the value at which the badge unlocks.
     *
     * The metric keys are the contract between this catalogue and the code that measures
     * progress; they are strings in the database on purpose so a badge can be added by
     * seeding a row rather than by shipping a code change.
     */
    val achievements: List<AchievementEntity> = listOf(
        AchievementEntity(
            code = "FIRST_STUDY_SESSION",
            title = "First steps",
            description = "Finish your first study session.",
            category = "MILESTONE",
            tier = 1,
            iconKey = "flag",
            metricKey = "SESSIONS_COMPLETED",
            thresholdValue = 1
        ),
        AchievementEntity(
            code = "STREAK_3",
            title = "Three in a row",
            description = "Study three days in a row.",
            category = "STREAK",
            tier = 1,
            iconKey = "local_fire_department",
            metricKey = "STREAK_DAYS",
            thresholdValue = 3
        ),
        AchievementEntity(
            code = "STREAK_7",
            title = "A full week",
            description = "Study seven days in a row.",
            category = "STREAK",
            tier = 2,
            iconKey = "local_fire_department",
            metricKey = "STREAK_DAYS",
            thresholdValue = 7
        ),
        AchievementEntity(
            code = "STREAK_30",
            title = "Habit formed",
            description = "Study thirty days in a row.",
            category = "STREAK",
            tier = 3,
            iconKey = "whatshot",
            metricKey = "STREAK_DAYS",
            thresholdValue = 30
        ),
        AchievementEntity(
            code = "WORDS_10",
            title = "Getting started",
            description = "Collect ten words.",
            category = "COLLECTION",
            tier = 1,
            iconKey = "menu_book",
            metricKey = "WORDS_COLLECTED",
            thresholdValue = 10
        ),
        AchievementEntity(
            code = "WORDS_50",
            title = "Word collector",
            description = "Collect fifty words.",
            category = "COLLECTION",
            tier = 2,
            iconKey = "library_books",
            metricKey = "WORDS_COLLECTED",
            thresholdValue = 50
        ),
        AchievementEntity(
            code = "MASTERED_10",
            title = "Ten mastered",
            description = "Bring ten words to MASTERED.",
            category = "MASTERY",
            tier = 2,
            iconKey = "workspace_premium",
            metricKey = "WORDS_MASTERED",
            thresholdValue = 10
        ),
        AchievementEntity(
            code = "REVIEWS_100",
            title = "Century",
            description = "Complete one hundred reviews.",
            category = "PRACTICE",
            tier = 2,
            iconKey = "task_alt",
            metricKey = "REVIEWS_COMPLETED",
            thresholdValue = 100
        ),
        AchievementEntity(
            code = "REVIEWS_1000",
            title = "Thousand reviews",
            description = "Complete one thousand reviews.",
            category = "PRACTICE",
            tier = 3,
            iconKey = "military_tech",
            metricKey = "REVIEWS_COMPLETED",
            thresholdValue = 1000
        )
    )

    /**
     * Writes the catalogues into a database that has just had the schema created.
     *
     * One implementation, called from two places that both need it: a fresh install, from
     * `AppDatabase`'s create callback, and a v1 to v2 migration, which cannot use DAOs because
     * the ones it would need are generated against the schema that does not exist yet. If the
     * two paths each had their own insert statements they could drift, and a migrated install
     * would quietly end up with a different curriculum from a new one.
     *
     * `INSERT OR IGNORE` against the unique indices on `code` and `ordinal`, so calling this
     * again is a no-op rather than a constraint failure.
     */
    fun install(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        // `learning_levels` has no timestamp column by design: a level is part of the
        // curriculum rather than something with a creation time.
        for (level in learningLevels) {
            db.execSQL(
                "INSERT OR IGNORE INTO `learning_levels` " +
                    "(`code`, `ordinal`, `title`, `description`, `targetWordCount`) " +
                    "VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(level.code, level.ordinal, level.title, level.description, level.targetWordCount)
            )
        }

        for (badge in achievements) {
            db.execSQL(
                "INSERT OR IGNORE INTO `achievements` (`code`, `title`, `description`, `category`, " +
                    "`tier`, `iconKey`, `metricKey`, `thresholdValue`, `isActive`, `createdAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    badge.code, badge.title, badge.description, badge.category, badge.tier,
                    badge.iconKey, badge.metricKey, badge.thresholdValue, 1, now
                )
            )
        }
    }
}
