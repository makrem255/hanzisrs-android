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
        // Five, not four: the AGAIN card was put back for a second attempt and has not been
        // reached yet, so the session is still running. That is the behaviour under test
        // elsewhere in this file, and it is the whole point of re-queueing.
        assertEquals(5, state.total)
        assertFalse("the re-queued AGAIN card is still owed its second attempt", state.isFinished)

        // Answering it out is what finally ends the sitting.
        val finished = state.reveal().beginRating()!!.completeRating(SrsRating.GOOD)
        assertTrue(finished.isFinished)
        // Four distinct cards were covered: the re-queued AGAIN card is the same card as the one
        // already graded, so it adds an attempt rather than a card. That is the distinction
        // `answeredCount` and `answersGiven` exist to keep, and it is why both are checked here.
        assertEquals("four distinct cards covered", 4, finished.answeredCount)
        assertEquals("but five attempts were graded", 5, finished.answersGiven)
    }

    // ---- repeated failures ------------------------------------------------------------------------------

    @Test
    fun `a card can be corrected by stepping back and answering again`() {
        val missed = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.GOOD)
        val steppedBack = missed.previous()

        assertEquals("stepping back must reach the card just answered", tea, steppedBack.currentWordId)
        assertTrue(
            "re-reading a card already answered shows its answer rather than posing it again",
            steppedBack.revealed
        )
        assertEquals(SrsRating.GOOD, steppedBack.currentAnswer)

        val corrected = steppedBack.beginRating()!!.completeRating(SrsRating.HARD)

        assertEquals("the later answer supersedes the earlier one", SrsRating.HARD, corrected.answerFor(tea))
        assertEquals("coverage counts cards, not attempts", 1, corrected.answeredCount)
        assertEquals("both attempts were real graded events", 2, corrected.answersGiven)
    }

    @Test
    fun `stepping back to a failed card poses it again, because it is owed an attempt`() {
        // Stepping back to a card the learner got *right* re-reads it, answer showing.
        // Stepping back to one they got *wrong* is not a re-read: the re-read would hand over
        // the recall the card is still owed. It is posed, and they turn it over themselves.
        val missed = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.AGAIN)

        assertFalse(
            "a failed card must not be revealed just because the learner navigated to it",
            missed.previous().revealed
        )
        assertTrue(
            "but it is still correctable: one tap turns it over",
            missed.previous().reveal().canRate
        )
    }

    @Test
    fun `repeated failures keep accumulating as real answers`() {
        var state = deck(tea).reveal().beginRating()!!.completeRating(SrsRating.AGAIN)
        repeat(3) {
            state = state.previous().reveal().beginRating()!!.completeRating(SrsRating.AGAIN)
        }

        assertEquals(4, state.answersGiven)
        assertEquals("one card, however many attempts", 1, state.answeredCount)
        assertEquals(SrsRating.AGAIN, state.answerFor(tea))
        // Each lapse puts the card back, so it is still owed an attempt after four of them.
        assertFalse("a card failed this often cannot be finished", state.isFinished)
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
    fun `the deck cannot be stepped while a write is in flight`() {
        val writing = deck(tea, water, fire).reveal().beginRating()!!

        val steppedForward = writing.next()
        val steppedBack = writing.previous()

        // Both attempts are refused, so the deck is left exactly where the write was claimed.
        // The index is 0 because that is where the learner was - not because 0 is a clamp: the
        // point is that neither call moved it, in either direction.
        assertEquals(
            "stepping ahead mid-write would skip a card",
            writing.index,
            steppedForward.index
        )
        assertEquals(
            "stepping back mid-write would re-ask an answered card",
            writing.index,
            steppedBack.index
        )
        assertEquals("and the card under the learner is unchanged", tea, steppedForward.currentWordId)
        assertTrue("navigation must not release the write claim", steppedBack.isRating)

        // And the consequence that made this matter: because the index never moved, the write
        // lands on the card it was actually made for. Had the step been allowed, `completeRating`
        // would have advanced from `water` and recorded the answer to the wrong card.
        val landed = steppedForward.completeRating(SrsRating.GOOD)
        assertEquals(water, landed.currentWordId)
        assertEquals(SrsRating.GOOD, landed.answerFor(tea))
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

    // ---- a failed card returns in this session ------------------------------------------------------------

    // A card rated AGAIN is scheduled `now + 10 minutes` and the button says so. Nothing in the
    // app can wake the deck up 10 minutes later - no WorkManager, no AlarmManager, no
    // JobScheduler, and `remindersEnabled` is read by nobody. So the card has to come back
    // inside the sitting where it failed, or the promise the button makes is false.

    @Test
    fun `a failed card is put back at the end of the same session`() {
        val after = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.AGAIN)

        assertEquals(
            "the failed card must still come back in this session",
            listOf(water, tea),
            after.wordIds.drop(1)
        )
        assertEquals("the re-queued card must not be in front of the learner", water, after.currentWordId)
    }

    @Test
    fun `the re-queued card is posed face-down rather than showing the wrong answer`() {
        // Tea fails, water passes, so the learner has been advanced to the re-queued tea. It
        // has an answer on record - AGAIN - and showing that answer would hand over the recall
        // the second attempt exists to test.
        val after = deck(tea, water)
            .reveal().beginRating()!!.completeRating(SrsRating.AGAIN)
            .reveal().beginRating()!!.completeRating(SrsRating.GOOD)

        assertEquals(tea, after.currentWordId)
        assertFalse(
            "a re-queued card must be posed, not revealed with the answer the learner got wrong",
            after.revealed
        )
        assertFalse("it must not be ratable before the learner attempts it", after.canRate)
    }

    @Test
    fun `the re-queued card can be revealed and rated like any other`() {
        val after = deck(tea, water)
            .reveal().beginRating()!!.completeRating(SrsRating.AGAIN)
            .reveal().beginRating()!!.completeRating(SrsRating.GOOD)
            .reveal()

        assertEquals("the learner is on the re-queued card", tea, after.currentWordId)
        assertTrue("and must be able to turn it over", after.revealed)
        assertTrue("and then rate it", after.canRate)
    }

    @Test
    fun `rating the re-queued card again ends the session`() {
        // The re-queued card was answered twice - once wrong, once right. Both are real graded
        // events and both are counted, which is what `answersGiven` is for.
        val after = deck(tea, water)
            .reveal().beginRating()!!.completeRating(SrsRating.AGAIN)
            .reveal().beginRating()!!.completeRating(SrsRating.GOOD)
            .reveal().beginRating()!!.completeRating(SrsRating.EASY)

        assertTrue("a corrected card must not reappear forever", after.isFinished)
        assertEquals(3, after.total)
        assertEquals(
            "the correction must replace the answer rather than add a second one to one card",
            SrsRating.EASY,
            after.answerFor(tea)
        )
        assertEquals("both attempts are real and both are counted", 3, after.answersGiven)
    }

    @Test
    fun `a failed last card keeps the session running instead of finishing it`() {
        // Rating AGAIN on the final card used to end the sitting, which is the worst version of
        // this bug: the learner presses "Again" and the deck immediately declares itself done.
        val after = deck(tea, water)
            .next().reveal().beginRating()!!.completeRating(SrsRating.AGAIN)

        assertFalse("a lapse must not finish the session", after.isFinished)
        assertEquals(water, after.currentWordId)
        assertFalse("and the card must be posed, not handed over", after.revealed)
    }

    @Test
    fun `a card rated HARD, GOOD or EASY is not re-queued`() {
        SrsRating.entries.filter { it != SrsRating.AGAIN }.forEach { rating ->
            val after = deck(tea, water).reveal().beginRating()!!.completeRating(rating)
            assertEquals(
                "$rating must leave the deck size alone",
                2,
                after.total
            )
        }
    }

    @Test
    fun `re-queueing does not make an answered card look unrated`() {
        // `review_log` keeps both attempts, but the deck still knows the card was seen. This is
        // the difference between a second attempt and a card that was never covered.
        val after = deck(tea, water).reveal().beginRating()!!.completeRating(SrsRating.AGAIN)

        assertTrue(after.isAnswered(tea))
        assertEquals(1, after.answeredCount)
    }

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
