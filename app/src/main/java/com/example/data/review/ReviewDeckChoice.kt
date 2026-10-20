package com.example.data.review

import com.example.data.model.WordWithSrs

/**
 * What an ordinary sitting covers: whatever is due, or the whole collection if nothing is.
 *
 * This was written out at two call sites, in two spellings that meant the same thing —
 * `dueWords.ifEmpty { userWords }` in the navigation layer and
 * `if (dueWords.isNotEmpty()) dueWords else allWords` in the review screen. Both read
 * `StateFlow.value` off the view model from a composable-scope lambda to do it, so the
 * decision about which cards a learner is shown lived outside the thing that owns the deck,
 * and a change to it — capping the sitting, or preferring learning cards to due ones — had
 * two places to be made and one of them was a navigation callback that no test reaches.
 *
 * The rule now lives here, stated once, and `MainViewModel.wordsForReview` delegates to it —
 * behaviour identical, but the rule has a test of its own ([ReviewDeckChoiceTest]) instead of
 * being reachable only through a view model. It is also the boundary weak-word practice must
 * not cross: [com.example.data.dashboard.WeakWordSelector] filters, and this is what the
 * ordinary deck looks like with no filtering at all.
 */
object ReviewDeckChoice {

    /** Due words first; the whole collection only when nothing is due; empty stays empty. */
    fun select(due: List<WordWithSrs>, all: List<WordWithSrs>): List<WordWithSrs> =
        if (due.isNotEmpty()) due else all
}
