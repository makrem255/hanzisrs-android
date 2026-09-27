package com.example

import com.example.data.srs.ReviewDeckState
import com.example.data.srs.SrsRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deck's interaction rules, tested without Android, a database or a coroutine.
 *
 * The two rules that protect the integrity of the review history are covered from both sides:
 * that they refuse the bad input, and that they still allow the legitimate version of it. A
 * guard that cannot be satisfied would be as wrong as no guard at all.
 */
class ReviewDeckStateTest {

    private val tea = 101L
    private val water = 102L
    private val fire = 103L

    private fun deck(vararg ids: Long) = ReviewDeckState(wordIds = ids.toList())

    private fun ReviewDeckState.rateGood(): ReviewDeckState {
        val claimed = beginRating()
        assertNotNull("a revealed card must be rateable", claimed)
        return claimed!!.completeRating(SrsRating.GOOD)
    }

    // ---- a card cannot be graded before it is attempted ------------------------------------------------

    @Test
    fun `a new card cannot be rated before it is revealed`() {
        val fresh = deck(tea)

        assertFalse("a fresh card starts hidden", fresh.revealed)
        assertNull(
            "recording a review for a recall that never happened is fabricated history",
            fresh.beginRating()
        )
        assertFalse("a refused attempt must not put the card into a writing state", fresh.isRating)
    }

    @Test
    fun `revealing then rating is allowed`() {
        val revealed = deck(tea).reveal()

        assertTrue(revealed.revealed)
        assertTrue("a revealed card is rateable", revealed.canRate)
        assertNotNull(revealed.beginRating())
    }

    @Test
    fun `reveal is idempotent and does not start a write`() {
        val once = deck(tea).reveal()
        val twice = once.reveal()

        assertTrue(twice.revealed)
        assertFalse("revealing must not claim the write slot", twice.isRating)
        assertEquals(once, twice)
    }

    @Test
    fun `tapping the card toggles the answer side`() {
        val opened = deck(tea).toggleReveal()
        assertTrue(opened.revealed)

        val closed = opened.toggleReveal()
        assertFalse("tap to reveal also allows tap to hide", closed.revealed)
    }

    @Test
    fun `the card cannot be flipped while a write is in flight`() {
        val writing = deck(tea).reveal().beginRating()!!

        assertEquals(
            "tapping the card must not hide a question whose answer is being recorded",
            writing,
            writing.toggleReveal()
        )
        assertEquals("revealing is also refused mid-write", writing, writing.reveal())
        assertTrue("and the answer side stays visible", writing.toggleReveal().revealed)
    }

    // ---- duplicate review requests ---------------------------------------------------------------------

    @Test
    fun `a second rating attempt while a write is in flight is refused`() {
        val claimed = deck(tea).reveal().beginRating()!!

        assertNull(
            "two rapid taps would write two reviews and skip a card",
            claimed.beginRating()
        )
        assertTrue("the refused attempt must not disturb the in-flight write", claimed.isRating)
    }

    @Test
    fun `a write can be claimed again once the previous one has landed`() {
        val afterFirst = deck(tea).reveal().beginRating()!!.completeRating(SrsRating.GOOD)
        val second = deck(tea).reveal().beginRating()!!

        assertNotNull("the guard must release, or a card could only be rated once", second)
        assertEquals("one answer was given", 1, afterFirst.answersGiven)
    }

    @Test
    fun `a rejected write releases the claim and leaves the learner on the card`() {
        val claimed = deck(tea, water).reveal().beginRating()!!
        val recovered = claimed.failRating()

        assertFalse(recovered.isRating)
        assertEquals("the learner must stay on the card they answered", tea, recovered.currentWordId)
        assertTrue("the card stays revealed so the answer is not lost", recovered.revealed)
        assertEquals(
            "a refused write is not an answer",
            0,
            recovered.answersGiven
        )
        assertNotNull("the card can be answered again after a failure", recovered.beginRating())
    }

    // ---- first review, correct answer, incorrect answer ------------------------------------------------

