package com.example

import com.example.data.progress.BadgeRule
import com.example.data.progress.ProgressEngine
import com.example.data.progress.ProgressMetric
import com.example.data.progress.RatingTally
import com.example.data.srs.SrsRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gamification rules on their own.
 *
 * Every assertion here is about a property the design depends on - that XP cannot be farmed by
 * getting a card wrong, that a level only ever goes up, that re-running the award pass cannot
 * re-announce a badge, and that a badge nobody can measure is refused rather than awarded on a
 * zero. Those are the claims worth pinning, and they are only checkable while the arithmetic is
 * pure.
 */
class ProgressEngineTest {

    // ---- XP ---------------------------------------------------------------------------------------

    @Test
    fun `XP comes only from the rating, and is a fixed price per rating`() {
        assertEquals(1, ProgressEngine.xpFor(SrsRating.AGAIN))
        assertEquals(3, ProgressEngine.xpFor(SrsRating.HARD))
        assertEquals(5, ProgressEngine.xpFor(SrsRating.GOOD))
        assertEquals(7, ProgressEngine.xpFor(SrsRating.EASY))
    }

    @Test
    fun `failing a card earns the least, not the most`() {
        // If AGAIN paid the most, repeatedly failing one card would be the most profitable
        // thing a learner could do, and the ledger is a plain count of ratings.
        val again = ProgressEngine.xpFor(SrsRating.AGAIN)
        for (other in SrsRating.entries.filter { it != SrsRating.AGAIN }) {
            assertTrue(
                "AGAIN pays $again but ${other.name} pays ${ProgressEngine.xpFor(other)}",
                again < ProgressEngine.xpFor(other)
            )
        }
    }

    @Test
    fun `total XP is the sum of the tallies`() {
        val tallies = listOf(
            RatingTally(SrsRating.AGAIN, 3),
            RatingTally(SrsRating.HARD, 2),
            RatingTally(SrsRating.GOOD, 10),
            RatingTally(SrsRating.EASY, 4)
        )

        val expected = 3 * 1 + 2 * 3 + 10 * 5 + 4 * 7

        assertEquals(expected, ProgressEngine.totalXp(tallies))
    }

    @Test
    fun `a learner with no answers has no XP`() {
        assertEquals(0, ProgressEngine.totalXp(emptyList()))
    }

    @Test
    fun `a nonsensical tally cannot reduce XP`() {
        // A negative count would have to come from a subtraction bug, and letting it claw XP
        // back means a level can move down. The ledger is append-only, so this can only ever be
        // a bug, and a bug must not be able to take something away.
        val xp = ProgressEngine.totalXp(listOf(RatingTally(SrsRating.GOOD, 10), RatingTally(SrsRating.EASY, -5)))

        assertEquals(50, xp)
    }

    // ---- levels -----------------------------------------------------------------------------------

    @Test
    fun `level one begins at zero XP`() {
        assertEquals(0, ProgressEngine.totalXpForLevel(1))
        assertEquals(1, ProgressEngine.levelFor(0))
    }

    @Test
    fun `the level curve widens, and is strictly increasing`() {
        // From level 2 upwards, not from level 1. Level 1 begins at 0 XP by definition, so
        // comparing it against an initial 0 would assert that XP is required to reach the first
        // level, which is the opposite of what the learner is told.
        var previous = ProgressEngine.totalXpForLevel(1)
        for (level in 2..40) {
            val at = ProgressEngine.totalXpForLevel(level)
            assertTrue("level $level begins at $at, not above level ${level - 1} at $previous", at > previous)
            previous = at
        }
    }

    @Test
    fun `a level never goes down as XP goes up`() {
        var previousLevel = 1
        var xp = 0
        while (xp < 20_000) {
            val level = ProgressEngine.levelFor(xp)
            assertTrue("at $xp XP the level fell from $previousLevel to $level", level >= previousLevel)
            previousLevel = level
            xp += 137
        }
    }

    @Test
    fun `the level is the one whose floor the XP has reached`() {
        for (level in 1..30) {
            val floor = ProgressEngine.totalXpForLevel(level)
            assertEquals("exactly $floor XP is level $level", level, ProgressEngine.levelFor(floor))
        }
    }

