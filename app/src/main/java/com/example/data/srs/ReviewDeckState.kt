package com.example.data.srs

/**
 * The review deck as a pure state machine: no Android, no database, no coroutines.
 *
 * The deck is where a learner's answer becomes a durable schedule, so the rules about *when* an
 * answer is allowed to be recorded belong somewhere they can be tested exhaustively rather than
 * spread across a gesture detector and a few `MutableStateFlow` writes. [SrsAlgorithm] is pure
 * for the same reason; this is the same idea applied to the interaction around it.
 *
 * Two rules are enforced here rather than in the UI, because a UI-only rule is a rule a second
 * screen can forget:
 *
 *  1. **A card cannot be rated before it has been revealed.** Recording a review for a recall the
 *     learner never attempted is fabricated learning history, and it silently poisons every
 *     retention figure derived from `review_log` later. The swipe gesture is a shortcut for
 *     Good/Hard, not a way to skip the attempt.
 *  2. **Only one write may be in flight per card.** Two rapid taps on "Good" would otherwise
 *     write two reviews and advance the deck twice, skipping a card the learner never saw.
 *     [beginRating] returns `null` for the second attempt, which is the only place that can be
 *     decided correctly because it is the only place that sees both attempts.
 *
 * Re-answering a card is deliberately *not* treated as a duplicate. Stepping back to a card and
 * rating it again is a real, second graded event - it is how a learner relearns something they
 * got wrong - so it is logged, counted in [answersGiven], and the new rating replaces the old.
 * What is forbidden is the accidental double-tap, which is what rule 2 stops.
 *
 * A third thing follows from treating a failure as real rather than final: a card rated
 * [SrsRating.AGAIN] returns later in the same session ([completeRating]). The learner pressed
 * the button that says the word comes back in ten minutes, and the ten minutes they get is the
 * rest of this sitting rather than whatever they do next with their phone.
 */
