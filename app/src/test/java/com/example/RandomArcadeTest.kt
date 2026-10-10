package com.example

import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.model.WordView
import com.example.data.model.WordWithSrs
import com.example.data.review.HANZI_WHITE_ARGB
import com.example.data.review.RANDOM_REVIEW_HISTORY_SIZE
import com.example.data.review.RANDOM_REVIEW_MAX_ATTEMPTS
import com.example.data.review.RANDOM_REVIEW_MIN_VOCABULARY
import com.example.data.review.SELECTION_FULL_MS
import com.example.data.review.SELECTION_FULL_STEPS
import com.example.data.review.SELECTION_PALETTE
import com.example.data.review.SELECTION_SHORT_MS
import com.example.data.review.SELECTION_SHORT_STEPS
import com.example.data.review.WHEEL_MIN_CONTRAST
import com.example.data.review.contrastRatio
import com.example.data.review.countsAsAttempt
import com.example.data.review.helpRevealed
import com.example.data.review.meetsReviewMinimum
import com.example.data.review.pickRandomWord
import com.example.data.review.selectionPool
import com.example.data.review.selectionSchedule
import com.example.data.review.updateRecentHistory
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Random Review arcade rules: the entry gate, the five-word memory, the wheel timing, the
 * wheel palette, and what counts as an attempt.
 *
 * All pure, all deterministic - the screen executes these but never invents them, so a failure
 * here names the rule that broke rather than the animation that happened to be running.
 */
class RandomArcadeTest {

    private fun word(id: Long, graded: Boolean = true): WordWithSrs {
        val srs = SrsStateEntity(
            userId = 1L,
            userVocabularyId = id,
            vocabularyId = id,
            state = if (graded) {
                StorageValues.CardState.LEARNING.storageValue
            } else {
                StorageValues.CardState.NEW.storageValue
            },
        )
        return WordWithSrs(
            word = WordView(
                id = id,
                userId = 1L,
                vocabularyId = id,
                hanzi = "字$id",
                pinyin = "zi$id",
                meaning = "meaning $id",
                hskLevel = 1,
                radical = "子",
                exampleCn = "",
                examplePy = "",
                exampleEn = "",
                strokeJson = "",
                tags = "",
                exampleSentenceId = null,
                addedAt = 0L,
                isStarred = false,
            ),
            srs = srs,
        )
    }

    // ---- the entry gate --------------------------------------------------------------------

    @Test
    fun `four words cannot start, five can`() {
        assertFalse(
            "the wheel must not spin on a collection too small to feel random",
            meetsReviewMinimum((1L..4L).map { word(it) }),
        )
        assertTrue(meetsReviewMinimum((1L..5L).map { word(it) }))
        assertTrue(meetsReviewMinimum((1L..40L).map { word(it) }))
    }

    @Test
    fun `the minimum is five`() {
        assertEquals(5, RANDOM_REVIEW_MIN_VOCABULARY)
    }

    // ---- the five-word memory ---------------------------------------------------------------

