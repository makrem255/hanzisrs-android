package com.example.data.progress

import com.example.data.srs.SrsRating

/**
 * The quantities a learner's progress is measured in.
 *
 * These five keys are not chosen here: they are the keys the badge catalogue in
 * `ReferenceData.achievements` already names in its `metricKey` column, and that catalogue is
 * global content, not per-learner state. The enum exists so the code that measures progress and
 * the rows that declare a rule cannot drift apart silently - a badge naming a sixth key has no
 * engine behind it, and [ProgressEngine.evaluate] reports that as an unmapped key rather than
 * quietly treating the metric as zero.
 */
enum class ProgressMetric(val key: String) {
    /** Longest run of consecutive days with a graded review. */
    StreakDays("STREAK_DAYS"),

    /** Distinct study sessions that recorded at least one answer. */
    SessionsCompleted("SESSIONS_COMPLETED"),

    /** Words in the learner's active collection. */
    WordsCollected("WORDS_COLLECTED"),

    /** Cards the scheduler has moved to `MASTERED`. */
    WordsMastered("WORDS_MASTERED"),

    /** Answers recorded in the append-only review log. */
    ReviewsCompleted("REVIEWS_COMPLETED");

    /** Singular noun for one unit, e.g. `1 review`. */
    private val singular: String
        get() = when (this) {
            StreakDays -> "day"
            SessionsCompleted -> "session"
            WordsCollected, WordsMastered -> "word"
            ReviewsCompleted -> "review"
        }

    /** What this metric counts, for a label beside the number. */
    val displayName: String
        get() = when (this) {
            StreakDays -> "best streak"
            SessionsCompleted -> "sessions"
            WordsCollected -> "in your collection"
            WordsMastered -> "mastered"
            ReviewsCompleted -> "reviews answered"
        }

    /**
     * The milestone as a sentence fragment, e.g. `1,240 reviews` or `1 word`.
     *
     * Lives here rather than in a screen because four surfaces show the same five numbers and
     * four copies of "reviews" is four chances to ship a badge that says "1 reviews".
     */
    fun describe(value: Int): String {
        val safe = value.coerceAtLeast(0)
        val plural = if (safe == 1) "1" else groupDigits(safe)
        val noun = if (safe == 1) singular else "${singular}s"
        return "$plural $noun"
    }

    companion object {
        /** The metric a stored `metricKey` names, or null when no engine measures it. */
        fun forKey(key: String): ProgressMetric? = entries.firstOrNull { it.key == key }

        /** Thousands separators, without pulling in a locale-sensitive formatter. */
        private fun groupDigits(value: Int): String {
            val digits = value.toString()
            if (digits.length <= 3) return digits
            return buildString {
                digits.forEachIndexed { index, char ->
                    if (index > 0 && (digits.length - index) % 3 == 0) append(',')
                    append(char)
                }
            }
        }
    }
}

/**
 * How many answers of each rating the learner has given.
 *
 * A tally rather than a total, because XP is awarded per rating and a single review count
 * cannot answer "how much of that was correct". Only the counts are read from the database; the
 * arithmetic that turns them into a score lives in [ProgressEngine].
 */
data class RatingTally(val rating: SrsRating, val count: Int)

/**
 * One level of progress toward the next.
 *
 * [fractionToNextLevel] is a plain ratio rather than a nullable "unknown": unlike accuracy or a
 * share of the collection, a level is never genuinely unknown. If the engine can place a
 * learner at a level it can always say how far through that level they are.
 */
data class LevelProgress(
    val totalXp: Int,
    val level: Int,
    /** Experience already earned inside the current level. */
    val xpIntoLevel: Int,
    /** Experience the current level spans, so `xpIntoLevel / xpForNextLevel` is the bar. */
    val xpForNextLevel: Int
) {
    val fractionToNextLevel: Float
        get() = if (xpForNextLevel <= 0) 0f else (xpIntoLevel.toFloat() / xpForNextLevel).coerceIn(0f, 1f)
}

/**
 * A learned fact about the learner, stated as a number rather than as a reward.
 *
 * The distinction from an achievement is that a milestone never has an unlock moment. "You have
 * answered 1,240 reviews" is true of the learner whether or not anybody is watching, and it stays
 * true. That is the whole reason it is modelled separately: a fact that gets announced and then
 * withheld would be a fact pretending to be a prize.
 */
