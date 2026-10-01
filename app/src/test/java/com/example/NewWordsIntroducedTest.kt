package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.repository.SaveWordResult
import com.example.data.repository.WordRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * `daily_stats.newWordsIntroduced` actually counts.
 *
 * ## What was broken
 *
 * The column existed, was written to `daily_stats` on every review, was selected by
 * `DailyStatDao.observeDay`, and was surfaced on the dashboard as a headline number — and nothing
 * ever incremented it. `saveNewWordWithInitialSrs` enrolled the word and returned without
 * touching the roll-up, so the figure was permanently `0` for every learner on every day.
 *
 * It is the kind of defect a green test suite hides completely: every assertion about it would
 * have been written against the value the code produced, which was always zero.
 *
 * ## Why the write is where it is
 *
 * Inside the enrolment transaction, not after it. Two separate writes would mean a crash between
 * them leaves a word enrolled and uncounted, and nothing in the app re-derives the roll-up for
 * the current day — `recomputeForDay` exists for the weekly roll-up, and the daily row is
 * written incrementally. The word and its count are one fact, so they are one transaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NewWordsIntroducedTest {

    private lateinit var database: AppDatabase

    /**
     * Non-UTC on purpose. [WordRepository] resolves the study day through the same [StudyDay]
     * helper the reader uses, so pinning UTC here would make the test pass even if the write and
     * the read disagreed by a day — which is precisely the bug `StudyDayTest` exists for.
     */
    private val zone = ZoneId.of("America/Chicago")
    private val today = LocalDate.of(2026, 3, 1)
    private val todayEpochDay = today.toEpochDay().toInt()

    /** The instant the day starts in this zone, so the repository is told which day it is. */
    private val todayAtMidnight = today.atStartOfDay(zone).toInstant().toEpochMilli() + 20L * 60L * 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { AppDatabase.seedReferenceData(database) }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `adding a word counts it against today`() = runTest {
        val userId = newUser("counter@example.com")
        val words = WordRepository(database, zone) { todayAtMidnight }

        assertEquals(
            "the day must start with no row at all, or a zero here would be a default rather " +
                "than a count",
            null,
            database.dailyStatDao().get(userId, todayEpochDay)
        )

        enrol(words, userId, "海", "hai", "sea")

        val row = database.dailyStatDao().get(userId, todayEpochDay)
        assertNotNull("enrolling a word did not create today's row at all", row)
        assertEquals("one word was added but the roll-up says otherwise", 1, row!!.newWordsIntroduced)
    }

    @Test
    fun `adding words accumulates across the day`() = runTest {
        val userId = newUser("repeat@example.com")
        val words = WordRepository(database, zone) { todayAtMidnight }

        // Asserting per save rather than only at the end, naming the entry, so a failure points
        // at which word was dropped instead of just reporting a low total.
        listOf(
            "海" to "hai", "山" to "shan", "火" to "huo", "水" to "shui"
        ).forEachIndexed { index, (hanzi, pinyin) ->
            enrol(words, userId, hanzi, pinyin, "meaning-$hanzi")
            assertEquals(
                "after saving ${index + 1} word(s), ending with $hanzi, the roll-up was wrong",
                index + 1,
                database.dailyStatDao().get(userId, todayEpochDay)?.newWordsIntroduced
            )
        }
    }

    /**
     * The count is per learner, not global.
     *
     * A shared counter would make the dashboard's headline number meaningful for exactly one
     * person, and this app is entirely local — accounts are rows on one device — so the
     * cross-talk would be real rather than hypothetical.
     */
    @Test
    fun `one learner's words are not counted for another`() = runTest {
        val alice = newUser("alice@example.com")
        val bob = newUser("bob@example.com")
        val words = WordRepository(database, zone) { todayAtMidnight }

        enrol(words, alice, "海", "hai", "sea")
        enrol(words, alice, "山", "shan", "mountain")
        enrol(words, bob, "火", "huo", "fire")

        assertEquals("Alice's count was wrong", 2, database.dailyStatDao().get(alice, todayEpochDay)?.newWordsIntroduced)
        assertEquals("Bob's count absorbed Alice's words", 1, database.dailyStatDao().get(bob, todayEpochDay)?.newWordsIntroduced)
    }

    /**
     * A day with no words must have no row.
     *
     * The opposite failure is easy to write: a write that creates a zero row to increment it.
     * That would make `observeDay` stop being able to distinguish "no activity yet today" from
     * "activity today", which is the one thing that query exists to distinguish.
     */
    @Test
    fun `a learner who has not studied has no row rather than a row of zeroes`() = runTest {
        val userId = newUser("quiet@example.com")
        WordRepository(database, zone) { todayAtMidnight }

        assertEquals(
            "an untouched day must not materialise a daily row",
            null,
            database.dailyStatDao().get(userId, todayEpochDay)
        )
    }

    // ---- helpers --------------------------------------------------------------------------------

    private suspend fun newUser(identifier: String): Long = database.userDao().insertUser(
        UserEntity(
            identifier = identifier,
            identifierNormalized = identifier.lowercase(),
            authType = StorageValues.AuthType.EMAIL.storageValue,
            passwordHash = "unused-by-this-test",
            displayName = identifier.substringBefore("@"),
            token = "token-$identifier"
        )
    )

    private suspend fun enrol(
        words: WordRepository,
        userId: Long,
        hanzi: String,
        pinyin: String,
        meaning: String
    ) {
        val result = words.saveNewWordWithInitialSrs(
            NewWordDraft(
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
        )
        assertTrue("saving $hanzi did not succeed, so the count could not have moved: $result",
            result is SaveWordResult.Saved)
    }
}
