package com.example.data.dashboard

import com.example.data.model.StorageValues.CardState
import com.example.util.plural

/**
 * Turns the learner's own rows into a [DashboardSnapshot].
 *
 * Pure: no database, no Android, no clock of its own. Everything it needs arrives as an
 * argument, including the boundary of "today", because the boundary is a timezone-dependent
 * judgement and pretending otherwise in pure code is how a dashboard ends up claiming a card is
 * not due when it became due at lunchtime. That makes the whole of the dashboard's reasoning
 * testable on the JVM, in the same way [com.example.data.srs.SrsAlgorithm] is.
 *
 * The rules that decide what is "due", what counts as "difficult" and what to recommend next
 * live here rather than in a screen, because a screen is where they get forgotten the second
 * time somebody adds a second screen.
 */
object DashboardAggregator {

    /**
     * The ceiling on how many difficult words to surface.
     *
     * Small on purpose. This is a nudge, not a report; a list of twenty words is a list nobody
     * reads and that hides the two at the top.
     */
    const val DIFFICULT_LIMIT: Int = 5

    fun aggregate(
        cards: List<ScheduledCardRow>,
        difficult: List<DifficultCard>,
        todayRow: DayActivity?,
        recentDays: List<DayActivity>,
        lifetimeReviews: Int,
        activeDays: Int,
        currentStreakDays: Int,
        longestStreakDays: Int,
        limits: DailyLimits,
        nowEpochDay: Int,
        todayEndMillis: Long
    ): DashboardSnapshot {
        val new = cards.count { it.cardState == null || it.cardState == CardState.NEW }
        val learning = cards.count { it.cardState == CardState.LEARNING }
        val review = cards.count { it.cardState == CardState.REVIEW }
        val mastered = cards.count { it.cardState == CardState.MASTERED }

        // Only cards that have been studied at least once can be *due*. An unscheduled or brand
        // new enrolment has nothing owed against it, and folding it in here is what made the
        // old "N Due" button count new words as overdue.
        val dueReviews = cards.count { row ->
            val state = row.cardState
            state != null &&
                state != CardState.NEW &&
                (row.dueDateMillis ?: Long.MAX_VALUE) <= todayEndMillis
        }

        val workload = Workload(
            dueReviews = dueReviews,
            newAvailable = new,
            learningInFlight = learning,
            newTakenToday = todayRow?.newWordsIntroduced ?: 0,
            limits = limits
        )

        val progress = Progress(
            // `new` already counts unscheduled enrolments, so the collection size is the sum of
            // the four buckets rather than a separate count that could disagree with them.
            enrolled = new + learning + review + mastered,
            new = new,
            learning = learning,
            review = review,
            mastered = mastered,
            lifetimeReviews = lifetimeReviews,
            activeDays = activeDays,
            currentStreakDays = currentStreakDays,
            longestStreakDays = longestStreakDays,
            studiedToday = (todayRow?.reviewsCompleted ?: 0) > 0
        )

        // A card qualifies only once it has actually lapsed. Ranked by lapses first because a
        // word failed three times is harder than one failed once with a higher ease factor, then
        // by ease because a card the scheduler has been repeatedly penalised for is the one it
        // is least confident about. The id tie-breaker makes the order total, so the list does
        // not reshuffle between two refreshes of the same data.
        val ranked = difficult
            .filter { it.lapses > 0 }
            .sortedWith(
                compareByDescending<DifficultCard> { it.lapses }
                    .thenBy { it.easeFactor }
                    .thenByDescending { it.totalReviews }
                    .thenBy { it.userVocabularyId }
            )
            .take(DIFFICULT_LIMIT)

        return DashboardSnapshot(
            asOfEpochDay = nowEpochDay,
            workload = workload,
            progress = progress,
            today = todayRow,
            recentDays = recentDays.sortedBy { it.epochDay },
            difficult = ranked,
            recommendation = recommend(
                enrolled = progress.enrolled,
                workload = workload,
                difficultCount = ranked.size
            )
        )
    }

    /**
     * Picks the one thing to do next.
     *
     * The order encodes a teaching judgement, so it is stated rather than implied: reviews
     * before new words, because a due review is memory that is actively decaying while a new
     * word is merely unlearned. New words only when the learner's own daily quota allows it.
     * Practising a difficult word is a suggestion for someone already caught up, never a way to
     * avoid work that is genuinely due.
     */
    private fun recommend(
        enrolled: Int,
        workload: Workload,
        difficultCount: Int
    ): NextAction = when {
        enrolled == 0 -> NextAction(
            kind = NextAction.Kind.NOTHING_ENROLLED,
            headline = "Add your first word",
            detail = "Your collection is empty, so there is nothing to review yet.",
            cardCount = 0
        )

        workload.dueReviews > 0 -> NextAction(
            kind = NextAction.Kind.REVIEW_DUE,
            headline = "Review ${workload.dueReviews} due " + plural(workload.dueReviews, "card", "cards"),
            detail = "These are scheduled and past due. Reviewing them first protects what you " +
                "have already learned.",
            cardCount = workload.dueReviews
        )

        workload.newOffered > 0 -> NextAction(
            kind = NextAction.Kind.LEARN_NEW,
            headline = "Learn " + workload.newOffered + " new " + plural(workload.newOffered, "word", "words"),
            detail = "You are caught up on reviews. Your daily limit allows " +
                "${workload.newOffered} more today.",
            cardCount = workload.newOffered
        )

        difficultCount > 0 -> NextAction(
            kind = NextAction.Kind.PRACTICE_DIFFICULT,
            headline = "Caught up. Practise something hard",
            detail = "Nothing is due. $difficultCount " + plural(difficultCount, "word", "words") +
                " gave you trouble before.",
            cardCount = difficultCount
        )

        else -> NextAction(
            kind = NextAction.Kind.CAUGHT_UP,
            headline = "You are caught up",
            detail = "No reviews due and no new words in your collection.",
            cardCount = 0
        )
    }
}
