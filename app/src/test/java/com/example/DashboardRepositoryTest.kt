package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.dashboard.DashboardSnapshot
import com.example.data.dashboard.NextAction
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.model.UserPreferenceEntity
import com.example.data.repository.DashboardRepository
import com.example.data.repository.SaveWordResult
import com.example.data.repository.SrsRepository
import com.example.data.repository.WordRepository
import com.example.data.srs.SrsRating
import com.example.data.srs.StudyDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The dashboard against a real database.
 *
 * [com.example.data.dashboard.DashboardAggregatorTest] proves the rules; this proves the rules
 * are fed by real rows. The two can drift apart silently — a query that returns the wrong join,
 * a `LEFT JOIN` that behaves as an inner one, a column alias that quietly maps to null — and
 * only a real SQLite database will show it.
 *
 * Every assertion here is against a state the test created by actually saving words and
 * actually recording reviews. Nothing is inserted into `srs_state` or `daily_stats` directly, so
 * a test cannot pass on a row the application would never write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DashboardRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var words: WordRepository
    private lateinit var srs: SrsRepository

    private val zone: ZoneId = ZoneOffset.UTC

    /** A fixed instant, so "today" is a chosen day rather than whenever the test runs. */
    private val day = 1_760_000_000_000L
    private val dayMillis = 86_400_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { AppDatabase.seedReferenceData(database) }
        // Both repositories are pinned to [day] and to [zone]. The clock matters because
        // `WordRepository` now writes a `daily_stats` row when it enrols a word, and that row is
        // keyed on the study day it was written on. Left on the wall clock, every enrolment
        // landed on the machine's real day while the reviews landed on [day], and the two showed
        // up in the history as unrelated rows — which is what made
        // "a review yesterday is not reported as today's work" report two days of activity
        // instead of one. It was the repository reading a clock the test could not set, not the
        // dashboard miscounting.
        words = WordRepository(database, zone) { day }
        srs = SrsRepository(database, zone)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun newUser(identifier: String): Long = database.userDao().insertUser(
        UserEntity(
            identifier = identifier,
            identifierNormalized = identifier.lowercase(),
            authType = StorageValues.AuthType.EMAIL.storageValue,
            // Nothing here signs in, so this is a marker rather than a credential. A literal
            // shaped like a real PBKDF2 digest would be a lie in a fixture.
            passwordHash = "unused-by-these-tests",
            displayName = identifier.substringBefore("@"),
            token = "token-$identifier"
        )
    )

    private fun draft(userId: Long, hanzi: String, pinyin: String, meaning: String) = NewWordDraft(
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

    private suspend fun enrol(userId: Long, hanzi: String, pinyin: String, meaning: String) =
        enrol(words, userId, hanzi, pinyin, meaning)

    /**
     * Enrols through an explicit repository, so a test can choose *which day* the word counts
     * against.
     *
     * `saveNewWordWithInitialSrs` writes a `daily_stats` row for the day it is called on, and
     * that is what the daily new-word allowance is measured against — so "enrol five words" and
     * "enrol five words *today*" are different fixtures, and only one of them leaves the
     * allowance intact.
     */
    private suspend fun enrol(
        repository: WordRepository,
        userId: Long,
        hanzi: String,
        pinyin: String,
        meaning: String
    ) = (repository.saveNewWordWithInitialSrs(draft(userId, hanzi, pinyin, meaning))
        as SaveWordResult.Saved).userVocabularyId

    /** A repository whose enrolments are dated to [millis] rather than to the wall clock. */
    private fun wordsOn(millis: Long) = WordRepository(database, zone) { millis }

    private fun dashboard(userId: Long, now: Long = day): DashboardSnapshot =
        runBlocking {
            DashboardRepository(database, zone) { now }.observeDashboard(userId).first()
        }

    private suspend fun setLimits(userId: Long, newLimit: Int, reviewLimit: Int) {
        database.userPreferenceDao().insert(
            UserPreferenceEntity(
                userId = userId,
                dailyNewWordLimit = newLimit,
                dailyReviewLimit = reviewLimit
            )
        )
    }

    // ---- the empty and untouched cases ----------------------------------------------------------------

    @Test
    fun `a learner with nothing enrolled is asked to add a word`() = runTest {
        val alice = newUser("alice@example.com")

        val snapshot = dashboard(alice)

        assertEquals(NextAction.Kind.NOTHING_ENROLLED, snapshot.recommendation.kind)
        assertEquals(0, snapshot.progress.enrolled)
        assertTrue(snapshot.isEmptyCollection)
        assertTrue(
            "an empty week must not be drawn as a chart of zeroes",
            !snapshot.hasHistory
        )
    }

    @Test
    fun `enrolling a word makes it new, not due`() = runTest {
        val alice = newUser("alice@example.com")
        enrol(alice, "好", "hǎo", "good")

        val snapshot = dashboard(alice)

        assertEquals(1, snapshot.progress.enrolled)
        assertEquals(1, snapshot.progress.new)
        assertEquals(
            "a word nobody has started is not overdue",
            0,
            snapshot.workload.dueReviews
        )
        assertEquals(NextAction.Kind.LEARN_NEW, snapshot.recommendation.kind)
    }

    @Test
    fun `the daily goal is the learner's own stored limit`() = runTest {
        val alice = newUser("alice@example.com")
        setLimits(alice, newLimit = 3, reviewLimit = 25)
        // Five distinct real characters. `Validator` refuses a multi-character hanzi, so a
        // generated fixture like "字0" would be rejected before it reached the database and the
        // failure would read as a bug in the goal arithmetic.
        //
        // Dated to *yesterday*, deliberately. This test is about where the limit comes from, and
        // it needs five cards waiting with the allowance untouched. It used to enrol them today
        // and assert three were offered, which it could only do because `newWordsIntroduced` was
        // never incremented — the daily cap was not being enforced, only reported. Now that the
        // counter works, five words added today consume the whole allowance of three and the
        // correct answer is zero, which is a different test; it is
        // `the daily new word allowance is consumed by words added today` below.
        listOf("好" to "hǎo", "水" to "shuǐ", "火" to "huǒ", "山" to "shān", "人" to "rén")
            .forEach { (hanzi, pinyin) ->
                enrol(wordsOn(day - dayMillis), alice, hanzi, pinyin, "a meaning for $hanzi")
            }

        val snapshot = dashboard(alice)

        assertEquals(3, snapshot.workload.limits.newWords)
        assertEquals(25, snapshot.workload.limits.reviews)
        assertEquals(
            "five new words must be offered at the learner's limit of three",
            3,
            snapshot.workload.newOffered
        )
    }

    /**
     * The daily new-word limit is actually enforced, not just reported.
     *
     * This could not be written before `newWordsIntroduced` was incremented, because the
     * allowance was computed as `limit - 0` for every learner on every day. A learner could add
     * a hundred words in one morning and the dashboard would keep offering them the full daily
     * allowance, because the one number that would have told it otherwise was permanently zero.
     *
     * The cap is asserted at each step rather than only at the end, so a failure names the word
     * that broke it.
     */
    @Test
    fun `the daily new word allowance is consumed by words added today`() = runTest {
        val alice = newUser("alice@example.com")
        setLimits(alice, newLimit = 3, reviewLimit = 25)

        // Ten cards waiting, added *yesterday* so they do not touch today's quota.
        //
        // This is the part that makes the test about the quota rather than about arithmetic. The
        // offered count is `newAvailable.coerceAtMost(limit - taken)`, so with only four cards in
        // the collection the *card count* binds first and the quota never gets to speak — which is
        // what an earlier version of this test measured, and it read as the quota falling when it
        // was only the collection shrinking.
        val waiting = listOf(
            "好" to "hǎo", "水" to "shuǐ", "火" to "huǒ", "山" to "shān", "人" to "rén",
            "日" to "rì", "月" to "yuè", "木" to "mù", "石" to "shí", "田" to "tián"
        )
        waiting.forEach { (hanzi, pinyin) ->
            enrol(wordsOn(day - dayMillis), alice, hanzi, pinyin, "a meaning for $hanzi")
        }
        assertEquals(
            "the collection itself must not be the binding constraint, or this test measures the " +
                "wrong thing",
            3,
            dashboard(alice).workload.newOffered
        )

        // Four more, added today, one past the limit of three.
        val expectedAfterEach = listOf(2, 1, 0, 0)
        listOf("天" to "tiān", "地" to "dì", "风" to "fēng", "雨" to "yǔ")
            .forEachIndexed { index, (hanzi, pinyin) ->
                enrol(alice, hanzi, pinyin, "a meaning for $hanzi")
                assertEquals(
                    "after adding ${index + 1} word(s) today, the remaining daily allowance was " +
                        "wrong",
                    expectedAfterEach[index],
                    dashboard(alice).workload.newOffered
                )
            }

        // `day` is a millisecond instant, so the epoch day is derived through the same zone-aware
        // helper the write and read paths use rather than by dividing — the division is only
        // right in UTC, which is the assumption that hid §2.1 in the first place.
        val todayEpochDay = StudyDay.epochDayOf(day, zone)
        assertEquals(
            "the day counted the wrong number of new words",
            4,
            database.dailyStatDao().get(alice, todayEpochDay)?.newWordsIntroduced
        )
    }

    @Test
    fun `a learner with no preferences row still gets a dashboard`() = runTest {
        val alice = newUser("alice@example.com")
        enrol(alice, "好", "hǎo", "good")

        val snapshot = dashboard(alice)

        assertNotNull("a missing preferences row must not blank the screen", snapshot.recommendation)
        assertTrue(snapshot.workload.limits.newWords > 0)
    }

    // ---- reviews that have actually happened -------------------------------------------------------------

    @Test
    fun `a reviewed word is no longer new, and the counts follow`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")

        val outcome = srs.processReview(word, alice, SrsRating.GOOD, now = day)
        assertTrue(outcome is com.example.data.repository.ReviewOutcome.Recorded)

        val snapshot = dashboard(alice, now = day)

        assertEquals("the card has left the new pile", 0, snapshot.progress.new)
        assertEquals(1, snapshot.progress.review)
        assertEquals(1, snapshot.progress.enrolled)
        // GOOD on a first review schedules one day out, so nothing is due yet.
        assertEquals(
            "a card answered today is scheduled for tomorrow, not for now",
            0,
            snapshot.workload.dueReviews
        )
        assertEquals(NextAction.Kind.CAUGHT_UP, snapshot.recommendation.kind)

        val tomorrow = dashboard(alice, now = day + dayMillis)
        assertEquals(1, tomorrow.workload.dueReviews)
        assertEquals(NextAction.Kind.REVIEW_DUE, tomorrow.recommendation.kind)
    }

    @Test
    fun `accuracy is computed from the reviews that were recorded`() = runTest {
        val alice = newUser("alice@example.com")
        val good = enrol(alice, "好", "hǎo", "good")
        val hard = enrol(alice, "水", "shuǐ", "water")
        val again = enrol(alice, "火", "huǒ", "fire")

        srs.processReview(good, alice, SrsRating.GOOD, now = day)
        srs.processReview(hard, alice, SrsRating.HARD, now = day)
        srs.processReview(again, alice, SrsRating.AGAIN, now = day)

        val snapshot = dashboard(alice, now = day)

        assertEquals(3, snapshot.today?.reviewsCompleted)
        assertEquals("two of three ratings were not AGAIN", 2, snapshot.today?.correctReviews)
        assertEquals(1, snapshot.today?.againReviews)
        assertEquals(2.0 / 3.0, snapshot.today?.accuracy!!, 1e-9)
    }

    @Test
    fun `accuracy stays unknown on a day with no reviews`() = runTest {
        val alice = newUser("alice@example.com")
        enrol(alice, "好", "hǎo", "good")

        val snapshot = dashboard(alice, now = day)

        assertNull(
            "an unstudied day has no accuracy, and inventing one is the whole failure mode here",
            snapshot.today?.accuracy
        )
    }

    @Test
    fun `a review yesterday is not reported as today's work`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")

        srs.processReview(word, alice, SrsRating.GOOD, now = day - dayMillis)

        val snapshot = dashboard(alice, now = day)

        // Asserted on the count rather than on `today` being null. Enrolling a word writes a
        // `daily_stats` row for today, so `today` is now a real day with one new word on it and
        // no reviews — which is the truth. The old `assertNull` was a proxy for "no reviews were
        // counted today", and it only held while the enrolment wrote no row at all; asserting the
        // thing the test is named for is also the thing that survives that changing.
        assertEquals(
            "yesterday's review was counted as today's work",
            0,
            snapshot.today?.reviewsCompleted
        )

        // The history holds two days, and the count is asserted per day rather than as a total:
        // enrolling a word wrote a row for today, so `recentDays.size` is 2 and asserting 1 would
        // be asserting that adding a word leaves no trace — the very behaviour that made the
        // dashboard's new-word figure permanently zero.
        //
        // Both days are resolved through [StudyDay] rather than by dividing millis by 86,400,000.
        // The division only works in UTC, which is the assumption that hid the day-bucket bug in
        // the first place; `zone` is UTC here so the two agree, and using the shared function
        // keeps it that way if the fixture zone ever changes.
        val history = snapshot.recentDays.associateBy { it.epochDay }
        val yesterdayReviews: Int? =
            history[StudyDay.epochDayOf(day - dayMillis, zone)]?.reviewsCompleted
        val todayNewWords: Int? =
            history[StudyDay.epochDayOf(day, zone)]?.newWordsIntroduced

        // Typed locals, because JUnit4's overloads are chosen by argument type and a bare
        // `history[...]?.reviewsCompleted` in the call position does not resolve.
        assertEquals("yesterday's review is history", 1, yesterdayReviews)
        assertEquals("the word added today belongs to today", 1, todayNewWords)
    }

    @Test
    fun `the streak is the one the reviews actually built`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")

        srs.processReview(word, alice, SrsRating.GOOD, now = day - dayMillis)
        srs.processReview(word, alice, SrsRating.GOOD, now = day)

        val snapshot = dashboard(alice, now = day)

        assertEquals(2, snapshot.progress.currentStreakDays)
        assertEquals(2, snapshot.progress.longestStreakDays)
        assertEquals(2, snapshot.progress.activeDays)
        assertTrue(snapshot.progress.studiedToday)
    }

    @Test
    fun `lifetime reviews count every recorded answer`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")

        repeat(3) { srs.processReview(word, alice, SrsRating.GOOD, now = day) }

        assertEquals(3, dashboard(alice, now = day).progress.lifetimeReviews)
    }

    // ---- due dates come from the scheduler, not from a guess --------------------------------------------

    @Test
    fun `a card is due once its scheduled date has passed`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")
        srs.processReview(word, alice, SrsRating.GOOD, now = day)

        val soon = dashboard(alice, now = day)
        val muchLater = dashboard(alice, now = day + 365 * dayMillis)

        assertEquals("a card scheduled a day out is not due today", 0, soon.workload.dueReviews)
        assertEquals("and is due a year later", 1, muchLater.workload.dueReviews)
    }

    @Test
    fun `a card that falls due later today already counts as due today`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")

        // AGAIN reschedules ten minutes out, which lands inside the same day. The other ratings
        // schedule whole days ahead, so this is the only rating that can produce a card that
        // becomes due later today rather than tomorrow.
        val midnight = startOfDay(day)
        srs.processReview(word, alice, SrsRating.AGAIN, now = midnight)

        // The rule being pinned: the dashboard answers "how much will I need today", not "how
        // much is overdue at this instant". A card that falls due in ten minutes is something
        // the learner will need today, and reporting zero would understate the queue.
        assertEquals(
            "ten minutes before it falls due it is already part of today's work",
            1,
            dashboard(alice, now = midnight + 60_000L).workload.dueReviews
        )
        assertEquals(
            "and still part of today's work at the last instant of the day",
            1,
            dashboard(alice, now = endOfDay(midnight)).workload.dueReviews
        )
    }

    @Test
    fun `a card that falls due tomorrow is not part of today's work`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")

        // GOOD on a first review schedules a full day out, which is the next day.
        srs.processReview(word, alice, SrsRating.GOOD, now = day)

        // `day` is mid-morning, so one day out is mid-morning *tomorrow*, which is strictly
        // after today has ended. Sampling at the last instant of today is what makes this a
        // real "not today" rather than a probe that quietly lands on the following day.
        assertEquals(
            "one day out is tomorrow, not today",
            0,
            dashboard(alice, now = endOfDay(day)).workload.dueReviews
        )
        assertEquals(
            "and it is due on the day it was scheduled for",
            1,
            dashboard(alice, now = startOfDay(day) + dayMillis + 12 * 3_600_000L).workload.dueReviews
        )
    }

    /** Midnight at the start of the day containing [from], in the zone the tests read the day in. */
    private fun startOfDay(from: Long): Long = Instant.ofEpochMilli(from)
        .atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * The last millisecond of the day containing [from].
     *
     * Deliberately not the start of the next day: that instant belongs to the following day, so
     * using it as the "now" a dashboard is read at would widen the window by a whole day and
     * make a "not due today" assertion pass for the wrong reason.
     */
    private fun endOfDay(from: Long): Long = startOfDay(from) + dayMillis - 1

    // ---- difficult words, from real lapses ----------------------------------------------------------------

    @Test
    fun `a word is only difficult once it has actually been missed`() = runTest {
        val alice = newUser("alice@example.com")
        val good = enrol(alice, "好", "hǎo", "good")
        val missed = enrol(alice, "水", "shuǐ", "water")

        srs.processReview(good, alice, SrsRating.GOOD, now = day)
        srs.processReview(missed, alice, SrsRating.AGAIN, now = day)

        val snapshot = dashboard(alice, now = day)

        assertEquals(1, snapshot.difficult.size)
        assertEquals("水", snapshot.difficult.single().character)
        assertEquals("water", snapshot.difficult.single().meaning)
        assertTrue(
            "the join must carry the word, not just its id",
            snapshot.difficult.single().pinyin.isNotEmpty()
        )
    }

    @Test
    fun `a learner who has missed nothing has no difficult list`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")
        srs.processReview(word, alice, SrsRating.EASY, now = day)

        assertTrue(
            "an EASY answer is not difficulty, whatever the ease factor says",
            dashboard(alice, now = day).difficult.isEmpty()
        )
    }

    @Test
    fun `the worst word is listed first`() = runTest {
        val alice = newUser("alice@example.com")
        val once = enrol(alice, "好", "hǎo", "good")
        val thrice = enrol(alice, "水", "shuǐ", "water")

        srs.processReview(once, alice, SrsRating.AGAIN, now = day)
        repeat(3) { srs.processReview(thrice, alice, SrsRating.AGAIN, now = day) }

        val difficult = dashboard(alice, now = day).difficult
        assertTrue("the twice-missed word should lead", difficult.size >= 2)
        assertEquals("水", difficult.first().character)
    }

    // ---- isolation ---------------------------------------------------------------------------------------

    @Test
    fun `one learner never sees another's progress`() = runTest {
        val alice = newUser("alice@example.com")
        val bob = newUser("bob@example.com")
        val aliceWord = enrol(alice, "好", "hǎo", "good")
        enrol(bob, "水", "shuǐ", "water")

        srs.processReview(aliceWord, alice, SrsRating.GOOD, now = day)

        val bobs = dashboard(bob, now = day)

        assertEquals("bob has one word of his own", 1, bobs.progress.enrolled)
        assertEquals("and none of alice's reviews", 0, bobs.progress.lifetimeReviews)
        // Bob's own day is present and empty of reviews, which is a *stronger* statement of
        // isolation than the `assertNull` this replaced: Alice's review did not turn up in his
        // dashboard under any spelling. It is present because Bob added a word of his own today,
        // and adding a word writes a roll-up row — so the row is his, with one new word and no
        // reviews, and Alice's review is nowhere in it.
        assertEquals(
            "bob has not studied, so today's reviews must be zero",
            0,
            bobs.today?.reviewsCompleted
        )
        assertEquals(
            "and the one thing on his day is his own word, not Alice's review",
            1,
            bobs.today?.newWordsIntroduced
        )
    }

    @Test
    fun `a review of somebody else's word is refused`() = runTest {
        val alice = newUser("alice@example.com")
        val bob = newUser("bob@example.com")
        val alicesWord = enrol(alice, "好", "hǎo", "good")

        val outcome = srs.processReview(alicesWord, bob, SrsRating.GOOD, now = day)

        assertTrue(
            "the dashboard must not be a way to observe a learner you do not own",
            outcome is com.example.data.repository.ReviewOutcome.Rejected
        )
        assertEquals(0, dashboard(bob, now = day).progress.lifetimeReviews)
    }

    // ---- the recommendation ------------------------------------------------------------------------------

    @Test
    fun `recommending a review outranks recommending a new word`() = runTest {
        val alice = newUser("alice@example.com")
        val due = enrol(alice, "好", "hǎo", "good")
        enrol(alice, "水", "shuǐ", "water")
        srs.processReview(due, alice, SrsRating.GOOD, now = day)

        val snapshot = dashboard(alice, now = day + 365 * dayMillis)

        assertEquals(1, snapshot.workload.dueReviews)
        assertEquals(1, snapshot.workload.newAvailable)
        assertEquals(NextAction.Kind.REVIEW_DUE, snapshot.recommendation.kind)
    }

    @Test
    fun `a fully caught up learner is told so`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")
        srs.processReview(word, alice, SrsRating.GOOD, now = day)
        setLimits(alice, newLimit = 0, reviewLimit = 60)

        val snapshot = dashboard(alice, now = day)

        assertEquals(NextAction.Kind.CAUGHT_UP, snapshot.recommendation.kind)
        assertEquals(0, snapshot.recommendation.cardCount)
    }
}