    @Test
    fun `one XP below a level is still the level below`() {
        for (level in 2..30) {
            val floor = ProgressEngine.totalXpForLevel(level)
            assertEquals(level - 1, ProgressEngine.levelFor(floor - 1))
        }
    }

    @Test
    fun `level progress is a fraction of the way through the level`() {
        val floor = ProgressEngine.totalXpForLevel(3)
        val ceiling = ProgressEngine.totalXpForLevel(4)

        val start = ProgressEngine.levelProgress(floor)
        val middle = ProgressEngine.levelProgress(floor + (ceiling - floor) / 2)
        val end = ProgressEngine.levelProgress(ceiling - 1)

        assertEquals(3, start.level)
        assertEquals(0f, start.fractionToNextLevel, 0.0001f)
        assertTrue(middle.fractionToNextLevel > 0.4f && middle.fractionToNextLevel < 0.6f)
        assertTrue(end.fractionToNextLevel > 0.9f)
    }

    @Test
    fun `level progress is always a real fraction of a bar`() {
        // A bar drawn from a value outside 0..1 either overflows its track or renders empty at
        // the wrong end, so the bound is worth asserting over a wide sweep rather than trusting.
        for (xp in 0..5_000 step 7) {
            val fraction = ProgressEngine.levelProgress(xp).fractionToNextLevel
            assertTrue("at $xp XP the fraction was $fraction", fraction in 0f..1f)
        }
    }

    @Test
    fun `an absurd total does not overflow into a negative level`() {
        val progress = ProgressEngine.levelProgress(Int.MAX_VALUE)

        assertTrue(progress.level > 1)
        assertTrue(progress.level.toLong() * ProgressEngine.XP_PER_LEVEL_BASE > 0)
        assertTrue(progress.fractionToNextLevel in 0f..1f)
    }

    @Test(timeout = 5_000)
    fun `the level search terminates on a saturated total`() {
        // This is the case that hung. `levelFor` compared against `totalXpForLevel`, which
        // saturates at `Int.MAX_VALUE` once the curve passes it, so for a total of
        // `Int.MAX_VALUE` the condition `totalXpForLevel(level + 1) <= total` was true at
        // every level and the loop never returned. `levelProgress` is called while drawing
        // the progress screen, so a caller passing a capped-or-overflowed total hung the UI
        // thread rather than throwing.
        //
        // The timeout is the assertion. Without it this test would have hung the whole suite
        // instead of failing, which is exactly what happened before the bound was added.
        val level = ProgressEngine.levelFor(Int.MAX_VALUE)

        assertTrue("expected a level above 1, got $level", level > 1)
    }

    @Test
    fun `the level search terminates on the largest level an Int can express`() {
        // The boundary the bug lived at. `Int.MAX_VALUE` XP falls inside level 6554's band,
        // so this is the last value for which the answer is a real level rather than the
        // ceiling. Asserting the exact number pins both the curve and the boundary.
        val expected = 6554

        assertEquals(expected, ProgressEngine.levelFor(Int.MAX_VALUE))
        // The saturated public accessor is allowed to stop growing, and does: the band from
        // 6555 on is unreachable, so there is nothing for it to report.
        assertEquals(Int.MAX_VALUE, ProgressEngine.totalXpForLevel(expected + 1))
        assertTrue(ProgressEngine.totalXpForLevel(expected) < Int.MAX_VALUE)
    }

