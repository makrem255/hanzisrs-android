package com.example

import com.example.data.dashboard.DailyLimits
import com.example.data.dashboard.DashboardAggregator
import com.example.data.dashboard.DayActivity
import com.example.data.dashboard.DifficultCard
import com.example.data.dashboard.NextAction
import com.example.data.dashboard.ScheduledCardRow
import com.example.data.model.StorageValues.CardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's reasoning, tested without a database.
 *
 * Every figure on the screen comes out of [DashboardAggregator], so these cases are where a
 * dashboard that lies gets caught. The recurring theme is the difference between "zero" and
 * "unknown": a day with no reviews has an accuracy it never earned, and a collection with
 * nothing in it has a mastery rate of nothing.
 */
class DashboardAggregatorTest {

    private val limits = DailyLimits(newWords = 10, reviews = 60)
    private val today = 20_000L
    private val endOfToday = 86_400_000L

    private fun scheduled(
        id: Long,
        state: CardState?,
        dueAt: Long = 0,
        lapses: Int = 0,
        ease: Double = 2.5,
        reviews: Int = 0
    ) = ScheduledCardRow(
        userVocabularyId = id,
        state = state?.storageValue,
        dueDateMillis = if (state == null) null else dueAt,
        lapses = lapses,
        easeFactor = ease,
        totalReviews = reviews
    )

    private fun difficult(
        id: Long,
        character: String,
        lapses: Int,
        ease: Double = 2.5,
        reviews: Int = 1
    ) = DifficultCard(
        userVocabularyId = id,
        vocabularyId = id,
        character = character,
        pinyin = "",
        meaning = "meaning",
        state = CardState.REVIEW,
        lapses = lapses,
        easeFactor = ease,
        totalReviews = reviews
    )

    private fun aggregate(
        cards: List<ScheduledCardRow> = emptyList(),
        difficult: List<DifficultCard> = emptyList(),
        todayRow: DayActivity? = null,
        recent: List<DayActivity> = emptyList(),
        lifetime: Int = 0,
        activeDays: Int = 0,
        currentStreak: Int = 0,
        longestStreak: Int = 0,
        limits: DailyLimits = this.limits,
        todayEnd: Long = endOfToday
    ) = DashboardAggregator.aggregate(
        cards = cards,
        difficult = difficult,
        todayRow = todayRow,
        recentDays = recent,
        lifetimeReviews = lifetime,
        activeDays = activeDays,
        currentStreakDays = currentStreak,
        longestStreakDays = longestStreak,
        limits = limits,
        nowEpochDay = today.toInt(),
        todayEndMillis = todayEnd
    )

    private fun day(
        epochDay: Int,
        reviews: Int = 0,
        correct: Int = 0,
        again: Int = 0,
        newWordsIntroduced: Int = 0
    ) = DayActivity(
        epochDay = epochDay,
        reviewsCompleted = reviews,
        correctReviews = correct,
        againReviews = again,
        newWordsIntroduced = newWordsIntroduced,
        newWordsMastered = 0,
        studyMillis = 0
    )

    // ---- counting the workload ---------------------------------------------------------------------

    @Test
    fun `a brand new card is not due, however early it is scheduled`() {
        val snapshot = aggregate(
            cards = listOf(
                scheduled(1, CardState.NEW, dueAt = 0),
                scheduled(2, CardState.NEW, dueAt = 0)
            )
        )

        assertEquals(
            "counting new cards as overdue is what made the old button say everything was due",
            0,
            snapshot.workload.dueReviews
        )
        assertEquals(2, snapshot.workload.newAvailable)
    }

    @Test
    fun `an unscheduled enrolment is counted but never counted as due`() {
        val snapshot = aggregate(cards = listOf(scheduled(1, state = null)))

        assertEquals(0, snapshot.workload.dueReviews)
        assertEquals(
            "a word in the collection before it has been scheduled is still a new word",
            1,
            snapshot.workload.newAvailable
        )
        assertEquals(1, snapshot.progress.enrolled)
    }

