package com.example.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.db.seed.ReferenceData
import com.example.data.model.StorageValues
import com.example.data.model.UserPreferenceEntity
import com.example.data.srs.PinyinAnalyzer

/**
 * The v1 to v2 step: split two wide per-user tables into a shared content tier and a
 * per-learner tier.
 *
 * v1 stored one row per learner per word, with the glyph, the reading, the meaning, the
 * example and the schedule all in the same table. Two learners studying 好 therefore held two
 * identical copies of it, and no foreign key stopped a schedule from referring to another
 * learner's word. v2 keeps every one of those values, but stores the parts that are the same
 * for everyone exactly once.
 *
 * Two rules govern the code below.
 *
 * **Nothing is discarded.** Every column of every v1 row has somewhere to land. Where v1 stored
 * something the new model cannot represent honestly — a stroke count that was never really a
 * count, a study history that only ever kept the latest review per card — the value is left out
 * rather than reconstructed, and a comment says which. An empty history is visibly empty; a
 * fabricated one is not.
 *
 * **The DDL is not written by hand.** Room validates a migrated database against the entity
 * declarations and refuses to open it on any difference in a column, index or foreign key, so
 * the statements below are copies of the `createSql` values in
 * `app/schemas/com.example.data.db.AppDatabase/2.json`, which the annotation processor
 * generates from those same entities. Change a table and regenerate the schema and update this
 * file in the same commit; `DatabaseMigrationTest` fails loudly if the two drift apart.
 *
 * Statements run in foreign-key-safe order — parents before children — and the two legacy
 * tables are dropped last, once nothing refers to them.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 0. The scratch table, before the permanent structure: step 5 needs to write to it
        //    while it is resolving the permanent tables.
        db.execSQL(CREATE_LEGACY_WORD_MAP)

        // 1. Structure. `users` is deliberately absent: it already exists and is altered in
        //    place in step 2, which has to come before the indices in step 3. One of those
        //    indices is on `users.identifierNormalized`, a column that step 2 is what adds,
        //    so creating it any earlier would fail on a column which does not exist yet.
        CREATE_TABLES.forEach(db::execSQL)

        // 2. The three columns v2 added to `users`.
        addAndBackfillUserColumns(db)

        // 3. Indices, now that every column they name exists.
        CREATE_INDICES.forEach(db::execSQL)

        // 4. Reference data, before any row can refer to it. `vocabulary.levelId` is NOT NULL
        //    with a RESTRICT foreign key, so a word cannot be stored without a level.
        ReferenceData.install(db)

        // 5. The one-per-learner rows a v1 account was entitled to but never had.
        scaffoldPerUserRows(db)

        // 6. `words` becomes shared content plus per-learner enrolments, recording which
        //    enrolment each v1 word turned into.
        migrateWords(db)

        // 7. `srs_reviews` becomes scheduling state, joined through that mapping.
        migrateSchedules(db)

        // 8. Retire the legacy tables and the scratch mapping, children first.
        db.execSQL("DROP TABLE IF EXISTS `srs_reviews`")
        db.execSQL("DROP TABLE IF EXISTS `words`")
        db.execSQL("DROP TABLE IF EXISTS `temp.legacy_word_map`")
    }
}

// ---------------------------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------------------------

/**
 * A scratch table recording where each v1 `words.id` ended up.
 *
 * This exists because the two content tables are keyed by *meaning*, not by the v1 row id: 好
 * entered by two learners becomes one `characters` row, and the v1 ids of those two rows are
 * then indistinguishable by content. Re-deriving the link afterwards would mean guessing — the
 * pinyin column had to be normalised to reach a `pinyin_syllables` row, so `w.pinyin = toneMarked`
 * is not a reliable join. Recording the link while it is known is exact.
 *
 * A temp table rather than a real one: it lives on this connection only, and `sqlite_master`
 * does not list temp tables, so Room's post-migration schema check cannot see it.
 */
private const val CREATE_LEGACY_WORD_MAP =
    "CREATE TEMP TABLE IF NOT EXISTS `legacy_word_map` (" +
        "`wordId` INTEGER PRIMARY KEY NOT NULL, " +
        "`enrollmentId` INTEGER NOT NULL, " +
        "`vocabularyId` INTEGER NOT NULL)"