    @Test
    fun `every Int total lands on a real level, and the level is monotonic in the total`() {
        // A sweep rather than a single value, because the failure was not "a wrong answer" —
        // it was "no answer", and that only happens above the saturation point. A few
        // ordinary totals are included to catch an off-by-one the boundary case would miss.
        val totals = listOf(0, 1, 49, 50, 99, 100, 300, 5_000, 1_000_000) +
            listOf(Int.MAX_VALUE - 2, Int.MAX_VALUE - 1, Int.MAX_VALUE)

        var previous = 0
        for (total in totals) {
            val level = ProgressEngine.levelFor(total)
            assertTrue("level $level for $total XP", level in 1..ProgressEngine.MAX_LEVEL)
            assertTrue("level went backwards at $total XP", level >= previous)
            previous = level
        }

        // A level must never begin above the total that reached it, or `xpIntoLevel` — the
        // numerator of the progress fraction — is negative and the bar renders empty.
        for (total in totals) {
            val progress = ProgressEngine.levelProgress(total)
            assertTrue(
                "xpIntoLevel was ${progress.xpIntoLevel} at $total XP (level ${progress.level})",
                progress.xpIntoLevel >= 0
            )
        }
    }

    @Test
    fun `a negative total is treated as zero rather than as a level below one`() {
        assertEquals(1, ProgressEngine.levelFor(-500))
        assertEquals(0, ProgressEngine.levelProgress(-500).totalXp)
    }

    // ---- milestones -------------------------------------------------------------------------------

    @Test
    fun `a milestone is a fact about the learner, stated as a number`() {
        val milestones = ProgressEngine.milestones(
            mapOf(ProgressMetric.ReviewsCompleted to 1_240, ProgressMetric.WordsMastered to 12)
        )

        assertEquals(2, milestones.size)
        assertEquals(1_240, milestones.single { it.metric == ProgressMetric.ReviewsCompleted }.value)
        assertEquals(12, milestones.single { it.metric == ProgressMetric.WordsMastered }.value)
    }

    @Test
    fun `milestones come out in a fixed order, not in the caller's order`() {
        // The screen renders a list, and a list whose order depends on how the caller happened to
        // build its map would reshuffle itself whenever two metrics were measured in a different
        // sequence. Keyed by metric rather than by position so the assertion stays meaningful.
        val forwards = ProgressEngine.milestones(
            mapOf(ProgressMetric.StreakDays to 4, ProgressMetric.WordsCollected to 30)
        )
        val backwards = ProgressEngine.milestones(
            mapOf(ProgressMetric.WordsCollected to 30, ProgressMetric.StreakDays to 4)
        )

        assertEquals(forwards.map { it.metric }, backwards.map { it.metric })
        assertEquals(
            listOf(ProgressMetric.StreakDays, ProgressMetric.WordsCollected),
            forwards.map { it.metric }
        )
    }

    @Test
    fun `a metric nobody measured has no milestone, and that is not the same as zero`() {
        val milestones = ProgressEngine.milestones(mapOf(ProgressMetric.WordsCollected to 0))

        // Zero was measured. StreakDays was not, and reporting it as 0 would claim the learner
        // has never studied, which is a different and much worse statement than "unknown".
        assertEquals(1, milestones.size)
        assertEquals(ProgressMetric.WordsCollected, milestones[0].metric)
    }

    @Test
    fun `a metric reads as a sentence fragment`() {
        assertEquals("1 review", ProgressMetric.ReviewsCompleted.describe(1))
        assertEquals("2 reviews", ProgressMetric.ReviewsCompleted.describe(2))
        assertEquals("0 reviews", ProgressMetric.ReviewsCompleted.describe(0))
        assertEquals("1,240 reviews", ProgressMetric.ReviewsCompleted.describe(1_240))
        assertEquals("1,000,000 sessions", ProgressMetric.SessionsCompleted.describe(1_000_000))
        assertEquals("1 word", ProgressMetric.WordsMastered.describe(1))
        assertEquals("1 day", ProgressMetric.StreakDays.describe(1))
        assertEquals("9 days", ProgressMetric.StreakDays.describe(9))
    }

    // ---- awarding ---------------------------------------------------------------------------------

