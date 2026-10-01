package com.example.ui.screens

import com.example.data.model.StorageValues

/**
 * The learner-facing name of a card's scheduling state.
 *
 * ## Why this exists
 *
 * Three screens rendered `WordWithSrs.state` directly: the library row, the word-detail sheet
 * and the review card's status badge. That property is the raw storage text, and
 * [StorageValues.CardState] stores it as `NEW`, `LEARNING`, `REVIEW`, `MASTERED` - so every
 * library row read `MASTERED - Interval: 12d`, and the most prominent badge on the app's
 * primary screen, the one the learner is looking at while deciding how hard a word was, read
 * `REVIEW`.
 *
 * `StorageValues` states its own reason for existing: it "gives readers a single place to map
 * storage text back to a closed set". These three sites were the readers that skipped it. The
 * cost is not only ugliness - a storage token in user-facing text reads as machine output, and
 * a learner cannot tell `REVIEW` (the state) from "review" (the act) or from the `Review` tab
 * they just tapped to get there.
 *
 * ## Vocabulary
 *
 * Deliberately the same words [Filter] uses for its chips, so a learner who filters to
 * "In progress" is then shown "In progress" on the row rather than being taught a second name
 * for the same set.
 *
 * The unknown case returns `null` for the same reason [com.example.ui.theme.srsStateColorOrNull]
 * does: a value this build does not recognise is reported as unrecognised, not rounded to the
 * nearest state that looks close. A caller renders it as `Unknown`.
 */
internal fun srsStateLabel(state: StorageValues.CardState): String = when (state) {
    StorageValues.CardState.NEW -> "New"
    StorageValues.CardState.LEARNING -> "In progress"
    StorageValues.CardState.REVIEW -> "Reviewing"
    StorageValues.CardState.MASTERED -> "Mastered"
}

/** As [srsStateLabel], for a state still held as its stored `String`. `null` if unrecognised. */
internal fun srsStateLabelOrNull(storedState: String?): String? =
    StorageValues.CardState.fromStorage(storedState)?.let(::srsStateLabel)