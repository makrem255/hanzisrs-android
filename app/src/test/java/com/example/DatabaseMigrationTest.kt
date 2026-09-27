package com.example

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.db.AppDatabase
import com.example.data.db.migrations.MIGRATION_1_2
import com.example.data.db.migrations.MIGRATION_2_3
import com.example.data.model.StorageValues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves that upgrading from schema v1 to v2 loses no learner data.
 *
 * The v1 database is not a mock. It is built from the committed `1.json` by Room's own
 * `MigrationTestHelper`, filled with rows shaped like a real install, then migrated and
 * validated against the v2 entity definitions. `runMigrationsAndValidate` failing is Room
 * telling us the migrated schema differs from what the code expects, so this covers the
 * "does the migration actually produce the declared schema" question as well as the
 * "does the data survive" one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private val databaseName = "migration-test"

    /**
     * Builds a v1 database, fills it, migrates it to v2, and hands the result to [block].
     *
     * Every handle is closed on the way out. That is not tidiness for its own sake:
     * `createDatabase` deletes the previous file, and on Windows an open handle makes that
     * delete fail, so a leaked handle turns the next test into a confusing lock error rather
     * than a real failure.
     */
    private fun <T> withMigrated(block: (SupportSQLiteDatabase) -> T): T {
        helper.createDatabase(databaseName, 1).use { v1 ->
            seedV1(v1)
        }
        val migrated = helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2)
        return try {
            block(migrated)
        } finally {
            migrated.close()
        }
    }

    /**
     * A v1 install with two learners who both study 好, plus one word only the first has, and
     * schedules in every state the old schema allowed.
     */
    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, identifier, authType, passwordHash, displayName, token, createdAt) " +
                "VALUES (1, 'alice@example.com', 'EMAIL', 'hash-a', 'Alice', 't1', 1000)"
        )
        db.execSQL(
            "INSERT INTO users (id, identifier, authType, passwordHash, displayName, token, createdAt) " +
                "VALUES (2, 'bob@example.com', 'EMAIL', 'hash-b', 'Bob', 't2', 2000)"
        )

        // Word 1 and 4 are studied by both learners; 2 and 3 only by Alice.
        insertWord(db, 1, 1, "好", "hǎo", "good", 1, 1000)
        insertWord(db, 2, 1, "茶", "chá", "tea", 2, 1001)
        insertWord(db, 3, 1, "明", "míng", "bright", 1, 1002)
        insertWord(db, 4, 2, "好", "hǎo", "good (bob's wording)", 1, 1003)

        // Every state the old scheduler could produce.
        insertReview(db, wordId = 1, userId = 1, interval = 10, reps = 4, ease = 2.35, due = 5000, state = "REVIEW", total = 4)
        insertReview(db, wordId = 2, userId = 1, interval = 1, reps = 1, ease = 2.5, due = 4000, state = "LEARNING", total = 1)
        insertReview(db, wordId = 3, userId = 1, interval = 30, reps = 6, ease = 2.5, due = 9000, state = "MASTERED", total = 6)
        // Bob has no schedule for word 4, which the old schema permitted.
    }

    private fun insertWord(
        db: SupportSQLiteDatabase,
        id: Int,
        userId: Int,
        hanzi: String,
        pinyin: String,
        meaning: String,
        hsk: Int,
        createdAt: Int
    ) {
        db.execSQL(
            "INSERT INTO words (id, userId, hanzi, pinyin, meaning, hskLevel, radical, exampleCn, " +
                "examplePy, exampleEn, strokeJson, tags, createdAt) VALUES " +
                "($id, $userId, '${q(hanzi)}', '${q(pinyin)}', '${q(meaning)}', $hsk, 'rad', " +
                "'句子', 'juzi', 'sentence', '横', 'tag', $createdAt)"
        )
    }

    /**
     * Escapes a value for interpolation into a single-quoted SQL literal.
     *
     * SQLite has no bound parameters on `execSQL`, so these fixtures build SQL by hand. One of
     * the seeded meanings is `"good (bob's wording)"`, whose apostrophe closed the literal and
     * turned the whole seed into a syntax error — reported as `near "s": syntax error`, which
     * points at the apostrophe rather than at the fixture, and hides the fact that the v1
     * database was never built. Escaping here means the fixture reads naturally and any text
     * is safe, rather than the data being bent to suit the SQL.
     */
    private fun q(value: String): String = value.replace("'", "''")

    private fun insertReview(
        db: SupportSQLiteDatabase,
        wordId: Int,
        userId: Int,
        interval: Int,
        reps: Int,
        ease: Double,
        due: Long,
        state: String,
        total: Int
    ) {
        db.execSQL(
            "INSERT INTO srs_reviews (wordId, userId, intervalDays, repetitions, easeFactor, " +
                "dueDateMillis, lastReviewMillis, state, lastRating, totalReviews) VALUES " +
                "($wordId, $userId, $interval, $reps, $ease, $due, 999, '$state', 3, $total)"
        )
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int {
        db.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long {
        db.query(sql).use { c ->
            c.moveToFirst()
            return c.getLong(0)
        }
    }

    /**
     * Compares an expected count against a value read back as a `Long`.
     *
     * `assertEquals(3, someLong)` is a trap: whether it compiles to the `assertEquals(long, long)`
     * overload or to `assertEquals(Object, Object)` depends on overload resolution, and in the
     * boxed case a count of 3 fails against a Long of 3 with `expected: Integer<3> but was:
     * Long<3>`. That message describes a type accident as though it were a data mismatch, and it
     * did exactly that here. Converting on both sides makes the comparison unambiguous and the
     * failure message meaningful, whichever overload is chosen.
     */
    private fun assertCount(expected: Int, actual: Long, message: String? = null) {
        assertEquals(message, expected.toLong(), actual)
    }

    @Test
    fun `every v1 word survives and becomes content plus an enrolment`() = withMigrated { migrated ->
        // Four words become three shared vocabulary entries: 好 was studied by both learners
        // and is stored once, with two enrolments.
        assertEquals(3, count(migrated, "vocabulary"))
        assertEquals(4, count(migrated, "user_vocabulary"))
        // 好, 茶 and 明: three glyphs and three readings. The second 好 adds neither, because
        // both learners supplied the same reading of the same character.
        assertEquals(3, count(migrated, "characters"))
        assertEquals(3, count(migrated, "pinyin_syllables"))
        assertEquals(2, count(migrated, "users"))

        // Alice keeps her three words and Bob his one.
        assertCount(3, scalarLong(migrated, "SELECT COUNT(*) FROM user_vocabulary WHERE userId = 1"))
        assertCount(1, scalarLong(migrated, "SELECT COUNT(*) FROM user_vocabulary WHERE userId = 2"))

        // The content each learner saw is preserved verbatim.
        migrated.query(
            "SELECT meaning FROM vocabulary v JOIN characters c ON c.id = v.characterId " +
                "WHERE c.character = '好'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            // Alice's wording was written first and, as shared content, is the one that stands.
            assertEquals("good", c.getString(0))
        }
        // 茶 was inserted at HSK 2 in v1. Asserted through the level's `code`, not through an
        // `hskLevel` column: v2 replaced that column with a `levelId` foreign key, and querying
        // the old name would fail on the schema rather than on the level that was chosen.
        migrated.query(
            "SELECT lv.code FROM vocabulary v JOIN learning_levels lv ON lv.id = v.levelId " +
                "WHERE v.meaning = 'tea'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("HSK2", c.getString(0))
        }
    }

    @Test
    fun `every v1 schedule is preserved with its interval, ease and state`() = withMigrated { migrated ->
        // Three imported schedules, plus one created for Bob's 好, which v1 left unscheduled.
        // Counting only the three would let a regression that dropped the fourth pass.
        assertCount(3, scalarLong(migrated, "SELECT COUNT(*) FROM srs_state WHERE userId = 1"))
        assertEquals(4, count(migrated, "srs_state"))

        migrated.query(
            "SELECT s.intervalDays, s.repetitions, s.easeFactor, s.dueDateMillis, s.state " +
                "FROM srs_state s JOIN characters c ON c.id = " +
                "(SELECT v.characterId FROM vocabulary v WHERE v.id = s.vocabularyId) " +
                "WHERE c.character = '好' AND s.userId = 1"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(10, c.getInt(0))
            assertEquals(4, c.getInt(1))
            assertEquals(2.35, c.getDouble(2), 0.0001)
            assertEquals(5000L, c.getLong(3))
            assertEquals("REVIEW", c.getString(4))
        }

        migrated.query(
            "SELECT s.state FROM srs_state s JOIN characters c ON c.id = " +
                "(SELECT v.characterId FROM vocabulary v WHERE v.id = s.vocabularyId) " +
                "WHERE c.character = '明'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("MASTERED", c.getString(0))
        }
    }

    @Test
    fun `a word with no v1 schedule gets a new-card schedule rather than none`() = withMigrated { migrated ->
        // Bob's 好 was unenrolled in the scheduler before; it must not be invisible now.
        assertCount(1, scalarLong(migrated, "SELECT COUNT(*) FROM srs_state WHERE userId = 2"))
        migrated.query("SELECT state, totalReviews FROM srs_state WHERE userId = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(StorageValues.CardState.NEW.storageValue, c.getString(0))
            assertEquals(0, c.getInt(1))
        }
    }

    @Test
    fun `imported schedules are stamped as legacy so a later scheduler can tell them apart`() =
        withMigrated { migrated ->
        // A schedule the app computed itself is not the same evidence as one it inherited.
        assertCount(
            0,
            scalarLong(
                migrated,
                "SELECT COUNT(*) FROM srs_state WHERE schedulerVersion <> " +
                    StorageValues.SchedulerVersion.LEGACY
            )
        )
        assertCount(
            StorageValues.SchedulerVersion.LEGACY,
            scalarLong(migrated, "SELECT schedulerVersion FROM srs_state LIMIT 1")
        )
        }

    @Test
    fun `every existing learner gets a profile, preferences and a streak`() = withMigrated { migrated ->
        assertEquals(2, count(migrated, "user_profiles"))
        assertEquals(2, count(migrated, "user_preferences"))
        assertEquals(2, count(migrated, "streaks"))
        // No streak was invented for a learner who had not studied in the new model.
        assertCount(0, scalarLong(migrated, "SELECT COUNT(*) FROM streaks WHERE currentLength > 0"))
    }

    @Test
    fun `reference data is present so a migrated word always has a level`() = withMigrated { migrated ->
        assertTrue(count(migrated, "learning_levels") >= 7)
        assertTrue(count(migrated, "achievements") > 0)
        // Every migrated vocabulary row resolved to a real level.
        assertCount(
            count(migrated, "vocabulary"),
            scalarLong(migrated, "SELECT COUNT(*) FROM vocabulary WHERE levelId IS NOT NULL")
        )
    }

    @Test
    fun `the pinyin of an imported word is decomposed, not stored as one opaque string`() =
        withMigrated { migrated ->
            migrated.query(
                "SELECT syllable, toneNumber, toneMarked, initial, final FROM pinyin_syllables " +
                    "WHERE toneMarked = 'hǎo'"
            ).use { c ->
                assertTrue("the tone mark should have been parsed into a tone number", c.moveToFirst())
                assertEquals("hao", c.getString(0))
                assertEquals(3, c.getInt(1))
                assertEquals("h", c.getString(3))
                assertEquals("ao", c.getString(4))
            }
        }

    @Test
    fun `an imported example sentence is attached to the shared entry`() = withMigrated { migrated ->
        // Every v1 word carried the same sentence text, but dedup is per vocabulary entry, not
        // global: 好, 茶 and 明 are three different words and each keeps one. Only 好 is a
        // cross-learner duplicate, and that is the case the unique index has to collapse.
        assertCount(3, scalarLong(migrated, "SELECT COUNT(*) FROM example_sentences"))
        assertCount(
            1,
            scalarLong(
                migrated,
                "SELECT COUNT(*) FROM example_sentences es JOIN vocabulary v " +
                    "ON v.id = es.vocabularyId JOIN characters c ON c.id = v.characterId " +
                    "WHERE c.character = '好'"
            )
        )

        // Provenance is recorded as imported, not as curated: nobody has checked this text
        // against a reference, and the UI is expected to say so.
        migrated.query("SELECT provenance FROM example_sentences LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(StorageValues.ContentProvenance.LEGACY_IMPORT.storageValue, c.getString(0))
        }
    }

    @Test
    fun `the legacy tables are gone once their contents have been carried over`() =
        withMigrated { migrated ->
            val tables = buildList {
                migrated.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                    while (c.moveToNext()) add(c.getString(0))
                }
            }

            assertTrue("words should have been retired, found: $tables", "words" !in tables)
            assertTrue("srs_reviews should have been retired, found: $tables", "srs_reviews" !in tables)
        }

    @Test
    fun `a migrated database is readable through Room, not just by raw SQL`() {
        helper.createDatabase(databaseName, 1).use { seedV1(it) }
        // Release the helper's handle before opening the same file through Room. `AppDatabase` is
        // now v3, so the walk has to run all the way to v3 - stopping at v2 would leave Room
        // comparing the file against a schema it does not expect.
        helper.runMigrationsAndValidate(
            databaseName,
            AppDatabase.VERSION,
            true,
            *AppDatabase.MIGRATIONS
        ).close()

        // Opening through Room with the migrations registered proves the schema, the identity
        // hash and the queries all agree, which raw SQL alone would not catch.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbFile = context.getDatabasePath(databaseName)
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        try {
            val userDao = room.userDao()
            assertEquals(2, runBlocking { userDao.count() })
            assertNotNull(runBlocking { userDao.findByIdentifier("alice@example.com") })
            // Case-insensitive lookup works on migrated data, whose normalized column the
            // migration has to backfill rather than assume.
            assertNotNull(runBlocking { userDao.findByIdentifier("ALICE@EXAMPLE.COM") })
            assertEquals(3, runBlocking { room.vocabularyDao().count() })
        } finally {
            room.close()
        }
    }

    // ---- v2 to v3: strokes get a row of their own ------------------------------------------------------------------------

    /**
     * Builds a v2 database, fills it, migrates it to v3, and hands the result to [block].
     *
     * A separate helper from [withMigrated] because it starts from the committed `2.json` rather
     * than replaying v1. The 1-to-2 step has its own tests above; a fixture that had to survive
     * both to reach the thing under test would be testing the composition, and would report a
     * 1-to-2 regression as a 2-to-3 one.
     */
    private fun <T> withMigratedV3(block: (SupportSQLiteDatabase) -> T): T {
        helper.createDatabase(v3DatabaseName, 2).use { v2 ->
            seedV2(v2)
        }
        val migrated = helper.runMigrationsAndValidate(v3DatabaseName, 3, true, MIGRATION_2_3)
        return try {
            block(migrated)
        } finally {
            migrated.close()
        }
    }

    /**
     * A v2 install whose characters carry the stroke breakdown the old schema kept per reading.
     *
     * The interesting shapes are all here on purpose. 水 has a full seven-stroke blob with a
     * reading. 长 is reached by *two* vocabulary rows with *different* blobs, which is the case
     * that decides whether the migration merges them, picks one, or duplicates. 明 has an empty
     * blob, which is the case that would catch a migration inventing a stroke count.
     */
    private fun seedV2(db: SupportSQLiteDatabase) {
        // The reference data a v2 row can point at. `runMigrationsAndValidate` only checks the
        // schema, so these are the minimum rows that make the content tier coherent.
        db.execSQL(
            "INSERT INTO learning_levels (id, code, ordinal, title, description, targetWordCount) " +
                "VALUES (1, 'HSK1', 1, 'Beginner', 'Everyday words', 150)"
        )
        insertSyllable(db, 1, "shui", 3, "shuǐ", "35")
        insertSyllable(db, 2, "chang", 2, "cháng", "35")
        insertSyllable(db, 3, "zhang", 3, "zhǎng", "214")
        insertSyllable(db, 4, "ming", 2, "míng", "35")

        // 水, one reading, a full breakdown.
        insertCharacter(db, 1, "水", 0, 1000)
        insertVocabulary(db, 1, 1, 1, 1, "water", SEVEN_STROKES)

        // 长, two readings. The written form is identical either way, which is the whole reason
        // the blob is being moved up to the character.
        insertCharacter(db, 2, "长", 0, 1001)
        insertVocabulary(db, 2, 2, 1, 1, "long", FOUR_STROKES_FIRST)
        insertVocabulary(db, 3, 2, 3, 1, "to grow", FOUR_STROKES_SECOND)

        // 明, with nothing known about its strokes. `strokeCount` stays 0 and the blob is blank.
        insertCharacter(db, 3, "明", 0, 1002)
        insertVocabulary(db, 4, 3, 4, 1, "bright", "")
    }

    private fun insertSyllable(
        db: SupportSQLiteDatabase,
        id: Int,
        syllable: String,
        tone: Int,
        marked: String,
        contour: String
    ) {
        db.execSQL(
            "INSERT INTO pinyin_syllables (id, syllable, toneNumber, toneMarked, initial, final, " +
                "toneContour, createdAt) VALUES ($id, '$syllable', $tone, '$marked', '', '', " +
                "'$contour', 1)"
        )
    }

    private fun insertCharacter(
        db: SupportSQLiteDatabase,
        id: Int,
        character: String,
        strokeCount: Int,
        createdAt: Int
    ) {
        db.execSQL(
            "INSERT INTO characters (id, character, codePoint, strokeCount, radical, createdAt) " +
                "VALUES ($id, '${q(character)}', ${character.codePointAt(0)}, $strokeCount, 'rad', $createdAt)"
        )
    }

    private fun insertVocabulary(
        db: SupportSQLiteDatabase,
        id: Int,
        characterId: Int,
        pinyinId: Int,
        levelId: Int,
        meaning: String,
        strokeJson: String
    ) {
        db.execSQL(
            "INSERT INTO vocabulary (id, characterId, pinyinId, levelId, meaning, partOfSpeech, " +
                "strokeJson, tags, frequencyRank, provenance, isVerified, createdAt) VALUES " +
                "($id, $characterId, $pinyinId, $levelId, '${q(meaning)}', '', " +
                "'${q(strokeJson)}', '', 0, 'UNKNOWN', 0, 1)"
        )
    }

    /** Reads every stroke of one character back, in the order the table stores them. */
    private fun strokesOf(db: SupportSQLiteDatabase, character: String): List<Triple<Int, String, String>> =
        buildList {
            db.query(
                "SELECT s.position, s.nameCn, s.namePinyin FROM character_strokes s " +
                    "JOIN characters c ON c.id = s.characterId " +
                    "WHERE c.character = '${q(character)}' ORDER BY s.position ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getInt(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

    @Test
    fun `a stroke breakdown becomes one row per stroke, in the order it was written`() =
        withMigratedV3 { migrated ->
            val strokes = strokesOf(migrated, "水")

            assertEquals("every stroke in the blob should have a row", 7, strokes.size)
            // Positions are 1-based and dense, so `ORDER BY position` is the writing order and a
            // gap in it would silently reorder the sequence.
            assertEquals((1..7).toList(), strokes.map { it.first })
            assertEquals("丶", strokes[0].second)
            assertEquals("Diǎn", strokes[0].third)
            assertEquals("一", strokes[6].second)
            assertEquals("Héng", strokes[6].third)
        }

    @Test
    fun `a character with no stroke data gets no strokes rather than a guess`() =
        withMigratedV3 { migrated ->
            // 明's blob was blank. Storing rows anyway would claim strokes exist; storing a count
            // would claim how many. Both are statements nobody made.
            assertEquals(emptyList<Triple<Int, String, String>>(), strokesOf(migrated, "明"))
            assertCount(
                0,
                scalarLong(
                    migrated,
                    "SELECT strokeCount FROM characters WHERE character = '明'"
                )
            )
        }

    @Test
    fun `a character reached by two readings gets one stroke sequence, not two`() =
        withMigratedV3 { migrated ->
            // 长 is cháng and zhǎng. v2 let each reading carry its own blob; v3 has one sequence
            // for the written form. Duplicating them would leave two answers to "how is this
            // character written", and merging them would mean inventing a stroke list.
            val strokes = strokesOf(migrated, "长")

            assertCount(
                1,
                scalarLong(
                    migrated,
                    "SELECT COUNT(DISTINCT s.characterId) FROM character_strokes s " +
                        "JOIN characters c ON c.id = s.characterId WHERE c.character = '长'"
                )
            )
            assertEquals(FOUR_STROKES_FIRST_NAMES, strokes.map { it.second })
        }

    @Test
    fun `the vocabulary blob is left in place rather than being consumed by the move`() =
        withMigratedV3 { migrated ->
            // Not deleted: the seed and the library screen still read it, and dropping a column's
            // contents in a migration is the kind of irreversible step that has to be a separate,
            // deliberate decision rather than a side effect of moving a table.
            assertEquals(
                SEVEN_STROKES,
                scalarString(
                    migrated,
                    "SELECT strokeJson FROM vocabulary v JOIN characters c ON c.id = v.characterId " +
                        "WHERE c.character = '水'"
                )
            )
        }

    @Test
    fun `structure exists and is empty rather than null or a placeholder`() =
        withMigratedV3 { migrated ->
            val structures = buildList {
                migrated.query("SELECT structure FROM characters ORDER BY id ASC").use { c ->
                    while (c.moveToNext()) add(c.getString(0))
                }
            }

            assertEquals(3, structures.size)
            assertTrue(
                "an empty string is the honest unknown; a placeholder would be a false claim",
                structures.all { it.isEmpty() }
            )
        }

    @Test
    fun `the whole chain from v1 to v3 runs and validates`() {
        helper.createDatabase(chainDatabaseName, 1).use { seedV1(it) }
        val migrated = helper.runMigrationsAndValidate(
            chainDatabaseName,
            3,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3
        )
        try {
            // The v1 fixture's single-stroke `横` blob has to have reached the new table, which
            // is the only thing that proves the two steps compose.
            assertCount(3, scalarLong(migrated, "SELECT COUNT(*) FROM character_strokes"))
            assertCount(3, scalarLong(migrated, "SELECT COUNT(*) FROM characters"))
        } finally {
            migrated.close()
        }
    }

    private fun scalarString(db: SupportSQLiteDatabase, sql: String): String {
        db.query(sql).use { c ->
            c.moveToFirst()
            return c.getString(0)
        }
    }

    private companion object {
        // Real stroke sequences, so the parser is exercised on the shape production data
        // actually has rather than on a shape invented for the test.
        private const val SEVEN_STROKES =
            "丶 (Diǎn), 丶 (Diǎn), 丿 (Piǎo), 丶 (Diǎn), 亅 (Héng Gōu), ㇏ (Wàn Gōu), 一 (Héng)"
        private const val FOUR_STROKES_FIRST = "一 (Héng), 丿 (Piǎo), 丨 (Shù), ㇏ (Wàn Gōu)"
        private const val FOUR_STROKES_SECOND = "一 (Héng), 丿 (Piǎo), 丨 (Shù), 乀 (Wàn Gōu)"
        private val FOUR_STROKES_FIRST_NAMES = listOf("一", "丿", "丨", "㇏")

        /** A separate file per start version, so the tests cannot tread on each other's handles. */
        private const val v3DatabaseName = "migration-test-v3"
        private const val chainDatabaseName = "migration-test-chain"
    }
}