    @Test
    fun `a card due later today counts as due`() {
        val snapshot = aggregate(
            cards = listOf(scheduled(1, CardState.REVIEW, dueAt = endOfToday - 1))
        )

        assertEquals(
            "'due today' means the whole day, not the current instant",
            1,
            snapshot.workload.dueReviews
        )
    }

    @Test
    fun `a card due tomorrow does not count`() {
        val snapshot = aggregate(
            cards = listOf(scheduled(1, CardState.REVIEW, dueAt = endOfToday + 1))
        )

        assertEquals(0, snapshot.workload.dueReviews)
    }

    @Test
    fun `every state lands in exactly one bucket and the buckets sum to the collection`() {
        val snapshot = aggregate(
            cards = listOf(
                scheduled(1, CardState.NEW),
                scheduled(2, CardState.NEW),
                scheduled(3, CardState.LEARNING),
                scheduled(4, CardState.REVIEW),
                scheduled(5, CardState.MASTERED),
                scheduled(6, CardState.MASTERED),
                scheduled(7, state = null)
            )
        )

        val p = snapshot.progress
        // Three, not two: the unscheduled enrolment counts as new, because a word in the
        // collection that has not been scheduled yet is a word that has not been learned.
        assertEquals(3, p.new)
        assertEquals(1, p.learning)
        assertEquals(1, p.review)
        assertEquals(2, p.mastered)
        assertEquals(
            "a total the buckets do not add up to is a total the screen cannot trust",
            7,
            p.enrolled
        )
        assertEquals(7, p.distribution.sumOf { it.count })
    }

    @Test
    fun `words learned excludes the ones never studied`() {
        val snapshot = aggregate(
            cards = listOf(
                scheduled(1, CardState.NEW),
                scheduled(2, CardState.LEARNING),
                scheduled(3, CardState.MASTERED)
            )
        )

        assertEquals(2, snapshot.progress.wordsLearned)
    }

    // ---- the limits are the learner's own -----------------------------------------------------------

    @Test
    fun `the new word quota caps what is offered`() {
        val snapshot = aggregate(
            cards = List(30) { scheduled(it.toLong() + 1, CardState.NEW) },
            limits = limits.copy(newWords = 5)
        )

        assertEquals(30, snapshot.workload.newAvailable)
        assertEquals(5, snapshot.workload.newOffered)
    }

    @Test
    fun `new words taken today count against the quota`() {
        val snapshot = aggregate(
            cards = List(10) { scheduled(it.toLong() + 1, CardState.NEW) },
            todayRow = day(today.toInt(), newWordsIntroduced = 4),
            limits = limits.copy(newWords = 5)
        )

        assertEquals(1, snapshot.workload.newLimitRemaining)
        assertEquals(1, snapshot.workload.newOffered)
    }

    @Test
    fun `a met quota offers no new words at all`() {
        val snapshot = aggregate(
            cards = List(10) { scheduled(it.toLong() + 1, CardState.NEW) },
            todayRow = day(today.toInt(), newWordsIntroduced = 10)
        )

        assertTrue(snapshot.workload.newQuotaMet)
        assertEquals(0, snapshot.workload.newOffered)
    }

    // ---- accuracy, and refusing to invent it ---------------------------------------------------------

    @Test
    fun `accuracy is unknown, not zero, on a day with no reviews`() {
        val snapshot = aggregate(todayRow = day(today.toInt(), reviews = 0))

        assertNull(
            "a day with no reviews has an accuracy of unknown, not of zero",
            snapshot.today?.accuracy
        )
    }

    @Test
    fun `accuracy counts every rated review`() {
        val snapshot = aggregate(
            todayRow = day(today.toInt(), reviews = 10, correct = 8, again = 2)
        )

        assertEquals(0.8, snapshot.today!!.accuracy!!, 1e-9)
    }

    @Test
    fun `a day that only ever failed is zero and not null`() {
        val snapshot = aggregate(todayRow = day(today.toInt(), reviews = 4, correct = 0, again = 4))

        assertEquals(0.0, snapshot.today!!.accuracy!!, 1e-9)
    }

