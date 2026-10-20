package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.dashboard.DailyLimits
import com.example.data.dashboard.DashboardAggregator
import com.example.data.dashboard.DifficultCard
import com.example.data.dashboard.WeakWordSelector
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.model.StorageValues.CardState
import com.example.data.model.UserEntity
import com.example.data.model.WordView
import com.example.data.model.WordWithSrs
import com.example.data.repository.DashboardRepository
import com.example.data.repository.SaveWordResult
import com.example.data.repository.SrsRepository
import com.example.data.repository.WordRepository
import com.example.data.srs.SrsRating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * What "practice the weak words" means, from the rule down to real rows.
 *
 * Three claims are on trial. That "weak" is a property of the data — a schedule that has
 * actually lapsed — rather than a hunch about ease or recency. That the sitting presents
 * the words in the order the dashboard's "Worth another look" list shows them, capped to
 * the same five, so the button practises the list the learner just read. And that the
 * empty case is genuinely empty: a button that promises weak words and finds none must
 * start nothing, so the screen can say so instead of quietly opening an ordinary sitting.
 *
 * The agreement tests are the load-bearing ones. The ranking exists twice — inline in
 * [DashboardAggregator.aggregate] and again in [WeakWordSelector], because the two operate
 * on different projections of the same rows — so both halves are fed the same numbers here
 * and required to produce the same ids in the same order: first from fixtures, then from
 * an in-memory SQLite database where the lapses were written by actually recording
 * `AGAIN` answers, the way the app writes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeakWordSelectorTest {

    // ---- fixtures --------------------------------------------------------------------------

    /** One learner row, spelled once so both sides can be built from literally these numbers. */
    private data class Row(
        val id: Long,
        /** `null` is "no schedule row at all" — the join's `hasSrs = false`, not a lapse of 0. */
        val lapses: Int?,
        val ease: Double = 2.5,
        val reviews: Int = 1
    )

    private fun word(row: Row): WordWithSrs = WordWithSrs(
        word = WordView(
            id = row.id,
            userId = 1L,
            vocabularyId = 100L + row.id,
            hanzi = "字${row.id}",
            pinyin = "zi${row.id}",
            meaning = "meaning ${row.id}",
            hskLevel = 1,
            radical = "",
            exampleCn = "",
            examplePy = "",
            exampleEn = "",
            strokeJson = "",
            tags = "",
            exampleSentenceId = null,
            addedAt = 0L,
            isStarred = false
        ),
        srs = row.lapses?.let { lapses ->
            SrsStateEntity(
                userId = 1L,
                userVocabularyId = row.id,
                vocabularyId = 100L + row.id,
                intervalDays = 1,
                repetitions = 1,
                easeFactor = row.ease,
                dueDateMillis = 0L,
                lastReviewMillis = 0L,
                state = StorageValues.CardState.REVIEW.storageValue,
                lastRating = SrsRating.GOOD.value,
                lapses = lapses,
                totalReviews = row.reviews,
                schedulerVersion = 1
            )
        }
    )

    private fun difficult(row: Row): DifficultCard = DifficultCard(
        userVocabularyId = row.id,
        vocabularyId = 100L + row.id,
        character = "字${row.id}",
        pinyin = "zi${row.id}",
        meaning = "meaning ${row.id}",
        state = CardState.REVIEW,
        // A DifficultCard is a join over `srs_state`; a word without a schedule cannot be
        // one, which is why no agreement fixture ever hands it a null lapse count.
        lapses = requireNotNull(row.lapses),
        easeFactor = row.ease,
        totalReviews = row.reviews
    )

    private fun dashboardDifficult(rows: List<Row>): List<Long> = DashboardAggregator.aggregate(
        cards = emptyList(),
        difficult = rows.map { difficult(it) },
        todayRow = null,
        recentDays = emptyList(),
        lifetimeReviews = 0,
        activeDays = 0,
        currentStreakDays = 0,
        longestStreakDays = 0,
        limits = DailyLimits(newWords = 10, reviews = 60),
        nowEpochDay = 20_000,
        todayEndMillis = 20_000L * 86_400_000L
    ).difficult.map { it.userVocabularyId }

    private fun selected(rows: List<Row>): List<Long> =
        WeakWordSelector.select(rows.map { word(it) }).map { it.word.id }

    // ---- what counts as weak ------------------------------------------------------------------

    @Test
    fun `an empty collection selects nothing`() {
        assertTrue(WeakWordSelector.select(emptyList()).isEmpty())
    }

    @Test
    fun `a word with no schedule is not weak`() {
        // Never scheduled is not the same as scheduled and failed. The dashboard draws the
        // same line, and calling a brand new word "something you have missed" would be a
        // claim the learner's own history does not support.
        assertTrue(WeakWordSelector.select(listOf(word(Row(id = 1, lapses = null)))).isEmpty())
    }

    @Test
    fun `a schedule that has never lapsed is not weak`() {
        // Low ease and a pile of reviews are not lapses either: the scheduler being unsure
        // about a card is the scheduler's business, not a failure the learner produced.
        val rows = listOf(
            Row(id = 1, lapses = 0, ease = 1.4, reviews = 30),
            Row(id = 2, lapses = 0, ease = 1.5, reviews = 12)
        )
        assertTrue(selected(rows).isEmpty())
    }

    @Test
    fun `only lapsed words are selected`() {
        val rows = listOf(
            Row(id = 1, lapses = 0),
            Row(id = 2, lapses = 1),
            Row(id = 3, lapses = 0),
            Row(id = 4, lapses = 2)
        )
        assertEquals(listOf(4L, 2L), selected(rows))
    }

    // ---- how the sitting is ordered ------------------------------------------------------------

    @Test
    fun `the worst word comes first`() {
        val rows = listOf(Row(1, lapses = 1), Row(2, lapses = 3), Row(3, lapses = 2))
        assertEquals(listOf(2L, 3L, 1L), selected(rows))
    }

    @Test
    fun `equal lapses break by ease, then reviews, then enrolment id`() {
        // The exact tie-break chain of `DashboardAggregator.aggregate`, in order: lower ease
        // wins, then more reviews, and the id makes the order total so two refreshes of the
        // same data cannot reshuffle the deck under the learner.
        val rows = listOf(
            Row(id = 1, lapses = 2, ease = 2.5, reviews = 4),
            Row(id = 2, lapses = 2, ease = 2.0, reviews = 9),
            Row(id = 3, lapses = 2, ease = 2.0, reviews = 5),
            Row(id = 4, lapses = 2, ease = 2.0, reviews = 9)
        )
        assertEquals(listOf(2L, 4L, 3L, 1L), selected(rows))
    }

    @Test
    fun `the sitting is capped at the dashboard's limit, taking the worst`() {
        // Nine lapsed words, ranked by lapses so the worst is the highest id. Capping is
        // deliberate: the sitting is the list the learner was shown, not a longer queue.
        val rows = (1L..9L).map { Row(id = it, lapses = it.toInt()) }
        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), selected(rows))
        assertEquals(DashboardAggregator.DIFFICULT_LIMIT, selected(rows).size)
    }

    @Test
    fun `the collection itself is left alone`() {
        val rows = listOf(Row(3, lapses = 1), Row(1, lapses = 2), Row(2, lapses = 0))
        WeakWordSelector.select(rows.map { word(it) })
        assertEquals(
            "selection is a view over the data, never a mutation of it",
            listOf(3L, 1L, 2L),
            rows.map { it.id }
        )
    }

    // ---- agreement with the dashboard, from fixtures ---------------------------------------------

    @Test
    fun `the selector orders exactly the way the dashboard orders`() {
        // Row 4 has never lapsed: both sides must drop it. Row 1 and row 3 tie on every
        // key but the id, which is where a divergence between the two rankings would show.
        val rows = listOf(
            Row(id = 1, lapses = 1, ease = 2.5, reviews = 3),
            Row(id = 2, lapses = 3, ease = 2.1, reviews = 5),
            Row(id = 3, lapses = 1, ease = 2.5, reviews = 3),
            Row(id = 4, lapses = 0, ease = 1.8, reviews = 1),
            Row(id = 5, lapses = 2, ease = 2.4, reviews = 8)
        )
        val fromSelector = selected(rows)
        assertEquals(dashboardDifficult(rows), fromSelector)
        assertEquals(listOf(2L, 5L, 1L, 3L), fromSelector)
    }

    @Test
    fun `both sides take the same five, in the same order`() {
        val rows = (1L..9L).map { Row(id = it, lapses = (((it * 7) % 5) + 1).toInt()) }
        val fromSelector = selected(rows)
        assertEquals(dashboardDifficult(rows), fromSelector)
        assertEquals(DashboardAggregator.DIFFICULT_LIMIT, fromSelector.size)
    }

    // ---- against a real database, from real reviews -----------------------------------------------

    private lateinit var database: AppDatabase
    private lateinit var wordRepository: WordRepository
    private lateinit var srs: SrsRepository

    /** A fixed instant, so "the day" is a chosen day rather than whenever the test runs. */
    private val day = 1_760_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { AppDatabase.seedReferenceData(database) }
        // Pinned to the same fixed day, for the reason DashboardRepositoryTest pins it: the
        // enrolment writes a `daily_stats` row, and a wall-clock day would make the words
        // and the reviews land in different history rows.
        wordRepository = WordRepository(database, ZoneOffset.UTC) { day }
        srs = SrsRepository(database, ZoneOffset.UTC)
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
        hskLevel = 1
    )

    private suspend fun enrol(userId: Long, hanzi: String, pinyin: String, meaning: String): Long =
        (wordRepository.saveNewWordWithInitialSrs(draft(userId, hanzi, pinyin, meaning))
            as SaveWordResult.Saved).userVocabularyId

    @Test
    fun `real lapses select exactly the words the dashboard surfaced`() = runTest {
        val alice = newUser("alice@example.com")
        val healthy = enrol(alice, "好", "hǎo", "good")
        val once = enrol(alice, "水", "shuǐ", "water")
        val thrice = enrol(alice, "火", "huǒ", "fire")

        // Nothing is inserted into `srs_state` by hand: the lapses exist because reviews
        // were recorded, through the same repository call the review screen uses.
        srs.processReview(healthy, alice, SrsRating.GOOD, now = day)
        srs.processReview(once, alice, SrsRating.AGAIN, now = day)
        repeat(3) { srs.processReview(thrice, alice, SrsRating.AGAIN, now = day) }

        val collection = wordRepository.getWordsForUser(alice).first()
        val snapshot = DashboardRepository(database, ZoneOffset.UTC) { day }
            .observeDashboard(alice).first()

        val fromSelector = WeakWordSelector.select(collection).map { it.word.id }
        assertEquals(
            "the sitting must be the list the dashboard shows, ids and order both",
            snapshot.difficult.map { it.userVocabularyId },
            fromSelector
        )
        assertEquals("worst first: the thrice-missed word, then the once-missed one",
            listOf(thrice, once), fromSelector)
        assertTrue(
            "a word the learner has never missed is not weak, however good its reviews",
            healthy !in fromSelector
        )
    }

    @Test
    fun `a learner who missed nothing has nothing to practise`() = runTest {
        val alice = newUser("alice@example.com")
        val word = enrol(alice, "好", "hǎo", "good")
        srs.processReview(word, alice, SrsRating.GOOD, now = day)

        assertTrue(
            "no lapse means no weak sitting — the screen must be able to say so",
            WeakWordSelector.select(wordRepository.getWordsForUser(alice).first()).isEmpty()
        )
        assertTrue(
            "and the dashboard must agree there is nothing weak to show",
            DashboardRepository(database, ZoneOffset.UTC) { day }
                .observeDashboard(alice).first().difficult.isEmpty()
        )
    }
}
