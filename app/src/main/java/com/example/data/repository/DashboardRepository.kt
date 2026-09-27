package com.example.data.repository

import com.example.data.dashboard.DailyLimits
import com.example.data.dashboard.DashboardAggregator
import com.example.data.dashboard.DashboardSnapshot
import com.example.data.dashboard.DayActivity
import com.example.data.dashboard.DifficultCard
import com.example.data.dashboard.ScheduledCardRow
import com.example.data.db.AppDatabase
import com.example.data.db.toDailyLimits
import com.example.data.model.DailyStatEntity
import com.example.data.model.StorageValues.CardState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

/**
 * Assembles the dashboard from the learner's own rows.
 *
 * Holds no statistics of its own. Every number it publishes is either read from a row or
 * computed by [DashboardAggregator] from rows, so there is no code path in which a figure can
 * be produced without a corresponding record of why.
 *
 * [zone] and [clock] are injected for the same reason [SrsRepository] takes `now`: "due today"
 * is a whole-day boundary, and a boundary that can only be tested at today's date is a boundary
 * that is never tested.
 */
class DashboardRepository(
    private val database: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val dashboardDao = database.dashboardDao()
    private val dailyStatDao = database.dailyStatDao()
    private val streakDao = database.streakDao()

    /**
     * How many days of history the dashboard charts.
     *
     * Seven matches the "did I keep my streak" question a learner opens the app to ask. Longer
     * would need scrolling to be useful, which on a phone means not being useful.
     */
    private val historyDays = 7

    /**
     * The whole dashboard for [userId], recomputed whenever any input changes.
     *
     * One flow of one value rather than eight flows of eight values. Separate flows would let
     * the screen draw a streak from yesterday beside a queue count from this instant, and the
     * learner would see two truths at once.
     */
    fun observeDashboard(userId: Long): Flow<DashboardSnapshot> {
        val now = clock()
        val today = toEpochDay(now)
        val todayEnd = endOfTodayMillis(now)

        // Combined in stages because the typed `combine` overloads stop at five flows, and
        // because these inputs belong together: a card row and the difficult list are both
        // views of the same collection, so they should never be observed independently.
        val collection = combine(
            dashboardDao.observeCards(userId),
            dashboardDao.observeDifficult(userId, DashboardAggregator.DIFFICULT_LIMIT * 2)
        ) { cards, difficultRows -> Collection(cards, difficultRows) }

        val activity = combine(
            dailyStatDao.observeDay(userId, today),
            dailyStatDao.observeRecent(userId, historyDays)
        ) { todayRow, recent -> Activity(todayRow, recent) }

        val totals = combine(
            dailyStatDao.observeLifetimeReviews(userId),
            streakDao.observe(userId)
        ) { reviews, streak -> Totals(reviews, streak) }

        return combine(
            collection,
            activity,
            totals,
            dashboardDao.observeLimits(userId)
        ) { coll, act, tot, limitsRow ->
            DashboardAggregator.aggregate(
                cards = coll.cards,
                difficult = coll.difficultRows.mapNotNull { it.toDifficultCard() },
                todayRow = act.todayRow?.toDayActivity(),
                recentDays = act.recent.map { it.toDayActivity() },
                lifetimeReviews = tot.lifetimeReviews,
                activeDays = tot.streak?.totalActiveDays ?: 0,
                currentStreakDays = tot.streak?.currentLength ?: 0,
                longestStreakDays = tot.streak?.longestLength ?: 0,
                limits = limitsRow.toDailyLimits(),
                nowEpochDay = today,
                todayEndMillis = todayEnd
            )
        }
    }

    /** The learner's collection: every card, and the hard ones within it. */
    private data class Collection(
        val cards: List<ScheduledCardRow>,
        val difficultRows: List<com.example.data.db.DifficultCardRow>
    )

    /** The learner's own recorded activity. */
    private data class Activity(
        val todayRow: DailyStatEntity?,
        val recent: List<DailyStatEntity>
    )

    /** Lifetime counters, which do not change within a day. */
    private data class Totals(
        val lifetimeReviews: Int,
        val streak: com.example.data.model.StreakEntity?
    )

    /**
     * Translates a stored row into a [DifficultCard].
     *
     * Returns null for a state the enum does not recognise. A card whose stored state is
     * unreadable is dropped from the *report* rather than crashing the dashboard, and it stays
     * in the database where it can be diagnosed - hiding a row in a list is very different from
     * hiding a row in the source of truth.
     */
    private fun com.example.data.db.DifficultCardRow.toDifficultCard(): DifficultCard? {
        val parsed = CardState.fromStorage(state) ?: return null
        return DifficultCard(
            userVocabularyId = userVocabularyId,
            vocabularyId = vocabularyId,
            character = character,
            pinyin = pinyin,
            meaning = meaning,
            state = parsed,
            lapses = lapses,
            easeFactor = easeFactor,
            totalReviews = totalReviews
        )
    }

    private fun DailyStatEntity.toDayActivity() = DayActivity(
        epochDay = dateEpochDay,
        reviewsCompleted = reviewsCompleted,
        correctReviews = correctReviews,
        againReviews = againReviews,
        newWordsIntroduced = newWordsIntroduced,
        newWordsMastered = newWordsMastered,
        studyMillis = studyMillis
    )

    /**
     * The learner's epoch day for [millis], in their own timezone.
     *
     * The same rule [SrsRepository] uses to bucket a review into a day. Two copies of this would
     * be a latent bug - a review logged just before midnight could land in a different day from
     * the one the dashboard attributes it to - so the two must agree, and the shared shape of
     * the signature is the thing that makes a divergence visible.
     */
    fun toEpochDay(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay().toInt()

    /**
     * The last instant of [millis]'s day, in the learner's timezone.
     *
     * "Due today" has to mean the whole day, not the current instant. Using `now` would tell a
     * learner at breakfast that a card which falls due at lunchtime is not due, and a dashboard
     * that understates the queue to look tidy is worse than one that admits a card is coming.
     */
    private fun endOfTodayMillis(millis: Long): Long {
        val endOfDay = Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
        return endOfDay.toInstant().toEpochMilli()
    }
}
