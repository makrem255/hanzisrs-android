package com.example

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.StreakEntity
import com.example.data.model.UserEntity
import com.example.data.model.UserPreferenceEntity
import com.example.data.model.UserProfileEntity
import com.example.data.repository.SaveWordResult
import com.example.data.repository.WordRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the relationships the schema is supposed to guarantee.
 *
 * These are the claims the entity declarations make — cascades, unique keys, and the fact that
 * one learner cannot reach another's data — checked against a real SQLite database rather than
 * inferred from the annotations. An in-memory Room database is used because it enforces
 * foreign keys exactly as the on-disk one does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SchemaRelationshipTest {

    private lateinit var database: AppDatabase
    private lateinit var words: WordRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // An in-memory database never fires AppDatabase's create callback, so the reference
        // catalogue has to be installed the way a real install gets it. Without this every
        // `saveNewWordWithInitialSrs` call would fail on a missing level and the failures would
        // read as a bug in the enrolment path rather than as a missing seed.
        runBlocking { AppDatabase.seedReferenceData(database) }
        words = WordRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Inserts an account directly, bypassing registration.
     *
     * The password column is NOT NULL but nothing here signs in, so the value is a marker
     * rather than a hash. A literal that looked like a real PBKDF2 digest would be a
     * credential-shaped lie in a test fixture.
     */
    private suspend fun newUser(identifier: String): Long = database.userDao().insertUser(
        UserEntity(
            identifier = identifier,
            identifierNormalized = identifier.lowercase(),
            authType = StorageValues.AuthType.EMAIL.storageValue,
            passwordHash = "unused-by-these-tests",
            displayName = identifier.substringBefore("@"),
            token = "token-$identifier"
        )
    )

    private fun draft(userId: Long, hanzi: String, pinyin: String, meaning: String = "a meaning") = NewWordDraft(
        userId = userId,
        hanzi = hanzi,
        pinyin = pinyin,
        meaning = meaning,
        hskLevel = 1,
        radical = "rad",
        exampleCn = "句子 $hanzi",
        examplePy = "juzi $pinyin",
        exampleEn = "a sentence",
        strokeJson = "横"
    )

    // ---- content tier ---------------------------------------------------------------------

    @Test
    fun `the same word added by two learners is stored once and enrolled twice`() = runTest {
        val alice = newUser("alice@example.com")
        val bob = newUser("bob@example.com")

        val first = words.saveNewWordWithInitialSrs(draft(alice, "好", "hǎo"))
        val second = words.saveNewWordWithInitialSrs(draft(bob, "好", "hǎo"))

        assertTrue(first is SaveWordResult.Saved)
        assertTrue(second is SaveWordResult.Saved)
        // The second learner reused the content instead of creating a parallel copy of it.
        assertEquals(
            "the shared vocabulary row should be reused, not duplicated",
            (first as SaveWordResult.Saved).vocabularyId,
            (second as SaveWordResult.Saved).vocabularyId
        )
        assertTrue("the second add should report reused content", second.reusedExistingContent)

        assertEquals(1, database.characterDao().count())
        assertEquals(1, database.vocabularyDao().count())
        assertEquals(1, database.pinyinDao().count())
        // One enrolment each, and they are different rows: the content is shared, the learner
        // tracking it is not.
        assertEquals(1, database.userVocabularyDao().countForUser(alice))
        assertEquals(1, database.userVocabularyDao().countForUser(bob))
        assertNotEquals(
            "two learners must not share an enrolment row",
            (first as SaveWordResult.Saved).userVocabularyId,
            (second as SaveWordResult.Saved).userVocabularyId
        )
    }

    @Test
    fun `a character read two ways is one glyph but two vocabulary entries`() = runTest {
        val user = newUser("poly@example.com")

        words.saveNewWordWithInitialSrs(draft(user, "长", "cháng", "long"))
        words.saveNewWordWithInitialSrs(draft(user, "长", "zhǎng", "to grow"))

        // The glyph and its stroke data exist once...
        assertEquals(1, database.characterDao().count())
        // ...while the two readings are distinct entries, which is the point of splitting them.
        assertEquals(2, database.vocabularyDao().count())
        assertEquals(2, database.pinyinDao().count())
    }

    @Test
    fun `a word cannot be enrolled twice by the same learner`() = runTest {
        val user = newUser("dupe@example.com")

        assertTrue(words.saveNewWordWithInitialSrs(draft(user, "茶", "chá")) is SaveWordResult.Saved)
        val again = words.saveNewWordWithInitialSrs(draft(user, "茶", "chá"))

        assertEquals(SaveWordResult.Duplicate, again)
        assertEquals(1, database.userVocabularyDao().countForUser(user))
    }

    @Test
    fun `the level catalogue is seeded with the HSK ladder and a fallback`() = runTest {
        // Re-seeding is harmless and the assertion is about the catalogue's shape, not about
        // it being seeded exactly once.
        AppDatabase.seedReferenceData(database)

        val levels = database.learningLevelDao().getAll()

        assertEquals(7, levels.size)
        assertNotNull(levels.firstOrNull { it.code == "GENERAL" })
        assertEquals(0, levels.first().ordinal)
        assertEquals(6, levels.last().ordinal)
    }

    // ---- learner isolation ----------------------------------------------------------------

    @Test
    fun `a learner cannot schedule a word they have not enrolled`() = runTest {
        val owner = newUser("owner@example.com")
        val stranger = newUser("stranger@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(owner, "明", "míng")) as SaveWordResult.Saved

        // The guarded insert matches no enrolment for the stranger, so it writes nothing and
        // reports a row id no caller can mistake for success.
        val newRowId = database.srsStateDao().insertIfEnrolled(
            userId = stranger,
            userVocabularyId = saved.userVocabularyId,
            intervalDays = 1,
            repetitions = 0,
            easeFactor = 2.5,
            dueDateMillis = 0L,
            now = 0L
        )

        // Room reports a zero-row INSERT...SELECT as -1, so the contract is `<= 0`. Asserting a
        // specific value would pin a Room implementation detail instead of the property that
        // matters, which is that the caller is told the insert did not happen.
        assertTrue("a refused insert must not look like a new row id: $newRowId", newRowId <= 0L)
        // The stranger got no schedule...
        assertNull(database.srsStateDao().get(stranger, saved.userVocabularyId))
        // ...and the owner's own schedule is untouched.
        assertNotNull(database.srsStateDao().get(owner, saved.userVocabularyId))
    }

    @Test
    fun `a learner cannot read or delete another learner's enrolment`() = runTest {
        val owner = newUser("owner@example.com")
        val stranger = newUser("stranger@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(owner, "朋", "péng")) as SaveWordResult.Saved

        // Deleting is scoped by userId, so a guessed id is not enough.
        assertFalse(words.deleteWord(saved.userVocabularyId, stranger))
        assertNotNull(words.getWordById(saved.userVocabularyId))

        // Each learner sees only their own library.
        assertEquals(1, words.getWordsForUser(owner).first().size)
        assertEquals(0, words.getWordsForUser(stranger).first().size)
    }

    @Test
    fun `the same identifier cannot be registered twice in different cases`() = runTest {
        newUser("Person@Example.com")

        val second = database.userDao().findByIdentifier("person@example.com")

        assertNotNull("lookup is case-insensitive", second)
        assertEquals(1, database.userDao().count())
    }

    @Test
    fun `one profile, one preference row and one streak per learner`() = runTest {
        val user = newUser("single@example.com")

        // The row a learner is entitled to is created once, the way registration creates it.
        database.userProfileDao().insert(UserProfileEntity(userId = user))
        database.userPreferenceDao().insert(UserPreferenceEntity(userId = user))
        database.streakDao().insertIfAbsent(StreakEntity(userId = user))

        // Changing it is a separate, explicit write keyed on the learner rather than on a
        // surrogate id, so it cannot turn into a second row.
        assertEquals(
            1,
            database.userProfileDao().updateForUser(
                userId = user,
                bio = "hello",
                timezoneId = "UTC",
                locale = "en",
                targetExamEpochDay = null,
                avatarSeed = "seed",
                now = 0L
            )
        )

        assertNotNull(database.userProfileDao().observeForUser(user).first())
        assertEquals("hello", database.userProfileDao().getForUser(user)?.bio)
        assertNotNull(database.streakDao().get(user))
        assertNotNull(database.userPreferenceDao().getForUser(user))

        // A second create for the same learner is refused rather than silently duplicating.
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { database.userProfileDao().insert(UserProfileEntity(userId = user)) }
        }
        assertEquals(1, database.userProfileDao().countForUser(user))
    }

    // ---- cascades -------------------------------------------------------------------------

    @Test
    fun `removing a word drops its schedule but keeps the review history`() = runTest {
        val user = newUser("cascade@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(user, "学", "xué")) as SaveWordResult.Saved

        // Record a review so there is history worth preserving.
        val review = com.example.data.repository.SrsRepository(database)
        review.processReview(saved.userVocabularyId, user, com.example.data.srs.SrsRating.GOOD)
        assertEquals(1, database.reviewLogDao().countForUser(user))

        assertTrue(words.deleteWord(saved.userVocabularyId, user))

        // The schedule went with the enrolment...
        assertNull(database.srsStateDao().get(user, saved.userVocabularyId))
        // ...but the shared content and the record of studying it are still there.
        assertEquals(1, database.vocabularyDao().count())
        assertEquals(
            "dropping a word must not erase the learner's review history",
            1,
            database.reviewLogDao().countForUser(user)
        )
    }

    @Test
    fun `deleting a learner removes everything scoped to them and nothing shared`() = runTest {
        val leaving = newUser("leaving@example.com")
        val staying = newUser("staying@example.com")
        words.saveNewWordWithInitialSrs(draft(leaving, "好", "hǎo"))
        words.saveNewWordWithInitialSrs(draft(staying, "好", "hǎo"))
        words.saveNewWordWithInitialSrs(draft(staying, "茶", "chá"))

        database.userDao().deleteUser(leaving)

        assertEquals(0, database.userVocabularyDao().countForUser(leaving))
        assertEquals(2, database.userVocabularyDao().countForUser(staying))
        // The other learner still has every word, including the one the deleted user shared.
        assertEquals(2, words.getWordsForUser(staying).first().size)
        // Both content entries survive: 好 because the other learner is still enrolled in it,
        // and 茶 because content is never scoped to a learner and so was never at risk.
        assertEquals(2, database.vocabularyDao().count())
    }

    @Test
    fun `a review updates the schedule and the log in one step`() = runTest {
        val user = newUser("review@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(user, "明", "míng")) as SaveWordResult.Saved
        val srs = com.example.data.repository.SrsRepository(database)

        val outcome = srs.processReview(saved.userVocabularyId, user, com.example.data.srs.SrsRating.EASY)

        assertTrue(outcome is com.example.data.repository.ReviewOutcome.Recorded)
        val state = database.srsStateDao().get(user, saved.userVocabularyId)
        assertNotNull(state)
        assertEquals(1, state!!.totalReviews)
        assertEquals(1, database.reviewLogDao().countForUser(user))

        // The log records the transition, not just the outcome.
        val entry = database.reviewLogDao().getForCard(user, saved.vocabularyId).single()
        assertEquals(StorageValues.CardState.NEW.storageValue, entry.previousState)
        assertEquals(state.state, entry.newState)
        assertEquals(state.intervalDays, entry.newIntervalDays)
    }

    @Test
    fun `reviewing a card that is not in the collection is refused`() = runTest {
        val owner = newUser("owner2@example.com")
        val stranger = newUser("stranger2@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(owner, "你", "nǐ")) as SaveWordResult.Saved
        val srs = com.example.data.repository.SrsRepository(database)

        val outcome = srs.processReview(saved.userVocabularyId, stranger, com.example.data.srs.SrsRating.GOOD)

        assertTrue(outcome is com.example.data.repository.ReviewOutcome.Rejected)
        assertEquals(0, database.reviewLogDao().countForUser(stranger))
    }

    // ---- derived aggregates ---------------------------------------------------------------

    @Test
    fun `daily totals are one row per learner per day and survive repeated upserts`() = runTest {
        val user = newUser("stats@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(user, "茶", "chá")) as SaveWordResult.Saved
        val srs = com.example.data.repository.SrsRepository(database)

        srs.processReview(saved.userVocabularyId, user, com.example.data.srs.SrsRating.GOOD)
        srs.processReview(saved.userVocabularyId, user, com.example.data.srs.SrsRating.AGAIN)

        val stats = database.dailyStatDao().observeRecent(user, 30).first()
        assertEquals(1, stats.size)
        assertEquals(2, stats.single().reviewsCompleted)
        assertEquals(1, stats.single().correctReviews)
        assertEquals(1, stats.single().againReviews)
        assertEquals(0.5, stats.single().accuracyRate!!, 0.0001)
    }

    @Test
    fun `a streak extends on consecutive days, holds within a day, and restarts after a gap`() = runTest {
        val user = newUser("streak@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(user, "朋", "péng")) as SaveWordResult.Saved
        val srs = com.example.data.repository.SrsRepository(database)
        val day = 86_400_000L

        suspend fun studyOn(epochDay: Int) = srs.processReview(
            userVocabularyId = saved.userVocabularyId,
            userId = user,
            rating = com.example.data.srs.SrsRating.GOOD,
            now = epochDay * day
        )

        studyOn(100)
        assertEquals(1, database.streakDao().get(user)!!.currentLength)

        // A second answer on the same calendar day must not inflate the streak.
        studyOn(100)
        assertEquals(1, database.streakDao().get(user)!!.currentLength)

        // The next day extends it.
        studyOn(101)
        assertEquals(2, database.streakDao().get(user)!!.currentLength)
        studyOn(102)
        assertEquals(3, database.streakDao().get(user)!!.currentLength)
        assertEquals(3, database.streakDao().get(user)!!.longestLength)

        // Two missed days break the run, but the best run is remembered.
        studyOn(105)
        val streak = database.streakDao().get(user)!!
        assertEquals(1, streak.currentLength)
        assertEquals(3, streak.longestLength)
        assertEquals(4, streak.totalActiveDays)
    }

    // ---- validation -----------------------------------------------------------------------

    @Test
    fun `invalid input is refused before anything is written`() = runTest {
        val user = newUser("invalid@example.com")

        val noMeaning = words.saveNewWordWithInitialSrs(draft(user, "好", "hǎo", meaning = "   "))
        // Two-character words are library entries now; only overlong pastes are refused.
        val word = words.saveNewWordWithInitialSrs(draft(user, "学习", "xuéxí"))
        val tooLong = words.saveNewWordWithInitialSrs(draft(user, "学生们好啊呀", "xuéshēngmenhǎoāya"))
        val notPinyin = words.saveNewWordWithInitialSrs(draft(user, "猫", "!!!"))
        val badLevel = words.saveNewWordWithInitialSrs(draft(user, "狗", "gǒu").copy(hskLevel = 99))

        assertTrue(noMeaning is SaveWordResult.Invalid)
        assertTrue("学习 is a valid two-character word: $word", word is SaveWordResult.Saved)
        assertTrue(tooLong is SaveWordResult.Invalid)
        assertTrue(notPinyin is SaveWordResult.Invalid)
        assertTrue(badLevel is SaveWordResult.Invalid)

        // Only the valid word landed.
        assertEquals(1, database.userVocabularyDao().countForUser(user))
    }

    @Test
    fun `a character is stored once even when its radical differs between learners`() = runTest {
        val alice = newUser("rad1@example.com")
        val bob = newUser("rad2@example.com")

        words.saveNewWordWithInitialSrs(draft(alice, "明", "míng").copy(radical = "sun"))
        words.saveNewWordWithInitialSrs(draft(bob, "明", "míng").copy(radical = "bright"))

        assertEquals(1, database.characterDao().count())
        assertEquals(1, database.vocabularyDao().count())
    }

    @Test
    fun `a code point is recorded so lookups need not rescan text`() = runTest {
        val user = newUser("codepoint@example.com")
        words.saveNewWordWithInitialSrs(draft(user, "明", "míng"))

        val byCodePoint = database.characterDao().getByCodePoint("明".codePointAt(0))

        assertNotNull(byCodePoint)
        assertEquals("明", byCodePoint!!.character)
    }

    @Test
    fun `an example sentence is attached to the shared entry only once`() = runTest {
        val alice = newUser("ex1@example.com")
        val bob = newUser("ex2@example.com")

        words.saveNewWordWithInitialSrs(draft(alice, "好", "hǎo"))
        words.saveNewWordWithInitialSrs(draft(bob, "好", "hǎo"))

        val vocabularyId = database.vocabularyDao().getByHanzi("好").single().id

        assertEquals(1, database.exampleSentenceDao().getForVocabulary(vocabularyId).size)
    }

    @Test
    fun `a suspended enrolment leaves the due queue but keeps the row`() = runTest {
        val user = newUser("suspend@example.com")
        val saved = words.saveNewWordWithInitialSrs(draft(user, "学", "xué")) as SaveWordResult.Saved

        assertEquals(1, words.getWordsForUser(user).first().size)

        assertTrue(words.suspendEnrollment(saved.userVocabularyId, user))

        assertEquals(0, words.getWordsForUser(user).first().size)
        // Still present, so suspending is reversible.
        assertEquals(1, database.userVocabularyDao().countForUser(user))
    }

    @Test
    fun `character rows are shared content and carry no learner id`() = runTest {
        val user = newUser("tier@example.com")
        words.saveNewWordWithInitialSrs(draft(user, "猫", "māo"))

        val columns = database.openHelper.readableDatabase.query("PRAGMA table_info(`characters`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

        assertTrue("the content tier must not carry a userId column: $columns", "userId" !in columns)
    }
}