    @Test
    fun `history appends and caps at five newest`() {
        var history = emptyList<Long>()
        (1L..7L).forEach { history = updateRecentHistory(history, it) }

        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), history)
        assertEquals(RANDOM_REVIEW_HISTORY_SIZE, history.size)
    }

    @Test
    fun `a draw avoids the whole history while an alternative exists`() {
        val library = (1L..8L).map { word(it) }
        val history = listOf(1L, 2L, 3L, 4L, 5L)
        val seed = Random(7L)

        repeat(200) {
            val pick = pickRandomWord(library, history.toSet(), seed)!!
            assertTrue(
                "a recently shown word came back while three alternatives waited",
                pick.word.word.id !in history,
            )
        }
    }

    @Test
    fun `an exhausted pool resets instead of hanging`() {
        // Five words, all five in history: excluding would leave nothing, so the draw must
        // still answer - from the pool whole - rather than loop, freeze or return null.
        val library = (1L..5L).map { word(it) }
        val history = (1L..5L).toList()

        repeat(50) {
            assertNotNull(pickRandomWord(library, history.toSet(), Random(it.toLong())))
        }
    }

    @Test
    fun `single-word library still draws under a full history`() {
        val only = word(1L)
        val pick = pickRandomWord(listOf(only), setOf(1L, 2L, 3L, 4L, 5L), Random(1L))

        assertNotNull("refusing to run is worse than repeating the only word", pick)
        assertEquals(1L, pick!!.word.word.id)
    }

    // ---- the wheel timing --------------------------------------------------------------------

    @Test
    fun `the full wheel fits the five-second budget`() {
        val schedule = selectionSchedule(SELECTION_FULL_MS, SELECTION_FULL_STEPS)

        assertEquals(SELECTION_FULL_STEPS, schedule.size)
        assertTrue("every step must hold the screen for a positive time", schedule.all { it > 0 })
        assertTrue("the slowdown must actually slow down", schedule.zipWithNext().all { (a, b) -> b > a })
        assertTrue(
            "the whole animation including slowdown must stay under five seconds",
            schedule.sum() <= 5_000L,
        )
    }

    @Test
    fun `the short wheel is shorter but still decelerates`() {
        val full = selectionSchedule(SELECTION_FULL_MS, SELECTION_FULL_STEPS)
        val short = selectionSchedule(SELECTION_SHORT_MS, SELECTION_SHORT_STEPS)

        assertEquals(SELECTION_SHORT_STEPS, short.size)
        assertTrue(short.zipWithNext().all { (a, b) -> b > a })
        assertTrue(short.sum() < full.sum())
        assertTrue(short.sum() <= 5_000L)
    }

    // ---- the palette --------------------------------------------------------------------------

    @Test
    fun `the palette holds fifteen entries`() {
        assertEquals(15, SELECTION_PALETTE.size)
    }

    @Test
    fun `every entry holds white-hanzi contrast`() {
        SELECTION_PALETTE.forEachIndexed { i, color ->
            val ratio = contrastRatio(HANZI_WHITE_ARGB, color.argb)
            assertTrue(
                "entry $i has contrast %.2f, below the %.1f floor - white hanzi would be unreadable"
                    .format(ratio, WHEEL_MIN_CONTRAST),
                ratio >= WHEEL_MIN_CONTRAST,
            )
        }
    }

    // ---- what counts as an attempt ---------------------------------------------------------------

    @Test
    fun `only a heard utterance for the current word counts`() {
        assertTrue(countsAsAttempt(hypothesis = "学校", forWordId = 9L, currentWordId = 9L))
        assertFalse(
            "silence is not a mispronunciation",
            countsAsAttempt(hypothesis = null, forWordId = 9L, currentWordId = 9L),
        )
        assertFalse(
            "a blank result is not a mispronunciation",
            countsAsAttempt(hypothesis = "   ", forWordId = 9L, currentWordId = 9L),
        )
        assertFalse(
            "a late result for the previous word must not charge the new one",
            countsAsAttempt(hypothesis = "学校", forWordId = 9L, currentWordId = 10L),
        )
    }

    @Test
    fun `three attempts open help, two do not`() {
        assertEquals(3, RANDOM_REVIEW_MAX_ATTEMPTS)
        assertFalse(helpRevealed(0))
        assertFalse(helpRevealed(2))
        assertTrue(helpRevealed(3))
        assertTrue(helpRevealed(4))
    }

    // ---- the preview pool --------------------------------------------------------------------------

    @Test
    fun `the wheel previews the pool the draw comes from`() {
        val learned = (1L..3L).map { word(it, graded = true) }
        val fresh = (4L..9L).map { word(it, graded = false) }

        assertEquals(
            "cycling fresh words while drawing learned ones would be a bait-and-switch",
            learned.map { it.word.id }.toSet(),
            selectionPool(learned + fresh).map { it.word.id }.toSet(),
        )
        assertEquals(6, selectionPool(fresh).size)
    }
}
