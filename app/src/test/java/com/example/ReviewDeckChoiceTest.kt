package com.example

import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.model.WordView
import com.example.data.model.WordWithSrs
import com.example.data.review.ReviewDeckChoice
import com.example.data.srs.SrsRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordinary sitting's deck choice, with a test of its own at last.
 *
 * This rule lived for years only inside a private view-model method, untestable without a
 * database and a screen — which is exactly why it was silently duplicated across two call
 * sites that spelled it differently. It is stated once now, and these cases pin the two
 * halves: due words win, "nothing due" means the whole collection rather than nothing, and
 * an empty learner stays empty.
 *
 * The last case is the boundary of Phase C2's weak-word fix: [ReviewDeckChoice] must know
 * nothing about lapses. Ordinary review is the sitting that does *not* filter, and this
 * test is what fails if a filter ever migrates into it.
 */
class ReviewDeckChoiceTest {

    private fun word(id: Long, lapses: Int = 0): WordWithSrs = WordWithSrs(
        word = WordView(
            id = id,
            userId = 1L,
            vocabularyId = 100L + id,
            hanzi = "字$id",
            pinyin = "zi$id",
            meaning = "meaning $id",
            hskLevel = 1,
            radical = "",
            exampleCn = "",
            examplePy = "",
            exampleEn = "",
            strokeJson = "",
            tags = "",
            exampleSentenceId = null,
            addedAt = 0L,
            isStarred = false
        ),
        srs = SrsStateEntity(
            userId = 1L,
            userVocabularyId = id,
            vocabularyId = 100L + id,
            intervalDays = 1,
            repetitions = 1,
            easeFactor = 2.5,
            dueDateMillis = 0L,
            lastReviewMillis = 0L,
            state = StorageValues.CardState.REVIEW.storageValue,
            lastRating = SrsRating.GOOD.value,
            lapses = lapses,
            totalReviews = 1,
            schedulerVersion = 1
        )
    )

    @Test
    fun `due words are the sitting`() {
        val due = listOf(word(1), word(2))
        val all = listOf(word(1), word(2), word(3))
        assertEquals(
            "when something is due, the sitting is exactly that",
            listOf(1L, 2L),
            ReviewDeckChoice.select(due, all).map { it.word.id }
        )
    }

    @Test
    fun `the choice does not re-sort what it chose`() {
        // Ordering is the caller's business (the due query's, or the weak selector's); the
        // rule picks a list, it does not decorate one. A hidden sort here would silently
        // change every deck that feeds it.
        val due = listOf(word(3), word(1), word(2))
        assertEquals(
            listOf(3L, 1L, 2L),
            ReviewDeckChoice.select(due, all = listOf(word(1))).map { it.word.id }
        )
    }

    @Test
    fun `nothing due means the whole collection`() {
        val all = listOf(word(1), word(2), word(3))
        assertEquals(
            "a caught-up learner practises, they are not told to come back tomorrow",
            listOf(1L, 2L, 3L),
            ReviewDeckChoice.select(due = emptyList(), all = all).map { it.word.id }
        )
    }

    @Test
    fun `nothing at all is still nothing`() {
        assertTrue(ReviewDeckChoice.select(due = emptyList(), all = emptyList()).isEmpty())
    }

    @Test
    fun `weakness plays no part in an ordinary sitting`() {
        // The contrast that gives the weak-word fix its meaning: [com.example.data.dashboard.WeakWordSelector]
        // would reduce this deck to word 1 alone; the ordinary choice keeps both, because a
        // lapsed word that is due belongs in ordinary review just as much as a healthy one.
        val due = listOf(word(1, lapses = 4), word(2, lapses = 0))
        assertEquals(
            listOf(1L, 2L),
            ReviewDeckChoice.select(due, all = listOf(word(1), word(2))).map { it.word.id }
        )
        // And when nothing is due, the lapsed word is still not filtered out of the fallback.
        assertEquals(
            "the fallback is the whole collection, lapses and all",
            listOf(1L, 2L),
            ReviewDeckChoice.select(due = emptyList(), all = due).map { it.word.id }
        )
    }
}