private val CREATE_TABLES = listOf(
    "CREATE TABLE IF NOT EXISTS `learning_levels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `code` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `targetWordCount` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `characters` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `character` TEXT NOT NULL, `codePoint` INTEGER NOT NULL, `strokeCount` INTEGER NOT NULL, `radical` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `pinyin_syllables` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `syllable` TEXT NOT NULL, `toneNumber` INTEGER NOT NULL, `toneMarked` TEXT NOT NULL, `initial` TEXT NOT NULL, `final` TEXT NOT NULL, `toneContour` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `vocabulary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `characterId` INTEGER NOT NULL, `pinyinId` INTEGER NOT NULL, `levelId` INTEGER NOT NULL, `meaning` TEXT NOT NULL, `partOfSpeech` TEXT NOT NULL, `strokeJson` TEXT NOT NULL, `tags` TEXT NOT NULL, `frequencyRank` INTEGER NOT NULL, `provenance` TEXT NOT NULL, `isVerified` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`characterId`) REFERENCES `characters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`pinyinId`) REFERENCES `pinyin_syllables`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`levelId`) REFERENCES `learning_levels`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
    "CREATE TABLE IF NOT EXISTS `example_sentences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vocabularyId` INTEGER NOT NULL, `sentenceCn` TEXT NOT NULL, `sentencePinyin` TEXT NOT NULL, `sentenceEn` TEXT NOT NULL, `provenance` TEXT NOT NULL, `isVerified` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`vocabularyId`) REFERENCES `vocabulary`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `learning_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `levelId` INTEGER NOT NULL, `vocabularyId` INTEGER NOT NULL, `unit` INTEGER NOT NULL, `position` INTEGER NOT NULL, `difficulty` INTEGER NOT NULL, FOREIGN KEY(`levelId`) REFERENCES `learning_levels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`vocabularyId`) REFERENCES `vocabulary`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `achievements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `code` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `tier` INTEGER NOT NULL, `iconKey` TEXT NOT NULL, `metricKey` TEXT NOT NULL, `thresholdValue` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
    "CREATE TABLE IF NOT EXISTS `user_profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `avatarSeed` TEXT NOT NULL, `timezoneId` TEXT NOT NULL, `locale` TEXT NOT NULL, `bio` TEXT NOT NULL, `targetExamEpochDay` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `user_preferences` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `themeMode` TEXT NOT NULL, `ttsSpeed` REAL NOT NULL, `dailyNewWordLimit` INTEGER NOT NULL, `dailyReviewLimit` INTEGER NOT NULL, `remindersEnabled` INTEGER NOT NULL, `reminderHour` INTEGER NOT NULL, `showPinyin` INTEGER NOT NULL, `showStrokeOrder` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `user_vocabulary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `vocabularyId` INTEGER NOT NULL, `source` TEXT NOT NULL, `status` TEXT NOT NULL, `customNote` TEXT NOT NULL, `isStarred` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `lastOpenedAt` INTEGER, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`vocabularyId`) REFERENCES `vocabulary`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `srs_state` (`userId` INTEGER NOT NULL, `userVocabularyId` INTEGER NOT NULL, `vocabularyId` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `repetitions` INTEGER NOT NULL, `easeFactor` REAL NOT NULL, `dueDateMillis` INTEGER NOT NULL, `lastReviewMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `lastRating` INTEGER NOT NULL, `lapses` INTEGER NOT NULL, `totalReviews` INTEGER NOT NULL, `schedulerVersion` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`, `userVocabularyId`), FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`userId`, `userVocabularyId`, `vocabularyId`) REFERENCES `user_vocabulary`(`userId`, `id`, `vocabularyId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `learning_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `sessionType` TEXT NOT NULL, `status` TEXT NOT NULL, `targetCount` INTEGER NOT NULL, `reviewedCount` INTEGER NOT NULL, `correctCount` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `session_cards` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `vocabularyId` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, `rating` INTEGER, `responseTimeMillis` INTEGER NOT NULL, `preIntervalDays` INTEGER NOT NULL, `postIntervalDays` INTEGER NOT NULL, `preState` TEXT NOT NULL, `postState` TEXT NOT NULL, `presentedAt` INTEGER NOT NULL, `answeredAt` INTEGER, FOREIGN KEY(`sessionId`) REFERENCES `learning_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`vocabularyId`) REFERENCES `vocabulary`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
    "CREATE TABLE IF NOT EXISTS `review_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `vocabularyId` INTEGER NOT NULL, `sessionId` INTEGER, `reviewedAt` INTEGER NOT NULL, `rating` INTEGER NOT NULL, `previousIntervalDays` INTEGER NOT NULL, `newIntervalDays` INTEGER NOT NULL, `previousEaseFactor` REAL NOT NULL, `newEaseFactor` REAL NOT NULL, `previousState` TEXT NOT NULL, `newState` TEXT NOT NULL, `elapsedMillis` INTEGER NOT NULL, `responseTimeMillis` INTEGER NOT NULL, `schedulerVersion` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`vocabularyId`) REFERENCES `vocabulary`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`sessionId`) REFERENCES `learning_sessions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "CREATE TABLE IF NOT EXISTS `daily_stats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `dateEpochDay` INTEGER NOT NULL, `reviewsCompleted` INTEGER NOT NULL, `correctReviews` INTEGER NOT NULL, `againReviews` INTEGER NOT NULL, `newWordsIntroduced` INTEGER NOT NULL, `newWordsMastered` INTEGER NOT NULL, `studyMillis` INTEGER NOT NULL, `sessionCount` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `streaks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `currentLength` INTEGER NOT NULL, `longestLength` INTEGER NOT NULL, `totalActiveDays` INTEGER NOT NULL, `lastStudyEpochDay` INTEGER, `lastStudyMillis` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE TABLE IF NOT EXISTS `user_achievements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, `achievementId` INTEGER NOT NULL, `progressValue` INTEGER NOT NULL, `unlockedAt` INTEGER, `seenAt` INTEGER, FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`achievementId`) REFERENCES `achievements`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
)

private val CREATE_INDICES = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_levels_code` ON `learning_levels` (`code`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_levels_ordinal` ON `learning_levels` (`ordinal`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_characters_character` ON `characters` (`character`)",
    "CREATE INDEX IF NOT EXISTS `index_characters_codePoint` ON `characters` (`codePoint`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pinyin_syllables_syllable_toneNumber` ON `pinyin_syllables` (`syllable`, `toneNumber`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_vocabulary_characterId_pinyinId` ON `vocabulary` (`characterId`, `pinyinId`)",
    "CREATE INDEX IF NOT EXISTS `index_vocabulary_levelId` ON `vocabulary` (`levelId`)",
    "CREATE INDEX IF NOT EXISTS `index_vocabulary_characterId` ON `vocabulary` (`characterId`)",
    "CREATE INDEX IF NOT EXISTS `index_vocabulary_pinyinId` ON `vocabulary` (`pinyinId`)",
    "CREATE INDEX IF NOT EXISTS `index_vocabulary_meaning` ON `vocabulary` (`meaning`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_example_sentences_vocabularyId_sentenceCn` ON `example_sentences` (`vocabularyId`, `sentenceCn`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_items_levelId_position` ON `learning_items` (`levelId`, `position`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_items_levelId_vocabularyId` ON `learning_items` (`levelId`, `vocabularyId`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_items_vocabularyId` ON `learning_items` (`vocabularyId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_achievements_code` ON `achievements` (`code`)",
    "CREATE INDEX IF NOT EXISTS `index_achievements_metricKey` ON `achievements` (`metricKey`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_users_identifierNormalized` ON `users` (`identifierNormalized`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_profiles_userId` ON `user_profiles` (`userId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_preferences_userId` ON `user_preferences` (`userId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_vocabulary_userId_id_vocabularyId` ON `user_vocabulary` (`userId`, `id`, `vocabularyId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_vocabulary_userId_vocabularyId` ON `user_vocabulary` (`userId`, `vocabularyId`)",
    "CREATE INDEX IF NOT EXISTS `index_user_vocabulary_userId` ON `user_vocabulary` (`userId`)",
    "CREATE INDEX IF NOT EXISTS `index_user_vocabulary_vocabularyId` ON `user_vocabulary` (`vocabularyId`)",
    "CREATE INDEX IF NOT EXISTS `index_srs_state_userId_dueDateMillis` ON `srs_state` (`userId`, `dueDateMillis`)",
    "CREATE INDEX IF NOT EXISTS `index_srs_state_userId_state` ON `srs_state` (`userId`, `state`)",
    "CREATE INDEX IF NOT EXISTS `index_srs_state_vocabularyId` ON `srs_state` (`vocabularyId`)",
    "CREATE INDEX IF NOT EXISTS `index_review_log_userId_reviewedAt` ON `review_log` (`userId`, `reviewedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_review_log_vocabularyId_reviewedAt` ON `review_log` (`vocabularyId`, `reviewedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_review_log_sessionId` ON `review_log` (`sessionId`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_sessions_userId_startedAt` ON `learning_sessions` (`userId`, `startedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_sessions_userId_status` ON `learning_sessions` (`userId`, `status`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_cards_sessionId_vocabularyId` ON `session_cards` (`sessionId`, `vocabularyId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_cards_sessionId_sequence` ON `session_cards` (`sessionId`, `sequence`)",
    "CREATE INDEX IF NOT EXISTS `index_session_cards_vocabularyId` ON `session_cards` (`vocabularyId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_stats_userId_dateEpochDay` ON `daily_stats` (`userId`, `dateEpochDay`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_streaks_userId` ON `streaks` (`userId`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_achievements_userId_achievementId` ON `user_achievements` (`userId`, `achievementId`)",
    "CREATE INDEX IF NOT EXISTS `index_user_achievements_userId` ON `user_achievements` (`userId`)",
    "CREATE INDEX IF NOT EXISTS `index_user_achievements_achievementId` ON `user_achievements` (`achievementId`)",
)


// ---------------------------------------------------------------------------------------------
// users
// ---------------------------------------------------------------------------------------------

/**
 * The identifier v1 reserved for a guest.
 *
 * A guest is no longer identified by a fabricated email address; it is a row whose `authType`
 * is `ANONYMOUS`. That reserved address is the only trace a v1 guest leaves behind, so it is
 * what the migration matches on.
 */
private const val V1_GUEST_IDENTIFIER = "guest@hanzisrs.com"

/**
 * Adds the three columns v2 introduced to `users`, then fills them.
 *
 * `ALTER TABLE ADD COLUMN` rather than the usual create-copy-drop-rename rebuild, because the
 * table already holds rows that `user_profiles`, `user_vocabulary`, `srs_state` and everything
 * else downstream points at. Keeping the existing `id` values and the `AUTOINCREMENT`
 * high-water mark means no id has to be remapped anywhere. SQLite allows a NOT NULL column to
 * be added as long as it has a default, and Room's schema comparison only rejects a default the
 * entity itself declares, which these do not.
 */
private fun addAndBackfillUserColumns(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `users` ADD COLUMN `identifierNormalized` TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE `users` ADD COLUMN `isGuest` INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE `users` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")

    // The uniqueness rule is case-insensitive and v1 enforced it in Kotlin with no index
    // behind it, so the normalized value is what the unique index is taken out on.
    db.execSQL("UPDATE `users` SET `identifierNormalized` = lower(trim(`identifier`))")
    db.execSQL("UPDATE `users` SET `updatedAt` = `createdAt`")

    // A v1 guest was a normal account holding a reserved address. Record that as what it was.
    // The password hash is left alone: destroying a learner's credentials during an upgrade is
    // not a decision this migration gets to make.
    db.execSQL(
        "UPDATE `users` SET `isGuest` = 1, `authType` = ? WHERE lower(trim(`identifier`)) = ?",
        arrayOf<Any>(StorageValues.AuthType.ANONYMOUS.storageValue, V1_GUEST_IDENTIFIER)
    )

    // v1 allowed two accounts whose addresses differed only in casing, and the unique index on
    // `identifierNormalized` would refuse to be created. Dropping an account to satisfy a constraint is not an
    // acceptable way to migrate someone's data, so every colliding row except the lowest-id one
    // — which keeps the plain address — gains a suffix built from its own id, which is unique by
    // construction. The consequence is recorded here rather than hidden: such an account can no
    // longer be signed into with the address as typed. That is a property of the data, not a
    // choice this migration could avoid.
    db.execSQL(
        """
        UPDATE `users`
        SET `identifierNormalized` = lower(trim(`identifier`)) || '#' || `id`
        WHERE `id` IN (
            SELECT u.`id` FROM `users` u
            WHERE u.`id` > (
                SELECT MIN(v.`id`) FROM `users` v
                WHERE lower(trim(v.`identifier`)) = lower(trim(u.`identifier`))
            )
        )
        """.trimIndent()
    )
}

// ---------------------------------------------------------------------------------------------
// Per-learner scaffolding
// ---------------------------------------------------------------------------------------------

/**
 * Creates the profile, preferences and streak every account is entitled to.
 *
 * The streak is left at zero rather than back-filled, because v1 never recorded which days a
 * learner studied — only the current schedule for each card. A streak length derived from that
 * would be a guess presented as a fact. The row is still created, because the home screen reads
 * it unconditionally and a missing row would be a different kind of wrong.
 *
 * `user_preferences` gets the entity's own defaults rather than values chosen here, so a
 * migrated learner and a newly registered one are configured identically.
 */
private fun scaffoldPerUserRows(db: SupportSQLiteDatabase) {
    val now = System.currentTimeMillis()
    // The defaults are read off the entity rather than restated here, so that a migrated
    // learner and a newly registered one are configured identically even if a default changes.
    val defaults = UserPreferenceEntity(userId = 0L)
    db.query("SELECT `id`, `createdAt` FROM `users`").use { c ->
        while (c.moveToNext()) {
            val userId = c.getLong(0)
            val createdAt = c.getLong(1)
            db.execSQL(
                "INSERT OR IGNORE INTO `user_profiles` (`userId`, `avatarSeed`, `timezoneId`, " +
                    "`locale`, `bio`, `targetExamEpochDay`, `createdAt`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, NULL, ?, ?)",
                arrayOf<Any>(userId, "", "UTC", "en", "", createdAt, now)
            )
            db.execSQL(
                "INSERT OR IGNORE INTO `user_preferences` (`userId`, `themeMode`, `ttsSpeed`, " +
                    "`dailyNewWordLimit`, `dailyReviewLimit`, `remindersEnabled`, `reminderHour`, " +
                    "`showPinyin`, `showStrokeOrder`, `updatedAt`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    userId, defaults.themeMode, defaults.ttsSpeed, defaults.dailyNewWordLimit,
                    defaults.dailyReviewLimit, if (defaults.remindersEnabled) 1 else 0,
                    defaults.reminderHour, if (defaults.showPinyin) 1 else 0,
                    if (defaults.showStrokeOrder) 1 else 0, now
                )
            )
            db.execSQL(
                "INSERT OR IGNORE INTO `streaks` (`userId`, `currentLength`, `longestLength`, " +
                    "`totalActiveDays`, `lastStudyEpochDay`, `lastStudyMillis`, `updatedAt`) " +
                    "VALUES (?, 0, 0, 0, NULL, 0, ?)",
                arrayOf<Any>(userId, now)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// words -> content + enrolments
// ---------------------------------------------------------------------------------------------

/** The interval a never-scheduled card is given, matching what a freshly added word receives. */
private const val NEW_CARD_INTERVAL_DAYS = 1

private const val DEFAULT_EASE_FACTOR = 2.5

/**
 * Splits every v1 word into shared content, a per-learner enrolment, and the mapping between.
 *
 * The resolution order is what makes two learners converge on one row:
 *
 *  - **Characters** are keyed on the glyph, so 好 entered twice is one row.
 *  - **Readings** are keyed on `(toneless syllable, tone)`, so 长 cháng and 长 zhǎng are two
 *    readings and therefore two vocabulary entries for one glyph. That is the point of the
 *    split, and a learner who knows a character by two names can now study both.
 *  - **Vocabulary** is keyed on `(character, reading)`, so two learners who typed different
 *    glosses for the same word meet at one entry. Where the glosses differ the lower
 *    `words.id` wins, because that is the one the first learner actually wrote; a glossary
 *    entry cannot hold two glosses, and the other learner's enrolment is still created.
 *  - **Enrolments** are then made per learner, so both still have the word.
 *
 * Rows are read in `id` order and every insert is `OR IGNORE` against the unique index, so the
 * result does not depend on the order SQLite happens to scan in.
 */
private fun migrateWords(db: SupportSQLiteDatabase) {
    val levelIdByOrdinal = HashMap<Int, Long>()
    db.query("SELECT `ordinal`, `id` FROM `learning_levels`").use { c ->
        while (c.moveToNext()) levelIdByOrdinal[c.getInt(0)] = c.getLong(1)
    }
    val generalLevelId = levelIdByOrdinal[0]
        ?: error("Migration 1->2: learning_levels was not seeded, so no word can be placed")

    db.query(
        "SELECT `id`, `userId`, `hanzi`, `pinyin`, `meaning`, `hskLevel`, `radical`, " +
            "`exampleCn`, `examplePy`, `exampleEn`, `strokeJson`, `tags`, `createdAt` " +
            "FROM `words` ORDER BY `id` ASC"
    ).use { c ->
        while (c.moveToNext()) {
            val wordId = c.getLong(0)
            val userId = c.getLong(1)
            val hanzi = c.getString(2)
            val pinyin = c.getString(3)
            val meaning = c.getString(4)
            val hskLevel = c.getInt(5)
            val radical = c.getString(6)
            val exampleCn = c.getString(7)
            val examplePy = c.getString(8)
            val exampleEn = c.getString(9)
            val strokeJson = c.getString(10)
            val tags = c.getString(11)
            val createdAt = c.getLong(12)

            val characterId = resolveCharacter(db, hanzi, radical, createdAt)
            val pinyinId = resolvePinyin(db, pinyin, createdAt)
            val vocabularyId = resolveVocabulary(
                db = db,
                characterId = characterId,
                pinyinId = pinyinId,
                levelId = levelIdByOrdinal[hskLevel] ?: generalLevelId,
                meaning = meaning,
                strokeJson = strokeJson,
                tags = tags,
                createdAt = createdAt
            )
            val enrollmentId = enrol(db, userId, vocabularyId, createdAt)
            attachExample(db, vocabularyId, exampleCn, examplePy, exampleEn, createdAt)

            db.execSQL(
                "INSERT OR REPLACE INTO `legacy_word_map` (`wordId`, `enrollmentId`, `vocabularyId`) " +
                    "VALUES (?, ?, ?)",
                arrayOf<Any>(wordId, enrollmentId, vocabularyId)
            )
        }
    }
}

/**
 * Returns the id of the row for [hanzi], creating it if this is the first time it is seen.
 *
 * The code point is that of the leading character. v1 did not check that a word was a single
 * glyph and a few rows hold two, so this describes the first character rather than the whole
 * string. The alternative — refusing those rows — would delete a learner's data during an
 * upgrade, so the limitation is recorded in the column instead of acted on.
 *
 * `strokeCount` stays 0. v1's `strokeJson` held a comma-separated list of stroke *names*
 * ("点 (Diǎn), 点 (Diǎn), ..."), not geometry, and counting its separators would produce a number
 * shaped like a count without being one. 0 is the documented "unknown".
 */
private fun resolveCharacter(
    db: SupportSQLiteDatabase,
    hanzi: String,
    radical: String,
    createdAt: Long
): Long {
    db.execSQL(
        "INSERT OR IGNORE INTO `characters` (`character`, `codePoint`, `strokeCount`, `radical`, `createdAt`) " +
            "VALUES (?, ?, 0, ?, ?)",
        arrayOf<Any>(hanzi, hanzi.codePointAt(0), radical, createdAt)
    )
    return firstLong(db, "SELECT `id` FROM `characters` WHERE `character` = ?", arrayOf<Any>(hanzi))
}

/**
 * Returns the id of the reading for [pinyin], creating it if this is the first time.
 *
 * The decomposition is done in Kotlin by [PinyinAnalyzer] rather than in SQL, because a tone-mark
 * table is not something SQLite should be asked to own. Going through the same analyzer a word
 * typed today goes through is what makes an imported reading and a new one converge on the same
 * `pinyin_syllables` row instead of sitting side by side.
 */
private fun resolvePinyin(db: SupportSQLiteDatabase, pinyin: String, createdAt: Long): Long {
    val analysis = PinyinAnalyzer.analyze(pinyin)
    db.execSQL(
        "INSERT OR IGNORE INTO `pinyin_syllables` (`syllable`, `toneNumber`, `toneMarked`, " +
            "`initial`, `final`, `toneContour`, `createdAt`) VALUES (?, ?, ?, ?, ?, ?, ?)",
        arrayOf<Any>(
            analysis.syllable, analysis.toneNumber, analysis.toneMarked,
            analysis.initial, analysis.final, analysis.toneContour, createdAt
        )
    )
    return firstLong(
        db,
        "SELECT `id` FROM `pinyin_syllables` WHERE `syllable` = ? AND `toneNumber` = ?",
        arrayOf<Any>(analysis.syllable, analysis.toneNumber)
    )
}

/** Returns the shared vocabulary entry for this reading of this glyph, creating it if new. */
private fun resolveVocabulary(
    db: SupportSQLiteDatabase,
    characterId: Long,
    pinyinId: Long,
    levelId: Long,
    meaning: String,
    strokeJson: String,
    tags: String,
    createdAt: Long
): Long {
    // Provenance is recorded as imported and `isVerified` stays false: nobody has checked this
    // text against a reference, and the add-word screen is meant to say so rather than present an
    // imported gloss as curated.
    db.execSQL(
        "INSERT OR IGNORE INTO `vocabulary` (`characterId`, `pinyinId`, `levelId`, `meaning`, " +
            "`partOfSpeech`, `strokeJson`, `tags`, `frequencyRank`, `provenance`, `isVerified`, " +
            "`createdAt`) VALUES (?, ?, ?, ?, '', ?, ?, 0, ?, 0, ?)",
        arrayOf<Any>(
            characterId, pinyinId, levelId, meaning, strokeJson, tags,
            StorageValues.ContentProvenance.LEGACY_IMPORT.storageValue, createdAt
        )
    )
    return firstLong(
        db,
        "SELECT `id` FROM `vocabulary` WHERE `characterId` = ? AND `pinyinId` = ?",
        arrayOf<Any>(characterId, pinyinId)
    )
}

/** Records that [userId] has this word in their collection, and returns the enrolment id. */
private fun enrol(db: SupportSQLiteDatabase, userId: Long, vocabularyId: Long, addedAt: Long): Long {
    db.execSQL(
        "INSERT OR IGNORE INTO `user_vocabulary` (`userId`, `vocabularyId`, `source`, `status`, " +
            "`customNote`, `isStarred`, `addedAt`, `lastOpenedAt`) " +
            "VALUES (?, ?, ?, ?, '', 0, ?, NULL)",
        arrayOf<Any>(
            userId, vocabularyId,
            StorageValues.VocabularySource.IMPORTED.storageValue,
            StorageValues.EnrollmentStatus.ACTIVE.storageValue,
            addedAt
        )
    )
    return firstLong(
        db,
        "SELECT `id` FROM `user_vocabulary` WHERE `userId` = ? AND `vocabularyId` = ?",
        arrayOf<Any>(userId, vocabularyId)
    )
}

/**
 * Attaches the example sentence, if there is one, to the shared entry.
 *
 * `OR IGNORE` against the unique `(vocabularyId, sentenceCn)` index: when two learners typed the
 * same example, the word is shared and so is its example. The second learner's own translation
 * of it is the one that is lost, which is the same trade-off as the gloss above and for the same
 * reason — one shared entry, one rendering.
 */
private fun attachExample(
    db: SupportSQLiteDatabase,
    vocabularyId: Long,
    sentenceCn: String,
    sentencePy: String,
    sentenceEn: String,
    createdAt: Long
) {
    if (sentenceCn.isBlank()) return
    db.execSQL(
        "INSERT OR IGNORE INTO `example_sentences` (`vocabularyId`, `sentenceCn`, `sentencePinyin`, " +
            "`sentenceEn`, `provenance`, `isVerified`, `createdAt`) VALUES (?, ?, ?, ?, ?, 0, ?)",
        arrayOf<Any>(
            vocabularyId, sentenceCn, sentencePy, sentenceEn,
            StorageValues.ContentProvenance.LEGACY_IMPORT.storageValue, createdAt
        )
    )
}

// ---------------------------------------------------------------------------------------------
// srs_reviews -> srs_state
// ---------------------------------------------------------------------------------------------

/**
 * Carries every v1 schedule across, then gives the words that never had one a new-card schedule.
 *
 * `schedulerVersion` is stamped `LEGACY` on every row, which is the whole reason that column
 * exists: a schedule carried over by this migration is evidence about a past algorithm the app
 * has never observed, and a later scheduler has to be able to tell it apart from one it
 * computed itself.
 *
 * The second statement matters more than it looks. v1 had no foreign key from `srs_reviews` to
 * `words`, so a word could sit in a learner's collection with no schedule at all and the due
 * queue would silently never show it again. Such a card is now genuinely `NEW` and due
 * immediately, which is exactly the state a word added today would be in.
 *
 * `lapses` is 0 for every imported row: v1 did not count them, and inventing a count would
 * quietly inflate the retention estimate that feeds the next scheduler.
 */
private fun migrateSchedules(db: SupportSQLiteDatabase) {
    db.query(
        "SELECT r.`wordId`, r.`intervalDays`, r.`repetitions`, r.`easeFactor`, r.`dueDateMillis`, " +
            "r.`lastReviewMillis`, r.`state`, r.`lastRating`, r.`totalReviews` " +
            "FROM `srs_reviews` r ORDER BY r.`id` ASC"
    ).use { c ->
        while (c.moveToNext()) {
            val wordId = c.getLong(0)
            val intervalDays = c.getInt(1)
            val repetitions = c.getInt(2)
            val easeFactor = c.getDouble(3)
            val dueDateMillis = c.getLong(4)
            val lastReviewMillis = c.getLong(5)
            val lastRating = c.getInt(7)
            val totalReviews = c.getInt(8)

            // v1 wrote a state string with no closed vocabulary behind it. An unrecognised one
            // becomes NEW rather than being written through, which would break every reader that
            // expects a known state.
            val state = StorageValues.CardState.fromStorage(c.getString(6))?.storageValue
                ?: StorageValues.CardState.NEW.storageValue

            // `user_vocabulary.userId` and `.vocabularyId` come from the enrolment, not from the
            // review, so the composite foreign key into `user_vocabulary` holds by construction.
            db.execSQL(
                """
                INSERT OR IGNORE INTO `srs_state` (
                    `userId`, `userVocabularyId`, `vocabularyId`, `intervalDays`, `repetitions`,
                    `easeFactor`, `dueDateMillis`, `lastReviewMillis`, `state`, `lastRating`,
                    `lapses`, `totalReviews`, `schedulerVersion`, `updatedAt`
                )
                SELECT uv.`userId`, m.`enrollmentId`, m.`vocabularyId`, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?
                FROM `legacy_word_map` m
                JOIN `user_vocabulary` uv ON uv.`id` = m.`enrollmentId`
                WHERE m.`wordId` = ?
                """.trimIndent(),
                arrayOf<Any>(
                    intervalDays, repetitions, easeFactor, dueDateMillis, lastReviewMillis,
                    state, lastRating, totalReviews,
                    StorageValues.SchedulerVersion.LEGACY, lastReviewMillis, wordId
                )
            )
        }
    }

    val now = System.currentTimeMillis()
    db.execSQL(
        """
        INSERT OR IGNORE INTO `srs_state` (
            `userId`, `userVocabularyId`, `vocabularyId`, `intervalDays`, `repetitions`,
            `easeFactor`, `dueDateMillis`, `lastReviewMillis`, `state`, `lastRating`, `lapses`,
            `totalReviews`, `schedulerVersion`, `updatedAt`
        )
        SELECT uv.`userId`, uv.`id`, uv.`vocabularyId`, ?, 0, ?, ?, 0, ?, 0, 0, 0, ?, ?
        FROM `user_vocabulary` uv
        WHERE NOT EXISTS (
            SELECT 1 FROM `srs_state` s
            WHERE s.`userId` = uv.`userId` AND s.`userVocabularyId` = uv.`id`
        )
        """.trimIndent(),
        arrayOf<Any>(
            NEW_CARD_INTERVAL_DAYS, DEFAULT_EASE_FACTOR, now,
            StorageValues.CardState.NEW.storageValue, StorageValues.SchedulerVersion.LEGACY, now
        )
    )
}

/** Reads the first column of the first row of [sql], which the callers above have just ensured exists. */
private fun firstLong(db: SupportSQLiteDatabase, sql: String, args: Array<Any>): Long {
    db.query(sql, args).use { c ->
        check(c.moveToFirst()) { "Migration 1->2: expected a row from: $sql" }
        return c.getLong(0)
    }
}

