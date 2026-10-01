package com.example.data.dashboard

import com.example.data.model.StorageValues
import com.example.data.model.StorageValues.CardState

/**
 * One scheduling row, reduced to what the dashboard reasons about.
 *
 * [state] is null for an enrolment that has no `srs_state` row yet. That is a real and common
 * case, not an error: a word can be in the learner's collection before it has been scheduled.
 * Counting it as "new" is correct; counting it as "due" is not, because nothing is owed for a
 * card nobody has started.
 */
data class ScheduledCardRow(
    val userVocabularyId: Long,
    val state: String?,
    val dueDateMillis: Long?,
    val lapses: Int = 0,
    val easeFactor: Double = 2.5,
    val totalReviews: Int = 0
) {
    /** The state as a closed enum, or null when this enrolment is unscheduled. */
    val cardState: CardState? get() = StorageValues.CardState.fromStorage(state)
}

/**
 * A word the learner has demonstrably struggled with.
 *
 * Populated from a join, not guessed from a state: "difficult" is a claim about a specific
 * learner's history with a specific word, so it has to carry the word.
 */
data class DifficultCard(
    val userVocabularyId: Long,
    val vocabularyId: Long,
    val character: String,
    val pinyin: String,
    val meaning: String,
    val state: CardState,
    val lapses: Int,
    val easeFactor: Double,
    val totalReviews: Int
)

/**
 * The learner's own limits, which are also the daily goal.
 *
 * Read from the preferences the learner has already set rather than a new invented "goal"
 * column. The app already decides how much it intends to schedule in a day; a separate goal
 * would be a second, competing number.
 *
 * Reachable from the settings screen, which is where they became adjustable. Until then these
 * two numbers were stored, validated, migrated and read by every scheduling decision in the app
 * - and no learner could change them, so everyone studied on the same 10/60 for the life of their
 * account. [com.example.ui.viewmodel.MainViewModel.dailyLimits] is the editable face of this
 * type; it mirrors these fields rather than being a second definition of the same pair.
 */
data class DailyLimits(
    val newWords: Int,
    val reviews: Int
)

/** How much work is waiting. Answers "how much do I need to review?". */
data class Workload(
    /** Scheduled cards already studied at least once whose due date has passed. */
    val dueReviews: Int,
    /** Cards in the collection that have never been studied. */
    val newAvailable: Int,
    /** Cards currently mid-way through their learning steps. */
    val learningInFlight: Int,
    /** New words the learner has already taken today, against their limit. */
    val newTakenToday: Int,
    val limits: DailyLimits
) {
    val newLimitRemaining: Int get() = (limits.newWords - newTakenToday).coerceAtLeast(0)
    val newQuotaMet: Boolean get() = newTakenToday >= limits.newWords

    /**
     * New words offered today, honouring the limit.
     *
     * Zero when the quota is met, which is what stops the dashboard offering to teach words the
     * learner has already asked not to see today.
     */
    val newOffered: Int get() = if (newQuotaMet) 0 else newAvailable.coerceAtMost(newLimitRemaining)

    /** Everything queued for today, reviews first. */
    val totalQueued: Int get() = dueReviews + newOffered
}

/** One day of the learner's own history. */
data class DayActivity(
    val epochDay: Int,
    val reviewsCompleted: Int,
    val correctReviews: Int,
    val againReviews: Int,
    val newWordsIntroduced: Int,
    val newWordsMastered: Int,
    val studyMillis: Long
) {
    /** Reviews that carried an accuracy signal. */
    val attempts: Int get() = correctReviews + againReviews

    /**
     * Share of rated reviews that were not [com.example.data.srs.SrsRating.AGAIN].
     *
     * Null when nothing was rated. A day with no reviews has an accuracy of *unknown*, not of
     * zero and not of one hundred per cent; both of those would be numbers the learner never
     * earned, and rendering either is how a dashboard starts lying.
     */
    val accuracy: Double? get() = if (attempts == 0) null else correctReviews.toDouble() / attempts
}

/** A share of the collection in one scheduling state. */
data class StateShare(
    val state: CardState,
    val count: Int,
    /** 0..1, or null when nothing is enrolled and there is nothing to divide by. */
    val share: Float?
)

/** How far along the learner is. Answers "how am I progressing?". */
data class Progress(
    val enrolled: Int,
    val new: Int,
    val learning: Int,
    val review: Int,
    val mastered: Int,
    val lifetimeReviews: Int,
    val activeDays: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val studiedToday: Boolean
) {
    /** Cards the learner has actually studied at least once. */
    val wordsLearned: Int get() = new.let { learning + review + mastered }

    /** Share of the collection reached [CardState.MASTERED]; null when nothing is enrolled. */
    val masteryRate: Double? get() = if (enrolled == 0) null else mastered.toDouble() / enrolled

    val distribution: List<StateShare>
        get() = listOf(
            CardState.NEW to new,
            CardState.LEARNING to learning,
            CardState.REVIEW to review,
            CardState.MASTERED to mastered
        ).map { (state, count) ->
            StateShare(state, count, if (enrolled == 0) null else count.toFloat() / enrolled)
        }
}

/**
 * The single thing the learner should do next. Answers the last question.
 *
 * A [Kind] plus the numbers behind it, with the wording decided by
 * [com.example.data.dashboard.DashboardAggregator] rather than by a screen. A UI that writes
 * its own copy will eventually disagree with the numbers it is describing.
 */
data class NextAction(
    val kind: Kind,
    val headline: String,
    val detail: String,
    val cardCount: Int
) {
    enum class Kind {
        NOTHING_ENROLLED,
        REVIEW_DUE,
        LEARN_NEW,
        PRACTICE_DIFFICULT,
        CAUGHT_UP
    }

    val isStartable: Boolean get() = kind != Kind.NOTHING_ENROLLED
}

/**
 * Everything the dashboard shows, derived once from real rows.
 *
 * One immutable value rather than a bag of independent flows, so the screen cannot draw a
 * dashboard whose cards disagree with each other - a streak from yesterday next to a queue
 * count from a moment ago.
 */
data class DashboardSnapshot(
    val asOfEpochDay: Int,
    val workload: Workload,
    val progress: Progress,
    val today: DayActivity?,
    /** Oldest first, so a chart can read it left to right without re-sorting. */
    val recentDays: List<DayActivity>,
    val difficult: List<DifficultCard>,
    val recommendation: NextAction
) {
    /**
     * True when there is genuinely nothing to do.
     *
     * Distinguished from "no data yet": a brand-new learner has no work because they have not
     * added any, which is a prompt to add a word, not a congratulation.
     */
    val isEmptyCollection: Boolean get() = progress.enrolled == 0

    /** True when there is real history to chart. */
    val hasHistory: Boolean get() = recentDays.any { it.reviewsCompleted > 0 }
}
