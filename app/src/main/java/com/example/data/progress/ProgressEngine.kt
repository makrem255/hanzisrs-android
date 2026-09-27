package com.example.data.progress

import com.example.data.srs.SrsRating

/**
 * Turns measured activity into experience, levels, milestones and badge decisions.
 *
 * Pure: no database, no Android, no clock of its own. Everything it produces is a function of
 * arguments, which is the property that makes the parts worth arguing about testable - that XP
 * never decreases, that a level is monotonic, that re-running an award pass cannot re-announce
 * a badge, and that a badge naming a metric nobody measures is refused rather than guessed at.
 *
 * The rules this encodes are deliberately narrow. XP comes only from graded reviews, because a
 * graded review is the one thing a learner does that is unambiguously study; there is no XP for
 * opening the app, adding a word, or a daily check-in. Every award is a plain function of the
 * rating, so the number is predictable rather than a surprise. Nothing here penalises absence:
 * a level and a badge can only go up, and both are measured against the learner's own best
 * history so a missed day costs nothing already earned.
 */
object ProgressEngine {

    /**
     * XP for one graded answer.
     *
     * [SrsRating.AGAIN] earns the least rather than the most. Awarding a bonus for getting it
     * wrong would make repeatedly failing a card the most profitable thing a learner can do, and
     * the ledger is a plain count of ratings, so that is trivially reachable.
     */
    private val XP_BY_RATING: Map<SrsRating, Int> = mapOf(
        SrsRating.AGAIN to 1,
        SrsRating.HARD to 3,
        SrsRating.GOOD to 5,
        SrsRating.EASY to 7
    )

    /**
     * Base of the level curve, in XP.
     *
     * Level `n` begins at `XP_PER_LEVEL_BASE * (n - 1) * n`, so level 1 is 0, level 2 is 100,
     * level 3 is 300, and the gaps widen by [XP_PER_LEVEL_BASE] each time. Quadratic rather than
     * geometric because a geometric curve on an unbounded total has a level that eventually
     * cannot be reached in a lifetime, and a goal that is arithmetically impossible is a
     * demotivation dressed as progression.
     */
    const val XP_PER_LEVEL_BASE = 50

    /**
     * A hard stop for the level search.
     *
     * 10,000 levels begin at `50 * 9999 * 10000` = 4,999,500,000 XP, which is more than an
     * `Int` can hold, so no value of [totalXpForLevel]'s input domain can reach it. The
     * constant exists so [levelFor] is *provably* total rather than merely correct for
     * inputs a learner could actually produce. See that function for what happens without it.
     */
    const val MAX_LEVEL = 10_000

    /**
     * XP at which [level] begins, in `Long` and unclamped.
     *
     * Kept separate from [totalXpForLevel] because the public version saturates at
     * `Int.MAX_VALUE` for the convenience of callers that report a level's floor, and
     * [levelFor] must *not* see that saturation: a clamped value compares `<=` against any
     * `Int.MAX_VALUE` total forever. The search has to run against a number that keeps
     * growing.
     */
    private fun xpAtLevelLong(level: Int): Long =
        XP_PER_LEVEL_BASE.toLong() * (level - 1).toLong() * level.toLong()

