package com.example.data.dashboard

import com.example.data.model.WordWithSrs

/**
 * The words a "Practice weak words" sitting should cover.
 *
 * The dashboard's "Worth another look" list and the button beneath it used to be unrelated:
 * the button started an ordinary sitting over whatever was due, so a learner who pressed it
 * got the same cards as "Start review" and the word they came to practise was only in there
 * if it happened to be due. This selector makes the button mean what it says — the sitting is
 * built from exactly the words the dashboard surfaced.
 *
 * "Weak" means one thing, the same thing [DashboardAggregator] means: a schedule row exists
 * and it has actually lapsed (`lapses > 0`). A word with no schedule, or one that has never
 * been missed, is not weak however low its ease factor looks — the learner has not failed it
 * once, so the app has no business calling it difficult.
 *
 * The ranking is deliberately the ranking inside [DashboardAggregator.aggregate] — lapses
 * descending, ease ascending, review count descending, enrolment id ascending — so the deck
 * presents "worst first" exactly as the list does, and the id tie-breaker keeps that order
 * total across refreshes. The cap is [DashboardAggregator.DIFFICULT_LIMIT] for the same
 * reason the dashboard caps: the sitting is the list the learner just read, not a longer
 * queue they were never shown. [com.example.WeakWordSelectorTest] locks both halves to the
 * aggregator — against fixtures and against real rows — so the two cannot drift apart
 * silently.
 */
object WeakWordSelector {

    /**
     * The weak subset of [words], worst first, capped at [DashboardAggregator.DIFFICULT_LIMIT].
     *
     * Pure: the collection is filtered, sorted and copied, never modified in place. The deck
     * machinery it feeds ([MainViewModel.startReviewSession]'s snapshot) does not change.
     */
    fun select(words: List<WordWithSrs>): List<WordWithSrs> =
        words
            // `?: 0` rather than `!!`: the filter has just required a schedule row, but the
            // type has not, and an unreachable default is cheaper than a crash on a row
            // shape a future query might legitimately produce.
            .filter { (it.srs?.lapses ?: 0) > 0 }
            .sortedWith(
                compareByDescending<WordWithSrs> { it.srs?.lapses ?: 0 }
                    .thenBy { it.srs?.easeFactor ?: Double.MAX_VALUE }
                    .thenByDescending { it.srs?.totalReviews ?: 0 }
                    .thenBy { it.word.id }
            )
            .take(DashboardAggregator.DIFFICULT_LIMIT)
}