data class ReviewDeckState(
    /**
     * Enrolment ids in presentation order, frozen when the session starts.
     *
     * Frozen deliberately. `srs_state.dueDateMillis` changes the instant a rating is written, so
     * a deck derived from the live due query reorders and shrinks underneath the learner: the
     * card under their finger moves, and the session can report itself finished early because
     * the list it is comparing against got shorter. The scheduler's output changing the deck is
     * correct; the deck *reordering itself* is not. Content is read live, this ordering is not.
     */
    val wordIds: List<Long>,
    /** Position within [wordIds], clamped to `0..wordIds.size`. */
    val index: Int = 0,
    /**
     * Whether the answer side of the current card is showing.
     *
     * Starts `true` for a card already answered this session, because stepping back to it to
     * re-read is a re-read of something the learner has already seen, not a fresh test.
     *
     * A card re-queued after `AGAIN` is the deliberate exception, via [relearnIds]: it has been
     * answered, but the answer was *wrong*, so showing the answer again would hand the learner
     * the recall it is supposed to be testing. It is posed face-down instead.
     */
    val revealed: Boolean = false,
    /** Enrolment id to the rating given for it in this session. Presence marks a card answered. */
    val answers: Map<Long, SrsRating> = emptyMap(),
    /**
     * Cards re-queued after a failed rating, awaiting a genuine second attempt.
     *
     * Membership means "answered, but owed another go" - the opposite of the assumption
     * [moveTo] would otherwise make from [answers] alone. Dropped as soon as the card is rated
     * again by any rating, because at that point the learner's next attempt has been had either
     * way.
     */
    val relearnIds: Set<Long> = emptySet(),
    /** True while a rating is being written. Blocks a second write for the same attempt. */
    val isRating: Boolean = false,
    /**
     * Every rating given this session, including re-answers to a card already answered.
     *
     * Distinct from [answeredCount], which counts *cards* covered. Progress is about coverage;
     * this is the honest answer count, and conflating the two would make a learner who
     * corrected a mis-tap look like they did more work than they did.
     */
    val answersGiven: Int = 0
) {

    /** The enrolment id under the learner, or null once the deck is exhausted. */
    val currentWordId: Long?
        get() = wordIds.getOrNull(index)

    /** Cards covered so far in this session. */
    val answeredCount: Int
        get() = answers.size

    val total: Int
        get() = wordIds.size

    val isFinished: Boolean
        get() = wordIds.isEmpty() || index >= wordIds.size

    /**
     * Fraction of the deck covered, for the progress bar.
     *
     * Clamped at both ends. A finished deck sits at `index == wordIds.size`, where the
     * unclamped ratio is `(size + 1) / size` - a bar drawn past 100% on the one screen where the
     * learner is most likely to be looking at it. An empty deck has no denominator at all.
     */
    val progress: Float
        get() = if (wordIds.isEmpty()) {
            0f
        } else {
            ((index + 1).toFloat() / wordIds.size.toFloat()).coerceIn(0f, 1f)
        }

    val positionLabel: String
        get() = "${(index + 1).coerceAtMost(wordIds.size)} of ${wordIds.size}"

    fun answerFor(wordId: Long): SrsRating? = answers[wordId]

    fun isAnswered(wordId: Long): Boolean = answers.containsKey(wordId)

    /**
     * The rating given to the current card this session, if any.
     *
     * The UI shows this in place of the interval previews for a card the learner has already
     * answered, so that re-visiting a card reports what actually happened to it instead of
     * displaying a guess about what would happen next.
     */
    val currentAnswer: SrsRating?
        get() = currentWordId?.let { answers[it] }

    /** True when the current card is eligible to be rated right now. */
    val canRate: Boolean
        get() = currentWordId != null && revealed && !isRating

    fun start(newWordIds: List<Long>): ReviewDeckState =
        ReviewDeckState(wordIds = newWordIds.distinct())

    /**
     * Reveals the current card.
     *
     * A no-op while a write is in flight: flipping the card away mid-write would leave the
     * learner looking at an unanswered question while their answer was being recorded.
     */
    fun reveal(): ReviewDeckState =
        if (isRating || revealed) this else copy(revealed = true)

    /** Toggles the answer side, which is what a tap on the card does. */
    fun toggleReveal(): ReviewDeckState =
        if (isRating) this else copy(revealed = !revealed)

    /**
     * Claims the right to write a rating for the current card.
     *
     * Returns `null` when the attempt must be refused:
     *  - a write is already in flight (the double-tap);
     *  - the deck is exhausted;
     *  - the card has not been revealed, so there is no recall to grade.
     *
     * The caller must treat `null` as "do nothing" and must not advance the deck. Returning the
     * new state rather than a Boolean keeps the whole transition in one value, so a partially
     * applied transition is not expressible.
     */
    fun beginRating(): ReviewDeckState? =
        if (!canRate) null else copy(isRating = true)

    /**
     * Applies a successful write and moves to the next card.
     *
     * If this was the final card, [index] lands on `wordIds.size`, which is what [isFinished]
     * reports; the card stays reachable by [previous] so a mis-tap at the end is still correctable.
     *
     * A card rated [SrsRating.AGAIN] is additionally **put back at the end of this session**.
     *
     * ## Why
     *
     * [SrsAlgorithm] schedules `AGAIN` for `now + 10 minutes`, and the rating button says so,
     * so the learner has been told this word is coming back shortly. Nothing brought it back.
     * The deck advanced, the sitting ended, and nothing in the app could re-open it: there is no
     * `androidx.work`, no `AlarmManager`, no `JobScheduler`, and `remindersEnabled` /
     * `reminderHour` are columns no code path reads. The word returned only if the learner
     * happened to reopen the app within those ten minutes.
     *
     * So the one rating in a spaced-repetition system that most needs a second look was the one
     * rating guaranteed never to get one - while the button on it advertised the visit. A
     * learner who pressed "Again" on a word they had just failed learned nothing more about it
     * from this app, and had been told they would.
     *
     * It goes to the **end**, not the next slot: the second attempt should come after some
     * distance rather than the same card reappearing in front of the eye. Rating the last card
     * therefore leaves the session running rather than finishing it.
     *
     * The failed attempt is still recorded - it is real graded history and belongs in
     * `review_log`. Re-queueing adds an attempt; it never retracts the mistake. The re-queued
     * card is posed face-down again via [relearnIds], because it has been answered but the
     * answer was wrong, and showing the answer would hand over the very recall being tested.
     */
    fun completeRating(rating: SrsRating): ReviewDeckState {
        val answered = currentWordId ?: return copy(isRating = false, revealed = false)
        val requeued = rating == SrsRating.AGAIN

        val deck = if (requeued) wordIds + answered else wordIds

        return copy(
            wordIds = deck,
            index = (index + 1).coerceAtMost(deck.size),
            revealed = false,
            answers = answers + (answered to rating),
            relearnIds = if (requeued) relearnIds + answered else relearnIds - answered,
            isRating = false,
            answersGiven = answersGiven + 1
        )
    }

    /**
     * Releases the in-flight claim after a rejected write, leaving the learner on the card.
     *
     * The deck must not advance: the answer they gave has not been recorded, and advancing would
     * discard it and leave the schedule claiming a review that never happened.
     */
    fun failRating(): ReviewDeckState = copy(isRating = false)

    /**
     * Steps forward without answering, for re-reading.
     *
     * Refused while a write is in flight, for the same reason [reveal] is: the answer being
     * written belongs to the card that is on screen, and [completeRating] advances from wherever
     * the index happens to be when the write lands. Stepping away first would make that
     * transition skip whichever card the learner had stepped to.
     *
     * Clamped at the end. The target card is revealed if it was already answered, so stepping
     * back into a card shows its answer rather than posing it again.
     *
     * Except a card in [relearnIds]: re-queued after a failure, so it is posed rather than
     * revealed, because the answer it was answered with was wrong and showing it would defeat
     * the second attempt [completeRating] queued it for.
     */
    fun next(): ReviewDeckState = if (isRating) this else moveTo(index + 1)

    /** Steps back to the previous card without altering any recorded answer. */
    fun previous(): ReviewDeckState = if (isRating) this else moveTo(index - 1)

    private fun moveTo(target: Int): ReviewDeckState {
        val clamped = target.coerceIn(0, wordIds.size)
        if (clamped == index) return this
        return copy(
            index = clamped,
            revealed = wordIds.getOrNull(clamped)?.let {
                answers.containsKey(it) && it !in relearnIds
            } ?: false
        )
    }

    /**
     * Advances past cards that are no longer present in the learner's collection.
     *
     * Content can disappear mid-session - a word deleted from another screen, or an enrolment
     * removed. Because the id list is frozen, a vanished card would otherwise be shown as a
     * blank card and, worse, still counted in the denominator. Only the leading run is skipped,
     * so a card removed further ahead is still skipped when the learner reaches it.
     */
    fun skipMissing(presentIds: Set<Long>): ReviewDeckState {
        if (presentIds.containsAll(wordIds)) return this
        var next = index
        while (next < wordIds.size && wordIds[next] !in presentIds) next++
        return if (next == index) this else moveTo(next)
    }
}