    @Test
    fun `a badge unlocks when the metric reaches its threshold`() {
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rule(1, "WORDS_50", ProgressMetric.WordsCollected, threshold = 50)),
            metrics = mapOf(ProgressMetric.WordsCollected to 50),
            previouslyUnlocked = emptySet()
        )

        val decision = outcome.decisions.single()
        assertTrue(decision.earned)
        assertTrue(decision.newlyUnlocked)
        assertEquals(50, decision.progressValue)
        assertEquals(50, decision.threshold)
    }

    @Test
    fun `a badge does not unlock one short of its threshold`() {
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rule(1, "WORDS_50", ProgressMetric.WordsCollected, threshold = 50)),
            metrics = mapOf(ProgressMetric.WordsCollected to 49),
            previouslyUnlocked = emptySet()
        )

        val decision = outcome.decisions.single()
        assertFalse(decision.earned)
        assertFalse(decision.newlyUnlocked)
    }

    @Test
    fun `a locked badge still reports its progress`() {
        // Otherwise the badge is invisible until it is nearly earned, and the badges a learner
        // could realistically still get are the ones they cannot see.
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rule(1, "WORDS_50", ProgressMetric.WordsCollected, threshold = 50)),
            metrics = mapOf(ProgressMetric.WordsCollected to 3),
            previouslyUnlocked = emptySet()
        )

        assertEquals(3, outcome.decisions.single().progressValue)
    }

    @Test
    fun `re-running the award pass does not announce a badge again`() {
        val catalogue = listOf(rule(1, "STREAK_7", ProgressMetric.StreakDays, threshold = 7))

        val first = ProgressEngine.evaluate(catalogue, mapOf(ProgressMetric.StreakDays to 7), emptySet())
        val second = ProgressEngine.evaluate(catalogue, mapOf(ProgressMetric.StreakDays to 7), setOf(1L))

        assertEquals(1, first.newlyUnlocked.size)
        // Still earned - the badge is not taken back - but not news, so it is not announced.
        assertTrue(second.decisions.single().earned)
        assertTrue(second.newlyUnlocked.isEmpty())
    }

    @Test
    fun `a badge nobody measures is reported, not decided`() {
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rawRule(1, "MYSTERY", "MINUTES_STUDIED", threshold = 600)),
            metrics = ProgressMetric.entries.associateWith { 9999 },
            previouslyUnlocked = emptySet()
        )

        assertEquals(listOf("MINUTES_STUDIED"), outcome.unmappedMetricKeys)
        assertTrue(outcome.decisions.isEmpty())
    }

    @Test
    fun `a zero-threshold badge with an unmeasured metric is not handed out`() {
        // The failure this guards: a badge nobody can measure, with a threshold of zero, compared
        // against a defaulted metric of zero, unlocks on the first pass. The learner is given a
        // badge for a quantity that has never been counted.
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rawRule(1, "MYSTERY", "MINUTES_STUDIED", threshold = 0)),
            metrics = mapOf(ProgressMetric.WordsCollected to 0),
            previouslyUnlocked = emptySet()
        )

        assertTrue(outcome.newlyUnlocked.isEmpty())
        assertTrue(outcome.decisions.isEmpty())
    }

    @Test
    fun `a zero-threshold badge with a measured metric does unlock`() {
        // The guard above must not become a blanket refusal: if the metric really is measured,
        // then a threshold of zero means the badge is genuinely due.
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rule(1, "REAL", ProgressMetric.SessionsCompleted, threshold = 0)),
            metrics = mapOf(ProgressMetric.SessionsCompleted to 0),
            previouslyUnlocked = emptySet()
        )

        assertTrue(outcome.decisions.single().newlyUnlocked)
    }

    @Test
    fun `a badge whose metric was not measured this pass is skipped rather than zeroed`() {
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rule(1, "REVIEWS_100", ProgressMetric.ReviewsCompleted, threshold = 100)),
            metrics = mapOf(ProgressMetric.WordsCollected to 10),
            previouslyUnlocked = emptySet()
        )

        assertTrue(outcome.decisions.isEmpty())
        assertTrue(
            "an unmeasured metric is not a mapping failure, so it must not be reported as one",
            outcome.unmappedMetricKeys.isEmpty()
        )
    }

    @Test
    fun `one unmapped key is reported once however many badges name it`() {
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(
                rawRule(1, "A", "SOMETHING_NEW", 1),
                rawRule(2, "B", "SOMETHING_NEW", 2)
            ),
            metrics = emptyMap(),
            previouslyUnlocked = emptySet()
        )

        assertEquals(listOf("SOMETHING_NEW"), outcome.unmappedMetricKeys)
    }

    @Test
    fun `the whole catalogue is decided in one pass`() {
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(
                rule(1, "STREAK_3", ProgressMetric.StreakDays, 3),
                rule(2, "STREAK_7", ProgressMetric.StreakDays, 7),
                rule(3, "REVIEWS_100", ProgressMetric.ReviewsCompleted, 100),
                rule(4, "WORDS_50", ProgressMetric.WordsCollected, 50)
            ),
            metrics = mapOf(
                ProgressMetric.StreakDays to 9,
                ProgressMetric.ReviewsCompleted to 100,
                ProgressMetric.WordsCollected to 4
            ),
            previouslyUnlocked = setOf(1L)
        )

        // Both streak badges are earned; the one already held is not news, the other is.
        assertEquals(listOf(2L, 3L), outcome.newlyUnlocked.map { it.achievementId })
    }

    @Test
    fun `a negative measurement is treated as zero, never as progress towards a badge`() {
        val outcome = ProgressEngine.evaluate(
            catalogue = listOf(rule(1, "REVIEWS_100", ProgressMetric.ReviewsCompleted, 100)),
            metrics = mapOf(ProgressMetric.ReviewsCompleted to -5),
            previouslyUnlocked = emptySet()
        )

        assertEquals(0, outcome.decisions.single().progressValue)
        assertFalse(outcome.decisions.single().earned)
    }

    // ---- session summary --------------------------------------------------------------------------

    @Test
    fun `a session with no answers has an unknown accuracy, not a perfect one`() {
        val summary = ProgressEngine.summariseSession(emptyList())

        assertNull(summary.accuracy)
        assertEquals(0, summary.answers)
        assertEquals(0, summary.xpEarned)
    }

    @Test
    fun `accuracy counts a failed recall as a failed recall`() {
        val summary = ProgressEngine.summariseSession(
            listOf(SrsRating.GOOD, SrsRating.AGAIN, SrsRating.GOOD, SrsRating.HARD)
        )

        assertEquals(4, summary.answers)
        assertEquals(3, summary.again + summary.hard + summary.good + summary.easy - 1)
        assertEquals(0.75f, summary.accuracy!!, 0.0001f)
    }

    @Test
    fun `the session XP is the sum of what each answer was worth`() {
        val summary = ProgressEngine.summariseSession(
            listOf(SrsRating.EASY, SrsRating.EASY, SrsRating.AGAIN)
        )

        assertEquals(7 + 7 + 1, summary.xpEarned)
    }

    @Test
    fun `an untimed session reports an unknown duration, not zero seconds`() {
        assertNull(ProgressEngine.summariseSession(listOf(SrsRating.GOOD)).durationMillis)
        assertNotNull(ProgressEngine.summariseSession(listOf(SrsRating.GOOD), durationMillis = 4_000L))
    }

    @Test
    fun `a session is summarised from its answers alone`() {
        val summary = ProgressEngine.summariseSession(
            listOf(SrsRating.AGAIN, SrsRating.AGAIN, SrsRating.HARD, SrsRating.GOOD, SrsRating.EASY)
        )

        assertEquals(5, summary.answers)
        assertEquals(2, summary.again)
        assertEquals(1, summary.hard)
        assertEquals(1, summary.good)
        assertEquals(1, summary.easy)
        assertEquals(0.6f, summary.accuracy!!, 0.0001f)
    }

    /** A catalogue row as the repository reads it: a badge names its metric by key, not by enum. */
    private fun rule(id: Long, code: String, metric: ProgressMetric, threshold: Int) =
        BadgeRule(id = id, code = code, metricKey = metric.key, threshold = threshold, tier = 1)

    /** A catalogue row naming a metric string, for the case where it names the wrong thing. */
    private fun rawRule(id: Long, code: String, metricKey: String, threshold: Int) =
        BadgeRule(id = id, code = code, metricKey = metricKey, threshold = threshold, tier = 1)
}