    /** XP at which [level] begins. Level 1 begins at zero. Saturates rather than overflowing. */
    fun totalXpForLevel(level: Int): Int {
        val safe = level.coerceAtLeast(1)
        return xpAtLevelLong(safe).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** XP for a single answer of [rating]. */
    fun xpFor(rating: SrsRating): Int = XP_BY_RATING[rating] ?: 0

    /** Total XP across a set of answer tallies. */
    fun totalXp(tallies: List<RatingTally>): Int =
        tallies.sumOf { xpFor(it.rating).toLong() * it.count.coerceAtLeast(0) }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    /**
     * The level [totalXp] reaches.
     *
     * A loop rather than the closed form of the quadratic, because the closed form needs a
     * floating point square root followed by a correction to land on the right integer, and
     * this cannot be a unit test away from correct.
     *
     * **The loop is bounded, and it has to be.** It was written as
     * `while (totalXpForLevel(level + 1) <= safe) level++`, which never terminated for a total
     * above about 2.147 billion: `totalXpForLevel` saturates at `Int.MAX_VALUE` once the
     * curve passes it, and `Int.MAX_VALUE <= Int.MAX_VALUE` is true forever, so `level`
     * incremented without end. Any caller passing `Int.MAX_VALUE` — a corrupt XP total, a
     * future column summed without a cap, an overflow in the aggregator above this — hung
     * whichever thread it was on, and [levelProgress] is called while drawing the progress
     * screen, so that is the UI thread.
     *
     * The condition now runs against [xpAtLevelLong], which keeps growing, and stops at
     * [MAX_LEVEL], which is out of reach of any `Int`. The answer is unchanged for every
     * total below the saturation point — level 6554 is the last level an `Int` can hold XP
     * for — and the function is now total.
     */
    fun levelFor(totalXp: Int): Int {
        val safe = totalXp.coerceAtLeast(0).toLong()
        var level = 1
        while (level < MAX_LEVEL && xpAtLevelLong(level + 1) <= safe) {
            level++
        }
        return level
    }

    /** Total XP, level and position within it, for a learner with [totalXp]. */
    fun levelProgress(totalXp: Int): LevelProgress {
        val safe = totalXp.coerceAtLeast(0)
        val level = levelFor(safe)
        val floor = totalXpForLevel(level)
        val ceiling = totalXpForLevel(level + 1)
        return LevelProgress(
            totalXp = safe,
            level = level,
            xpIntoLevel = safe - floor,
            xpForNextLevel = (ceiling - floor).coerceAtLeast(1)
        )
    }

    /**
     * The facts a learner has actually accumulated.
     *
     * One entry per [ProgressMetric] that has been measured, so the screen never has to decide
     * whether a missing metric means zero. A metric with no measurement is simply absent, which
     * is different from a metric that measured zero.
     */
    fun milestones(metrics: Map<ProgressMetric, Int>): List<Milestone> =
        ProgressMetric.entries.mapNotNull { metric ->
            metrics[metric]?.let { Milestone(metric, it.coerceAtLeast(0)) }
        }

    /**
     * Evaluates every badge in [catalogue] against [metrics].
     *
     * @param previouslyUnlocked the badge ids already earned. A badge earned before is recorded
     *   as `earned` but not `newlyUnlocked`, so re-running the pass - which happens every time a
     *   session ends, and again whenever the app is opened - is silent unless something was
     *   genuinely crossed.
     * @return the decision per measurable badge, plus the catalogue keys no engine measures.
     */
    fun evaluate(
        catalogue: List<BadgeRule>,
        metrics: Map<ProgressMetric, Int>,
        previouslyUnlocked: Set<Long>
    ): AwardOutcome {
        val decisions = mutableListOf<AwardDecision>()
        val unmapped = mutableListOf<String>()

        for (badge in catalogue) {
            val metric = ProgressMetric.forKey(badge.metricKey)
            if (metric == null) {
                if (badge.metricKey !in unmapped) unmapped.add(badge.metricKey)
                continue
            }
            val measured = metrics[metric] ?: continue
            val value = measured.coerceAtLeast(0)
            val earned = value >= badge.threshold
            decisions += AwardDecision(
                achievementId = badge.id,
                code = badge.code,
                metric = metric,
                progressValue = value,
                threshold = badge.threshold,
                earned = earned,
                newlyUnlocked = earned && badge.id !in previouslyUnlocked
            )
        }

        return AwardOutcome(decisions, unmapped)
    }

    /**
     * What one sitting produced.
     *
     * @param ratings the ratings given, in the order they were given.
     * @param durationMillis wall-clock time if the session was timed, else null.
     *
     * `AGAIN` counts as an answer and as not correct. It is a failed recall, and the dashboard's
     * accuracy counts it the same way; two screens answering "how well did I do" differently
     * would be worse than either answer.
     */
    fun summariseSession(
        ratings: List<SrsRating>,
        durationMillis: Long? = null
    ): SessionSummary {
        val again = ratings.count { it == SrsRating.AGAIN }
        val hard = ratings.count { it == SrsRating.HARD }
        val good = ratings.count { it == SrsRating.GOOD }
        val easy = ratings.count { it == SrsRating.EASY }
        val answers = ratings.size
        val correct = hard + good + easy
        return SessionSummary(
            answers = answers,
            again = again,
            hard = hard,
            good = good,
            easy = easy,
            xpEarned = ratings.sumOf { xpFor(it) },
            accuracy = if (answers == 0) null else correct.toFloat() / answers,
            durationMillis = durationMillis
        )
    }
}
