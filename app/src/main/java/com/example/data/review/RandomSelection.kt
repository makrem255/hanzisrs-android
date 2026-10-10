package com.example.data.review

import com.example.data.model.StorageValues
import com.example.data.model.WordWithSrs
import kotlin.random.Random

/**
 * Whether the learner has actually studied this word rather than merely enrolled in it.
 *
 * A scheduling row is created the moment a word is enrolled, carrying state `NEW`, so the
 * presence of `srs` says nothing about whether anyone has ever answered a question about it. The
 * state is what separates "in the library" from "learned".
 */
val WordWithSrs.isLearned: Boolean
    get() = state != StorageValues.CardState.NEW.storageValue

/** Which pool a selection was drawn from. The screen reports this rather than guessing. */
enum class RandomWordPool {
    /** Words the learner has been graded on at least once. Always preferred when any exist. */
    Learned,

    /**
     * The learner's whole collection, used only when nothing has been graded yet.
     *
     * The alternative - refusing to run - would leave a learner with a full library staring at
     * an empty screen about "no learned vocabulary", having done nothing wrong. Showing what
     * they have, and letting the screen say these are words they have not studied yet, is both
     * truthful and useful. It is a note on a working feature, not an empty state.
     */
    AllEnrolled,
}

/** One selection from the learner's own vocabulary. */
data class RandomWordChoice(
    val word: WordWithSrs,
    val pool: RandomWordPool,
)

/**
 * Picks the next word for Random Review.
 *
 * Pure and deterministic given [random], so the whole selection policy is testable without a
 * database, a view model or a gesture. It reads only the caller's own enrolled words: there is
 * no fallback dictionary, no seeded content and no placeholder anywhere in this path, so what a
 * learner is asked to pronounce is always something they chose to learn.
 *
 * The three rules, in order:
 *
 *  1. **Prefer what has been studied.** Random Review is a recall exercise, and recalling a word
 *     you have met before is the point; quizzing a word enrolled thirty seconds ago is not.
 *  2. **Fall back to the whole collection** when nothing has been graded yet, rather than
 *     reporting an empty state to a learner who has words. See [RandomWordPool.AllEnrolled].
 *  3. **Do not repeat the last word immediately**, unless there is genuinely no other choice.
 *     With one word in the collection, excluding it would mean never showing anything, so the
 *     exclusion only applies when a second option exists.
 *
 * This deliberately does not touch the spaced-repetition scheduler. A word picked here is not
 * graded, not rescheduled and not written to `review_log`: Random Review is practice, and
 * recording a review for a word the learner was never asked to rate would silently change their
 * study plan from a feature that exists to give them a break from it.
 *
 * @param vocabulary the learner's enrolled words, in any order
 * @param excludeId enrolment id of the word shown most recently, or null for the first pick
 * @param random source of randomness, injected so tests can be deterministic
 * @return the chosen word, or null when [vocabulary] is empty
 */
fun pickRandomWord(
    vocabulary: List<WordWithSrs>,
    excludeId: Long?,
    random: Random = Random.Default,
): RandomWordChoice? = pickRandomWord(
    vocabulary = vocabulary,
    excludeIds = setOfNotNull(excludeId),
    random = random,
)

/**
 * Picks the next word for Random Review, avoiding every id in [excludeIds].
 *
 * The set form of the rule above: the caller keeps the recent-selection history and passes
 * the whole of it, and the exclusion applies whenever an eligible alternative exists. When the
 * exclusion would leave nothing to show, the pool is used whole rather than refusing to run.
 *
 * @param excludeIds enrolment ids to avoid, usually the recent-selection history
 */
fun pickRandomWord(
    vocabulary: List<WordWithSrs>,
    excludeIds: Set<Long>,
    random: Random = Random.Default,
): RandomWordChoice? {
    if (vocabulary.isEmpty()) return null

    val learned = vocabulary.filter { it.isLearned }
    val pool = learned.ifEmpty { vocabulary }
    val poolKind = if (learned.isEmpty()) RandomWordPool.AllEnrolled else RandomWordPool.Learned

    // Only exclude when there is somewhere else to go. With a single-word collection the
    // exclusion would empty the candidate list, and a learner with one word still deserves to
    // practise it.
    val candidates =
        if (pool.size > excludeIds.size && excludeIds.isNotEmpty()) {
            pool.filter { it.word.id !in excludeIds }
        } else {
            pool
        }
    val effective = candidates.ifEmpty { pool }

    return RandomWordChoice(
        word = effective[random.nextInt(effective.size)],
        pool = poolKind,
    )
}