    @Test
    fun `mastery rate is unknown for an empty collection`() {
        val snapshot = aggregate(cards = emptyList())

        assertNull(snapshot.progress.masteryRate)
        assertTrue(
            "a share of nothing must not be rendered as a zero-width bar",
            snapshot.progress.distribution.all { it.share == null }
        )
    }

    @Test
    fun `shares are a real proportion of the collection`() {
        val snapshot = aggregate(
            cards = listOf(
                scheduled(1, CardState.NEW),
                scheduled(2, CardState.MASTERED),
                scheduled(3, CardState.MASTERED),
                scheduled(4, CardState.MASTERED)
            )
        )

        assertEquals(0.75, snapshot.progress.masteryRate!!, 1e-9)
        assertEquals(0.75f, snapshot.progress.distribution.first { it.state == CardState.MASTERED }.share!!, 1e-6f)
        assertEquals(
            1f,
            snapshot.progress.distribution.sumOf { it.share!!.toDouble() }.toFloat(),
            1e-5f
        )
    }

    // ---- difficult words ----------------------------------------------------------------------------

    @Test
    fun `a word that has never lapsed is not called difficult`() {
        val snapshot = aggregate(
            cards = listOf(scheduled(1, CardState.REVIEW, ease = 1.3)),
            difficult = listOf(difficult(1, character = "好", lapses = 0, ease = 1.3))
        )

        assertTrue(
            "a low ease factor is not evidence a learner struggled; a lapse is",
            snapshot.difficult.isEmpty()
        )
    }

    @Test
    fun `the worst lapsed word is listed first`() {
        val snapshot = aggregate(
            difficult = listOf(
                difficult(1, character = "学", lapses = 1, ease = 2.0),
                difficult(2, character = "水", lapses = 4, ease = 2.4),
                difficult(3, character = "火", lapses = 2, ease = 1.9)
            )
        )

        assertEquals(listOf("水", "火", "学"), snapshot.difficult.map { it.character })
    }

    @Test
    fun `equally lapped words are broken by how hard the scheduler found them`() {
        val snapshot = aggregate(
            difficult = listOf(
                difficult(1, character = "学", lapses = 2, ease = 2.2),
                difficult(2, character = "水", lapses = 2, ease = 1.6)
            )
        )

        assertEquals("水", snapshot.difficult.first().character)
    }

    @Test
    fun `the difficult list is a nudge, not a report`() {
        val snapshot = aggregate(
            difficult = (1..12).map { difficult(it.toLong(), character = "字$it", lapses = it) }
        )

        assertEquals(DashboardAggregator.DIFFICULT_LIMIT, snapshot.difficult.size)
        assertEquals("the worst offender survives truncation", 12, snapshot.difficult.first().lapses)
    }

    // ---- what to do next ----------------------------------------------------------------------------

    @Test
    fun `an empty collection is prompted to add a word, not congratulated`() {
        val snapshot = aggregate(cards = emptyList())

        assertEquals(NextAction.Kind.NOTHING_ENROLLED, snapshot.recommendation.kind)
        assertTrue(!snapshot.recommendation.isStartable)
        assertTrue(snapshot.isEmptyCollection)
    }

    @Test
    fun `reviews are recommended ahead of new words`() {
        val snapshot = aggregate(
            cards = listOf(
                scheduled(1, CardState.REVIEW, dueAt = 0),
                scheduled(2, CardState.NEW)
            )
        )

        assertEquals(NextAction.Kind.REVIEW_DUE, snapshot.recommendation.kind)
        assertEquals(1, snapshot.recommendation.cardCount)
    }

    @Test
    fun `new words come next once nothing is due`() {
        val snapshot = aggregate(cards = listOf(scheduled(1, CardState.NEW)))

        assertEquals(NextAction.Kind.LEARN_NEW, snapshot.recommendation.kind)
        assertEquals(1, snapshot.recommendation.cardCount)
    }