data class Milestone(val metric: ProgressMetric, val value: Int)

/**
 * What one sitting of study actually produced.
 *
 * Every field is measured rather than estimated, which is why [accuracy] and [durationMillis]
 * are nullable. A session with no answers has an unknown accuracy, not 100%; a session the app
 * did not time has an unknown duration, not zero. Both would otherwise be numbers on a summary
 * card that the learner never earned.
 */
data class SessionSummary(
    val answers: Int,
    val again: Int,
    val hard: Int,
    val good: Int,
    val easy: Int,
    val xpEarned: Int,
    /** Fraction of answers graded correct, or null when nothing was answered. */
    val accuracy: Float?,
    /** Wall-clock time, or null when the session was not timed. */
    val durationMillis: Long?
) {
    companion object {
        /** The honest summary of a sitting in which nothing was answered. */
        val EMPTY = SessionSummary(0, 0, 0, 0, 0, 0, null, null)
    }
}

/**
 * A badge definition, mirrored from the `achievements` table.
 *
 * The copy - title, description, icon - stays in the database and is not duplicated here. Only
 * the four fields the engine reasons about are carried, so this type cannot drift into a second
 * source of truth for what a badge says.
 */
data class BadgeRule(
    val id: Long,
    val code: String,
    val metricKey: String,
    val threshold: Int,
    val tier: Int
)

/**
 * The engine's verdict on one badge for one learner at one moment.
 *
 * [earned] is "the threshold is met now". [newlyUnlocked] is "the threshold is met now and was
 * not met last time this pass ran". They are separate because the UI must only announce the
 * second: a badge that re-announces itself every time the award pass runs is noise that trains
 * the learner to ignore announcements.
 */
data class AwardDecision(
    val achievementId: Long,
    val code: String,
    val metric: ProgressMetric,
    val progressValue: Int,
    val threshold: Int,
    val earned: Boolean,
    val newlyUnlocked: Boolean
)

/** The result of one award pass. */
data class AwardOutcome(
    val decisions: List<AwardDecision>,
    /**
     * `metricKey` values in the catalogue that no engine measures.
     *
     * Surfaced rather than swallowed. A badge naming a metric with no engine behind it cannot be
     * evaluated, and the two available fakes are both unacceptable: reporting zero progress
     * claims the learner is at 0 of N, and a zero threshold would then hand them the badge
     * immediately for a metric that was never measured.
     */
    val unmappedMetricKeys: List<String>
) {
    /** Only the badges crossed by this pass, in catalogue order. */
    val newlyUnlocked: List<AwardDecision> get() = decisions.filter { it.newlyUnlocked }
}

/** A badge as the learner sees it: its copy, its progress, and whether it is earned. */
data class AchievementState(
    val achievementId: Long,
    val code: String,
    val title: String,
    val description: String,
    val category: String,
    val tier: Int,
    val iconKey: String,
    val metricKey: String,
    val threshold: Int,
    /** Null when the catalogue names a metric no engine measures, so no number can be shown. */
    val progressValue: Int?,
    val unlockedAt: Long?
) {
    val isUnlocked: Boolean get() = unlockedAt != null
}

/** The learner's progress, as one value. */
data class LearnerProgress(
    val level: LevelProgress,
    val milestones: List<Milestone>,
    val achievements: List<AchievementState>,
    val unlockedCount: Int
) {
    companion object {
        /** Before anything is known. Not what a learner sees; what the screen shows while reading. */
        fun empty() = LearnerProgress(
            level = LevelProgress(0, 1, 0, ProgressEngine.XP_PER_LEVEL_BASE),
            milestones = emptyList(),
            achievements = emptyList(),
            unlockedCount = 0
        )
    }
}

/**
 * A badge the learner has just earned, with enough to render it and enough to not show it twice.
 *
 * Carries [id] of the `user_achievements` row as well as the badge's own id, because "seen" is
 * tracked on the learner's progress row and the feedback surface needs to mark that one row
 * without a second query keyed on the badge alone.
 */
data class UnlockedAward(
    val userAchievementId: Long,
    val achievementId: Long,
    val code: String,
    val title: String,
    val description: String,
    val iconKey: String,
    val tier: Int,
    val unlockedAt: Long
)
