package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.progress.ProgressMetric
import com.example.data.repository.GamificationRepository
import com.example.data.repository.ReviewOutcome
import com.example.data.repository.SaveWordResult
import com.example.data.repository.SrsRepository
import com.example.data.repository.StudySessionRepository
import com.example.data.repository.WordRepository
import com.example.data.srs.SrsRating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gamification layer against a real database.
 *
 * [com.example.data.progress.ProgressEngineTest] proves the rules; this proves the rules are fed
 * by real rows and that the write is safe to re-run. The two can drift apart silently - a metric
 * counting the wrong table, a `LEFT JOIN` behaving as an inner one, an award pass that announces
 * the same badge twice - and only a real SQLite database will show it.
 *
 * Every assertion is against state the test created by actually saving words and actually
 * recording reviews. Nothing is inserted into `review_log`, `srs_state`, `user_achievements` or
 * `learning_sessions` directly, so a test cannot pass on a row the application would never write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GamificationRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var words: WordRepository
    private lateinit var srs: SrsRepository
    private lateinit var sessions: StudySessionRepository
    private lateinit var progress: GamificationRepository

    /** A fixed instant, so progress is measured against a chosen moment rather than whenever. */
    private val day = 1_760_000_000_000L
    private val dayMillis = 86_400_000L

    /** Ten real characters, because a badge needs a threshold the fixtures can actually reach. */
    private val hanzi = listOf(
        "好" to "hǎo", "水" to "shuǐ", "火" to "huǒ", "山" to "shān", "人" to "rén",
        "日" to "rì", "月" to "yuè", "木" to "mù", "天" to "tiān", "地" to "dì"
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { AppDatabase.seedReferenceData(database) }
        words = WordRepository(database)
        srs = SrsRepository(database)
        sessions = StudySessionRepository(database)
        progress = GamificationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- the measures ----------------------------------------------------------------------------

    @Test
    fun `a learner who has studied nothing is at zero on everything`() = runTest {
        val alice = newUser("alice@example.com")

        val p = progressOf(alice)

        assertEquals(0, p.level.totalXp)
        assertEquals(1, p.level.level)
        assertEquals(0, p.unlockedCount)
        // Every metric is measured, and every one of them is genuinely zero - which is different
        // from a metric that could not be measured at all.
        assertEquals(5, p.milestones.size)
        assertTrue(p.milestones.all { it.value == 0 })
    }

    @Test
    fun `XP is earned from graded reviews and from nothing else`() = runTest {
        val alice = newUser("alice@example.com")
        // Collecting words is not studying, so it must not move the XP.
        val first = enrol(alice, 0)
        val second = enrol(alice, 1)
        assertEquals("adding words is not XP", 0, progressOf(alice).level.totalXp)

        srs.processReview(first, alice, SrsRating.GOOD, now = day)
        srs.processReview(second, alice, SrsRating.AGAIN, now = day)

        val xp = progressOf(alice).level.totalXp
        assertEquals(ProgressEngineXps.good + ProgressEngineXps.again, xp)
    }

    @Test
    fun `XP only ever goes up as answers accumulate`() = runTest {
        val alice = newUser("alice@example.com")
        var previous = 0
        for (index in 0..4) {
            srs.processReview(enrol(alice, index), alice, SrsRating.GOOD, now = day)
            val now = progressOf(alice).level.totalXp
            assertTrue("XP fell from $previous to $now at answer $index", now >= previous)
            previous = now
        }
    }

    @Test
    fun `a word mastered counts toward the mastered milestone`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, 0)

        // A card reaches MASTERED by accumulating easy answers; one answer is not enough, and
        // the count has to track the scheduler's own decision rather than a separate guess.
        assertEquals(0, milestone(alice, ProgressMetric.WordsMastered))
        repeat(8) {
            srs.processReview(word, alice, SrsRating.EASY, now = day)
        }

        assertEquals(1, milestone(alice, ProgressMetric.WordsMastered))
    }

    @Test
    fun `the best streak is measured, not the current one`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, 0)

        // Three consecutive days, then a gap. The badge claims "study three days in a row", so it
        // measures the run that happened. A metric that fell to zero on the gap would make the
        // badge permanently unearnable, which is the behaviour that turns a streak into a debt.
        srs.processReview(word, alice, SrsRating.GOOD, now = day)
        srs.processReview(word, alice, SrsRating.GOOD, now = day + dayMillis)
        srs.processReview(word, alice, SrsRating.GOOD, now = day + 2 * dayMillis)
        srs.processReview(word, alice, SrsRating.GOOD, now = day + 5 * dayMillis)

        assertEquals(3, milestone(alice, ProgressMetric.StreakDays))
    }

    @Test
    fun `one learner's progress is never visible to another`() = runTest {
        val alice = newUser("alice@example.com")
        val bob = newUser("bob@example.com")
        repeat(3) { srs.processReview(enrolmentOf(alice, enrol(alice, it)), alice, SrsRating.GOOD, now = day) }
        enrol(bob, 0)

        assertEquals(3, milestone(alice, ProgressMetric.ReviewsCompleted))
        assertEquals(
            "bob has collected a word but answered nothing, so his review count is zero",
            0,
            milestone(bob, ProgressMetric.ReviewsCompleted)
        )
        assertEquals(1, milestone(bob, ProgressMetric.WordsCollected))
    }

    // ---- awarding --------------------------------------------------------------------------------

    @Test
    fun `a badge is awarded when the activity reaches its threshold`() = runTest {
        val alice = newUser("alice@example.com")
        // FIRST_STUDY_SESSION is thresholded at one session, and a session only counts once a
        // learner has actually answered something in it.
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)

        val earned = progress.evaluateAwards(alice, day)

        assertEquals(1, earned.size)
        assertEquals("FIRST_STUDY_SESSION", earned.single().code)
        assertNotNull("the award must carry the row id so it can be marked seen", earned.single().userAchievementId)
    }

    @Test
    fun `running the award pass again announces nothing`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)
        assertEquals(1, progress.evaluateAwards(alice, day).size)

        // This is the case that matters in practice: the pass runs when a session ends, and again
        // whenever the app is opened. An announcement that repeats trains the learner to ignore it.
        assertTrue(progress.evaluateAwards(alice, day).isEmpty())
        assertTrue(progress.evaluateAwards(alice, day + dayMillis).isEmpty())
    }

    @Test
    fun `a badge is awarded exactly once in the database`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)

        repeat(5) { progress.evaluateAwards(alice, day + it) }

        val rows = database.userAchievementDao().observeUnlocked(alice).first()
        assertEquals(1, rows.size)
    }

    @Test
    fun `a badge's unlock time does not move when the pass re-runs`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)
        val first = progress.evaluateAwards(alice, day).single().unlockedAt

        progress.evaluateAwards(alice, day + 10 * dayMillis)

        val row = database.userAchievementDao().observeUnlocked(alice).first().single()
        // A badge that re-dated itself would look newly earned every time the app opened.
        assertEquals(first, row.unlockedAt)
    }

    @Test
    fun `an unearned badge still records the progress made toward it`() = runTest {
        val alice = newUser("alice@example.com")
        repeat(3) { enrol(alice, it) }

        progress.evaluateAwards(alice, day)

        val collected = progressOf(alice).achievements.single { it.code == "WORDS_10" }
        assertFalse(collected.isUnlocked)
        assertEquals("a locked badge must show what has been measured toward it", 3, collected.progressValue)
        assertEquals(10, collected.threshold)
    }

    @Test
    fun `every badge in the catalogue is reported to the learner`() = runTest {
        val alice = newUser("alice@example.com")

        val shown = progressOf(alice).achievements.map { it.code }.toSet()

        // WORDS_10 / WORDS_50 are at threshold 10 and 50, which no fixture reaches, and a badge
        // that is invisible until it is nearly earned hides the ones still worth chasing.
        assertTrue("WORDS_10 was missing", shown.contains("WORDS_10"))
        assertTrue("STREAK_30 was missing", shown.contains("STREAK_30"))
        assertTrue("REVIEWS_1000 was missing", shown.contains("REVIEWS_1000"))
    }

    @Test
    fun `no badge is awarded for a metric nothing measured`() = runTest {
        val alice = newUser("alice@example.com")
        enrol(alice, 0)

        val earned = progress.evaluateAwards(alice, day)

        // Collecting a word is not studying, and the one badge a brand-new learner can have met is
        // the one for a session they actually sat in. Nothing else should have been handed out.
        assertTrue(earned.isEmpty())
    }

    @Test
    fun `an answer recorded with no session does not count as a study session`() = runTest {
        val alice = newUser("alice@example.com")
        // Exactly what a caller gets by forgetting the session link: the answer is real and is in
        // the log, but it belongs to no sitting, so it cannot be a session anyone sat.
        srs.processReview(enrol(alice, 0), alice, SrsRating.GOOD, now = day)

        assertEquals(0, milestone(alice, ProgressMetric.SessionsCompleted))
        assertTrue(progress.evaluateAwards(alice, day).isEmpty())
    }

    // ---- sessions --------------------------------------------------------------------------------

    @Test
    fun `a session is only a session once it has recorded an answer`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 2)
        assertEquals(
            "an unstarted session is not a completed one",
            0,
            milestone(alice, ProgressMetric.SessionsCompleted)
        )

        answer(alice, session, 0, SrsRating.GOOD)
        progress.evaluateAwards(alice, day)

        assertEquals(1, milestone(alice, ProgressMetric.SessionsCompleted))
    }

    @Test
    fun `an empty deck does not create a session`() = runTest {
        val alice = newUser("alice@example.com")

        val sessionId = sessions.begin(
            userId = alice,
            type = StorageValues.SessionType.REVIEW,
            vocabularyIds = emptyList(),
            now = day
        )

        // A sitting of zero cards is not a sitting, and writing one would put a row in the table
        // that SESSIONS_COMPLETED counts.
        assertNull(sessionId)
    }

    @Test
    fun `a session records the answers that were actually given`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 3)
        for (index in 0..2) {
            answer(alice, session, index, SrsRating.GOOD)
        }

        val closed = sessions.end(session.id, alice, StorageValues.SessionStatus.COMPLETED, day + 60_000)
        assertNotNull(closed)
        assertEquals(3, closed!!.reviewedCount)
        assertEquals(3, closed.correctCount)
        assertEquals(StorageValues.SessionStatus.COMPLETED.storageValue, closed.status)
    }

    @Test
    fun `a session cannot be closed twice`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)

        assertNotNull(sessions.end(session.id, alice, StorageValues.SessionStatus.COMPLETED, day + 1_000))
        // A second close reports that there was nothing to do rather than overwriting the first.
        assertNull(sessions.end(session.id, alice, StorageValues.SessionStatus.ABANDONED, day + 2_000))
    }

    @Test
    fun `a card cannot be answered twice in one session`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)
        val schedule = srs.getReviewForWord(session.cards.first().second)!!

        assertFalse(
            "a double tap must not be able to write two answers",
            sessions.recordAnswer(
                session.id, alice, session.cards.first().first, SrsRating.EASY, day, 0, schedule
            )
        )
    }

    @Test
    fun `starting a new session abandons the one left open`() = runTest {
        val alice = newUser("alice@example.com")
        val first = openSession(alice, 1)
        assertNotNull(sessions.activeFor(alice))

        // A different word, because a second session over the same card is not a thing a learner
        // can do - and a fixture that tried would fail on the duplicate guard rather than on the
        // abandonment this test is about.
        openSession(alice, 1, now = day + dayMillis, startIndex = 1)

        // A trail of sessions the learner never finished would inflate SESSIONS_COMPLETED and,
        // with it, the badge for having studied a number of times they did not.
        val second = sessions.activeFor(alice)!!
        assertFalse("the first session id was reused", first.id == second.id)
        assertEquals(StorageValues.SessionStatus.ABANDONED.storageValue, database.learningSessionDao().getById(first.id)!!.status)
    }

    @Test
    fun `a session summary reports the answers the log holds`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 4)
        val ratings = listOf(SrsRating.GOOD, SrsRating.AGAIN, SrsRating.EASY, SrsRating.HARD)

        for (index in ratings.indices) {
            answer(alice, session, index, ratings[index])
        }
        val closed = sessions.end(session.id, alice, StorageValues.SessionStatus.COMPLETED, day + 120_000)!!

        val summary = progress.summariseSession(alice, session.id, closed.durationMillis)

        assertEquals(4, summary.answers)
        assertEquals(1, summary.again)
        // Priced per rating actually given, not per card. A tally that charged a flat amount per
        // answer would make grading honestly - saying AGAIN rather than GOOD to keep a card close -
        // worth less than guessing, which is the opposite of what the app is for.
        val expected = ratings.fold(0) { total, rating -> total + xpOf(rating) }
        assertEquals(expected, summary.xpEarned)
        assertEquals(0.75f, summary.accuracy!!, 0.0001f)
        assertEquals(120_000L, summary.durationMillis)
    }

    @Test
    fun `a session with no answers has no accuracy to report`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 2)

        val summary = progress.summariseSession(alice, session.id, durationMillis = null)

        // Not 100%. A session with nothing in it has no accuracy, and a dash is the only honest
        // thing to put on a card.
        assertNull(summary.accuracy)
        assertEquals(0, summary.xpEarned)
        assertNull(summary.durationMillis)
    }

    @Test
    fun `one learner cannot read another learner's session`() = runTest {
        val alice = newUser("alice@example.com")
        val bob = newUser("bob@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)

        assertEquals(0, progress.summariseSession(bob, session.id).answers)
        assertNull(sessions.end(session.id, bob, StorageValues.SessionStatus.COMPLETED, day))
    }

    // ---- announcing ------------------------------------------------------------------------------

    @Test
    fun `an earned badge is announced once and not again`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)
        val earned = progress.evaluateAwards(alice, day)
        assertEquals(1, earned.size)

        assertEquals(1, progress.observeUnannouncedAwards(alice).first().size)
        for (award in earned) {
            assertTrue(progress.markSeen(alice, award.userAchievementId, day))
        }

        assertTrue(
            "a badge that re-appears at the next session end reads as a bug",
            progress.observeUnannouncedAwards(alice).first().isEmpty()
        )
    }

    @Test
    fun `marking a badge seen twice is a no-op`() = runTest {
        val alice = newUser("alice@example.com")
        val session = openSession(alice, 1)
        answer(alice, session, 0, SrsRating.GOOD)
        val award = progress.evaluateAwards(alice, day).single()

        assertTrue(progress.markSeen(alice, award.userAchievementId, day))
        assertFalse(progress.markSeen(alice, award.userAchievementId, day + 1_000))
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private suspend fun newUser(identifier: String): Long = database.userDao().insertUser(
        UserEntity(
            identifier = identifier,
            identifierNormalized = identifier.lowercase(),
            authType = StorageValues.AuthType.EMAIL.storageValue,
            // Nothing here signs in, so this is a marker rather than a credential.
            passwordHash = "unused-by-these-tests",
            displayName = identifier.substringBefore("@"),
            token = "token-$identifier"
        )
    )

    private fun draft(userId: Long, hanzi: String, pinyin: String) = NewWordDraft(
        userId = userId,
        hanzi = hanzi,
        pinyin = pinyin,
        meaning = "a meaning for $hanzi",
        hskLevel = 1,
        radical = "rad",
        exampleCn = "句子 $hanzi",
        examplePy = "juzi $pinyin",
        exampleEn = "a sentence",
        strokeJson = "横"
    )

    /** Enrols one of the fixture words, returning the enrolment id. */
    private suspend fun enrol(userId: Long, index: Int): Long {
        val (hanzi, pinyin) = hanzi[index]
        val result = words.saveNewWordWithInitialSrs(draft(userId, hanzi, pinyin))
        return (result as SaveWordResult.Saved).userVocabularyId
    }

    /** The shared-content id for an enrolment, which is what `session_cards` keys on. */
    private suspend fun enrolmentOf(userId: Long, userVocabularyId: Long): Long =
        database.userVocabularyDao().getById(userVocabularyId)!!.vocabularyId

    /**
     * An open session over [count] freshly enrolled fixture words.
     *
     * Enrolling and opening together, because that is the only order the application can produce:
     * a session's cards are inserted up front against words the learner already owns. Splitting
     * it would let a fixture build a session over words the user_vocabulary foreign key should
     * have rejected, and a test that only passes because of an impossible state is worse than no
     * test.
     */
    private suspend fun openSession(
        userId: Long,
        count: Int,
        now: Long = day,
        startIndex: Int = 0
    ): StudyFixture {
        val cards = (startIndex until startIndex + count).map { index ->
            val userVocabularyId = enrol(userId, index)
            enrolmentOf(userId, userVocabularyId) to userVocabularyId
        }
        val id = sessions.begin(
            userId = userId,
            type = StorageValues.SessionType.REVIEW,
            vocabularyIds = cards.map { it.first },
            now = now
        )
        assertNotNull("the fixture deck was not empty, so a session must have opened", id)
        return StudyFixture(id!!, cards)
    }

    /**
     * Answers one card of [session] the way the deck does: the review is recorded with the
     * session's id, and the session card is then written with the schedule that produced.
     *
     * Both halves matter, and a helper that skipped either would be a test the application could
     * pass for the wrong reason. The id on the log entry is what `SESSIONS_COMPLETED` counts; the
     * session card is what the completion summary is read back from.
     */
    private suspend fun answer(
        userId: Long,
        session: StudyFixture,
        index: Int,
        rating: SrsRating,
        now: Long = day
    ) {
        val (vocabularyId, userVocabularyId) = session.cards[index]
        val outcome = srs.processReview(
            userVocabularyId = userVocabularyId,
            userId = userId,
            rating = rating,
            sessionId = session.id,
            now = now
        )
        assertTrue("the review for card $index was rejected: $outcome", outcome is ReviewOutcome.Recorded)
        val schedule = srs.getReviewForWord(userVocabularyId)!!
        assertTrue(
            "the session card for $index was not written",
            sessions.recordAnswer(
                sessionId = session.id,
                userId = userId,
                vocabularyId = vocabularyId,
                rating = rating,
                answeredAt = now,
                responseTimeMillis = 0,
                schedule = schedule
            )
        )
    }

    /** An open session and the `(vocabularyId, userVocabularyId)` pairs it was opened over. */
    private data class StudyFixture(val id: Long, val cards: List<Pair<Long, Long>>)

    private fun progressOf(userId: Long) =
        runBlocking { progress.observeProgress(userId).first() }

    private fun milestone(userId: Long, metric: ProgressMetric): Int =
        progressOf(userId).milestones.single { it.metric == metric }.value

    /** The XP prices, read through the engine so the fixture cannot drift from the rules. */
    private object ProgressEngineXps {
        val again = com.example.data.progress.ProgressEngine.xpFor(SrsRating.AGAIN)
        val good = com.example.data.progress.ProgressEngine.xpFor(SrsRating.GOOD)
        val easy = com.example.data.progress.ProgressEngine.xpFor(SrsRating.EASY)
        val hard = com.example.data.progress.ProgressEngine.xpFor(SrsRating.HARD)
    }

    /** One rating's worth of XP, by the same route the engine prices it. */
    private fun xpOf(rating: SrsRating): Int =
        com.example.data.progress.ProgressEngine.xpFor(rating)
}