    @Test
    fun `difficult words never displace reviews that are genuinely due`() {
        val snapshot = aggregate(
            cards = listOf(scheduled(1, CardState.REVIEW, dueAt = 0)),
            difficult = listOf(difficult(1, character = "水", lapses = 3))
        )

        assertEquals(
            "practising something hard is optional; memory that is decaying is not",
            NextAction.Kind.REVIEW_DUE,
            snapshot.recommendation.kind
        )
    }

    @Test
    fun `a met new word quota does not manufacture something to do`() {
        val snapshot = aggregate(
            cards = listOf(scheduled(1, CardState.NEW)),
            todayRow = day(today.toInt(), newWordsIntroduced = 10)
        )

        assertEquals(NextAction.Kind.CAUGHT_UP, snapshot.recommendation.kind)
        assertEquals(0, snapshot.recommendation.cardCount)
    }

    @Test
    fun `a learner with nothing due and a hard word is offered that word`() {
        val snapshot = aggregate(
            cards = listOf(scheduled(1, CardState.REVIEW, dueAt = endOfToday + 1)),
            difficult = listOf(difficult(1, character = "水", lapses = 3))
        )

        assertEquals(NextAction.Kind.PRACTICE_DIFFICULT, snapshot.recommendation.kind)
    }

    @Test
    fun `fully caught up with nothing hard says so plainly`() {
        val snapshot = aggregate(
            cards = listOf(scheduled(1, CardState.MASTERED, dueAt = endOfToday + 1))
        )

        assertEquals(NextAction.Kind.CAUGHT_UP, snapshot.recommendation.kind)
    }

    @Test
    fun `a recommendation always carries the count it describes`() {
        val snapshot = aggregate(
            cards = (1..4).map { scheduled(it.toLong(), CardState.REVIEW, dueAt = 0) }
        )

        assertEquals(snapshot.workload.dueReviews, snapshot.recommendation.cardCount)
        assertTrue(snapshot.recommendation.headline.contains("4"))
    }

    // ---- history and streaks ------------------------------------------------------------------------

    @Test
    fun `history is ordered oldest first so a chart reads left to right`() {
        val snapshot = aggregate(
            recent = listOf(day(300), day(100), day(200))
        )

        assertEquals(listOf(100, 200, 300), snapshot.recentDays.map { it.epochDay })
    }

    @Test
    fun `history with no reviews is not history`() {
        val empty = aggregate(recent = listOf(day(1), day(2)))
        val real = aggregate(recent = listOf(day(1, reviews = 5)))

        assertTrue(!empty.hasHistory)
        assertTrue(real.hasHistory)
    }

    @Test
    fun `studying today is detected from the day's own row`() {
        val studied = aggregate(todayRow = day(today.toInt(), reviews = 1))
        val not = aggregate(todayRow = day(today.toInt(), reviews = 0))

        assertTrue(studied.progress.studiedToday)
        assertTrue(!not.progress.studiedToday)
    }

    @Test
    fun `a streak is reported as stored, including none at all`() {
        val snapshot = aggregate(currentStreak = 4, longestStreak = 11, activeDays = 20)

        assertEquals(4, snapshot.progress.currentStreakDays)
        assertEquals(11, snapshot.progress.longestStreakDays)
        assertEquals(20, snapshot.progress.activeDays)
    }

    @Test
    fun `a learner with no streak row is reported as zero, not as missing`() {
        val snapshot = aggregate(cards = listOf(scheduled(1, CardState.NEW)))

        assertEquals(0, snapshot.progress.currentStreakDays)
        assertEquals(0, snapshot.progress.lifetimeReviews)
        assertEquals("an unstudied day is absent, which is different from a day of zeroes", null, snapshot.today)
    }

    @Test
    fun `the total queue is reviews first then new`() {
        val snapshot = aggregate(
            cards = listOf(
                scheduled(1, CardState.REVIEW, dueAt = 0),
                scheduled(2, CardState.REVIEW, dueAt = 0),
                scheduled(3, CardState.NEW)
            )
        )

        assertEquals(3, snapshot.workload.totalQueued)
    }
}