    @Test
    fun `the first review of a new card advances the deck and is recorded`() {
        val fresh = deck(tea, water)
        val after = fresh.reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        assertEquals(water, after.currentWordId)
        assertEquals(SrsRating.GOOD, after.answerFor(tea))
        assertTrue(after.isAnswered(tea))
        assertFalse(after.isAnswered(water))
        assertEquals(1, after.answeredCount)
        assertEquals(1, after.answersGiven)
        assertEquals("the next card starts hidden", false, after.revealed)
    }

    @Test
    fun `an incorrect answer is recorded exactly like a correct one`() {
        val after = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.AGAIN)

        assertEquals(
            "the deck must not treat a lapse as unrecorded",
            SrsRating.AGAIN,
            after.answerFor(tea)
        )
        assertEquals(1, after.answeredCount)
        assertEquals(water, after.currentWordId)
    }

    @Test
    fun `every rating is recorded, not just the easy ones`() {
        // One card per rating, so each transition is a fresh card rather than a re-answer.
        val onePerRating = ReviewDeckState(
            wordIds = SrsRating.entries.map { it.value.toLong() }
        )
        var state = onePerRating
        SrsRating.entries.forEach { rating ->
            state = state.reveal().beginRating()!!.completeRating(rating)
        }

        assertEquals(SrsRating.AGAIN, state.answerFor(SrsRating.AGAIN.value.toLong()))
        assertEquals(SrsRating.HARD, state.answerFor(SrsRating.HARD.value.toLong()))
        assertEquals(SrsRating.GOOD, state.answerFor(SrsRating.GOOD.value.toLong()))
        assertEquals(SrsRating.EASY, state.answerFor(SrsRating.EASY.value.toLong()))
        assertEquals(4, state.total)
        assertTrue(state.isFinished)
    }

    // ---- repeated failures ------------------------------------------------------------------------------

    @Test
    fun `a card can be corrected by stepping back and answering again`() {
        val missed = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.AGAIN)
        val steppedBack = missed.previous()

        assertEquals("stepping back must reach the card just answered", tea, steppedBack.currentWordId)
        assertTrue(
            "re-reading a card already answered shows its answer rather than posing it again",
            steppedBack.revealed
        )
        assertEquals(SrsRating.AGAIN, steppedBack.currentAnswer)

        val corrected = steppedBack.beginRating()!!.completeRating(SrsRating.GOOD)

        assertEquals("the later answer supersedes the earlier one", SrsRating.GOOD, corrected.answerFor(tea))
        assertEquals("coverage counts cards, not attempts", 1, corrected.answeredCount)
        assertEquals("both attempts were real graded events", 2, corrected.answersGiven)
    }

    @Test
    fun `repeated failures keep accumulating as real answers`() {
        var state = deck(tea).reveal().beginRating()!!.completeRating(SrsRating.AGAIN)
        repeat(3) {
            state = state.previous().beginRating()!!.completeRating(SrsRating.AGAIN)
        }

        assertEquals(4, state.answersGiven)
        assertEquals("one card, however many attempts", 1, state.answeredCount)
        assertEquals(SrsRating.AGAIN, state.answerFor(tea))
    }

    // ---- previous / next navigation ---------------------------------------------------------------------

    @Test
    fun `previous and next move within the deck and clamp at both ends`() {
        val start = deck(tea, water, fire)

        assertEquals("cannot step back past the first card", 0, start.previous().index)
        assertEquals(fire, start.next().next().currentWordId)
        assertEquals(
            "cannot step forward past the last card",
            3,
            start.next().next().next().index
        )
    }

    @Test
    fun `navigating to an unanswered card leaves it hidden`() {
        val state = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        assertTrue("the answered card shows its answer when revisited", state.previous().revealed)
        assertFalse("an untouched card is still a test", state.next().next().revealed)
    }

    @Test
    fun `navigating never changes a recorded answer`() {
        val answered = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        val toured = answered.previous().next().previous()

        assertEquals(SrsRating.GOOD, toured.answerFor(tea))
        assertEquals(1, toured.answeredCount)
    }

    // ---- completion --------------------------------------------------------------------------------------

    @Test
    fun `answering the last card finishes the session`() {
        var state = deck(tea)
        state = state.reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        assertTrue(state.isFinished)
        assertNull("an exhausted deck has no current card", state.currentWordId)
        assertFalse("nothing can be rated once the deck is done", state.canRate)
    }

    @Test
    fun `the final card is still reachable after the session completes`() {
        val done = deck(tea).reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        val back = done.previous()

        assertEquals("a mis-tap on the last card must be correctable", tea, back.currentWordId)
        assertTrue(back.revealed)
        assertNotNull(back.beginRating())
    }

    @Test
    fun `an empty deck is finished rather than a division by zero`() {
        val empty = ReviewDeckState(wordIds = emptyList())

        assertTrue(empty.isFinished)
        assertFalse(empty.canRate)
        assertNull(empty.currentWordId)
        assertNull(empty.beginRating())
        assertEquals(0f, empty.progress, 0f)
        assertEquals(empty, empty.next())
        assertEquals(empty, empty.previous())
    }

    @Test
    fun `progress never exceeds one and reflects position, not answers`() {
        val state = deck(tea, water, fire)

        assertEquals(1f / 3f, state.progress, 1e-6f)
        val afterOne = state.reveal().beginRating()!!.completeRating(SrsRating.GOOD)
        assertEquals("progress tracks position in the deck, not answers given", 2f / 3f, afterOne.progress, 1e-6f)

        val allAnswered = afterOne.reveal().beginRating()!!.completeRating(SrsRating.GOOD)
            .reveal().beginRating()!!.completeRating(SrsRating.GOOD)
        assertEquals(1f, allAnswered.progress, 1e-6f)
        assertTrue("and it never divides past the end", allAnswered.previous().progress <= 1f)
    }

    @Test
    fun `the position label is clamped on the finished deck`() {
        val done = deck(tea).reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        assertEquals("1 of 1", deck(tea).positionLabel)
        assertEquals("1 of 1", done.positionLabel)
    }

    // ---- frozen ordering and vanished cards ---------------------------------------------------------------

    @Test
    fun `the session order is frozen and de-duplicated at the start`() {
        val started = ReviewDeckState(wordIds = emptyList()).start(listOf(water, tea, water, fire))

        assertEquals(listOf(water, tea, fire), started.wordIds)
        assertEquals(0, started.index)
        assertEquals(0, started.answersGiven)
        assertFalse(started.revealed)
    }

    @Test
    fun `a card removed from the collection mid-session is skipped and not counted`() {
        val state = deck(tea, water, fire).reveal().beginRating()!!.completeRating(SrsRating.GOOD)
        assertEquals(water, state.currentWordId)

        // The learner deletes the word they were about to review.
        val afterDeletion = state.skipMissing(setOf(tea, fire))

        assertEquals("the vanished card must not be shown blank", fire, afterDeletion.currentWordId)
        assertEquals("progress is measured against the session, not the survivors", 3, afterDeletion.total)
    }

    @Test
    fun `skipping does nothing when every card is still present`() {
        val state = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        assertEquals(state, state.skipMissing(setOf(tea, water)))
    }

    @Test
    fun `a word removed from the whole collection cannot leave the learner stuck`() {
        val state = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        val emptied = state.skipMissing(emptySet())

        assertTrue("every card is gone, so the session is over", emptied.isFinished)
    }

    // ---- state independence from scheduling ------------------------------------------------------------

    @Test
    fun `the deck applies the same rules to every card regardless of its scheduling state`() {
        // A newly introduced card, a card still in its learning steps, a review card and a
        // mature one are all just ids here. The deck must not branch on scheduling state: the
        // scheduler decides what a rating means, and the deck only decides when a rating is
        // allowed to exist.
        val ids = listOf(1L, 2L, 3L, 4L)
        var state = ReviewDeckState(wordIds = ids)

        ids.forEach {
            state = state.reveal().beginRating()!!.completeRating(SrsRating.GOOD)
        }

        assertEquals(4, state.answeredCount)
        assertTrue(state.isFinished)
    }
}
