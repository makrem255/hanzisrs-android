package com.example

import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.model.WordView
import com.example.data.model.WordWithSrs
import com.example.data.review.RandomWordPool
import com.example.data.review.isLearned
import com.example.data.review.pickRandomWord
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection policy for Random Review.
 *
 * Pure by construction - `pickRandomWord` takes a list and a seeded [Random] - so everything here
 * is about the *policy*, with no database, view model or gesture in the way. The properties that
 * matter are the ones a learner would notice: it never invents a word, it prefers what has been
 * studied, it does not immediately repeat, and it never refuses to run when there is something
 * to show.
 */
class RandomSelectionTest {

    private fun word(
        id: Long,
        hanzi: String = "字$id",
        graded: Boolean = false,
    ): WordWithSrs {
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
                hanzi = hanzi,
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

    private val seed = Random(20261006L)

    // ---- the caller can always be answered ------------------------------------------------

    @Test
    fun `an empty library yields nothing rather than a placeholder`() {
        assertNull(
            "a learner with no words must not be handed invented content",
            pickRandomWord(emptyList(), excludeId = null, random = seed),
        )
    }

    @Test
    fun `every selection comes from the collection it was given`() {
        val library = (1L..40L).map { word(id = it, graded = it % 3 == 0L) }

        repeat(200) {
            val pick = pickRandomWord(library, excludeId = null, random = seed)
            assertNotNull(pick)
            assertTrue(
                "a selection outside the learner's library is content the learner never chose",
                library.any { it.word.id == pick!!.word.word.id },
            )
        }
    }

    @Test
    fun `a single-word library still produces that word`() {
        val only = word(id = 7L, graded = true)

        val pick = pickRandomWord(listOf(only), excludeId = only.word.id, random = seed)

        assertNotNull(
            "excluding the only word available would mean refusing to run forever",
            pick,
        )
        assertEquals(only.word.id, pick!!.word.word.id)
    }

    // ---- what has been studied is preferred ---------------------------------------------

    @Test
    fun `graded words are preferred whenever any exist`() {
        val learned = (1L..10L).map { word(id = it, graded = true) }
        val fresh = (11L..30L).map { word(id = it, graded = false) }
        val library = learned + fresh

        repeat(200) {
            val pick = pickRandomWord(library, excludeId = null, random = seed)!!
            assertTrue(
                "a recall exercise should draw from what the learner has actually met",
                pick.word.isLearned,
            )
            assertEquals(RandomWordPool.Learned, pick.pool)
        }
    }

    @Test
    fun `a library nothing has been graded in falls back to the whole collection`() {
        val fresh = (1L..12L).map { word(id = it, graded = false) }

        repeat(100) {
            val pick = pickRandomWord(fresh, excludeId = null, random = seed)!!
            assertTrue(
                "a learner with enrolled words must not be sent to an empty state",
                fresh.any { it.word.id == pick.word.word.id },
            )
            assertEquals(
                "the screen reports the pool it drew from, so it has to be told which one",
                RandomWordPool.AllEnrolled,
                pick.pool,
            )
        }
    }

    // ---- no immediate repeats ------------------------------------------------------------

    @Test
    fun `the last word is not shown again when another word exists`() {
        val last = word(id = 99L, graded = true)
        val others = (1L..10L).map { word(id = it, graded = true) }
        val library = others + last

        repeat(200) {
            val pick = pickRandomWord(library, excludeId = last.word.id, random = seed)!!
            assertTrue(
                "seeing the same word twice in a row makes the mode feel stuck",
                pick.word.word.id != last.word.id,
            )
        }
    }

    @Test
    fun `graded pool is respected while excluding the previous word`() {
        // The previous word is graded and the library also holds ungraded words: the exclusion
        // must not widen the pool to the ungraded half.
        val previous = word(id = 1L, graded = true)
        val learned = (2L..8L).map { word(id = it, graded = true) }
        val fresh = (9L..20L).map { word(id = it, graded = false) }

        repeat(200) {
            val pick = pickRandomWord(
                listOf(previous) + learned + fresh,
                excludeId = previous.word.id,
                random = seed,
            )!!
            assertTrue(
                "excluding a word must not quietly change which pool is being asked",
                pick.word.isLearned,
            )
            assertTrue(pick.word.word.id != previous.word.id)
        }
    }

    // ---- the pool is reported, not guessed -----------------------------------------------

    @Test
    fun `the reported pool describes where the word actually came from`() {
        val graded = (1L..5L).map { word(id = it, graded = true) }
        val ungraded = (6L..9L).map { word(id = it, graded = false) }

        val withGraded = pickRandomWord(graded + ungraded, null, seed)!!
        assertEquals(RandomWordPool.Learned, withGraded.pool)
        assertTrue(withGraded.word.isLearned)

        val noneGraded = pickRandomWord(ungraded, null, seed)!!
        assertEquals(RandomWordPool.AllEnrolled, noneGraded.pool)
        assertEquals(RandomWordPool.AllEnrolled.name, noneGraded.pool.name)
    }

    @Test
    fun `an ungraded word is distinguishable from a graded one`() {
        assertTrue(word(id = 1L, graded = true).isLearned)
        assertTrue(!word(id = 2L, graded = false).isLearned)
    }
}
