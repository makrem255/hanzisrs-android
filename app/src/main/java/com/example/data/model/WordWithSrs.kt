package com.example.data.model

/**
 * A word exactly as the existing screens consume it, assembled from the content tier and
 * the learner's own progress.
 *
 * This type deliberately keeps the field names and the `word` / `srs` shape the Compose
 * screens already use. What changed underneath is that the columns are now gathered by a
 * join across `user_vocabulary`, `vocabulary`, `characters`, `pinyin_syllables`,
 * `example_sentences` and `srs_state` instead of being read from one wide per-user row.
 */
data class WordWithSrs(
    val word: WordView,
    val srs: SrsStateEntity?
) {
    val isDue: Boolean
        get() {
            val due = srs?.dueDateMillis ?: 0L
            return due <= System.currentTimeMillis()
        }

    val state: String
        get() = srs?.state ?: StorageValues.CardState.NEW.storageValue
}

/**
 * The learner's view of one vocabulary entry.
 *
 * `id` is the `user_vocabulary` row id, i.e. the id of the enrolment rather than of the
 * shared content. That is the handle every per-learner write takes, so one learner can never
 * address another's enrolment by guessing an id.
 */
data class WordView(
    val id: Long,
    val userId: Long,
    val vocabularyId: Long,
    val hanzi: String,
    val pinyin: String,
    val meaning: String,
    val hskLevel: Int,
    val radical: String,
    val exampleCn: String,
    val examplePy: String,
    val exampleEn: String,
    val strokeJson: String,
    val tags: String,
    val exampleSentenceId: Long?,
    val addedAt: Long,
    val isStarred: Boolean,
    /**
     * Tone as an integer 1-4, or 5 for neutral, kept alongside [pinyin] rather than inside it.
     *
     * The tone-marked form is lossy: "ma" carries no mark for tone 5, and a screen cannot
     * draw a contour or label a syllable "tone 2" without a number. Defaulted to 0 so every
     * existing construction site and test fixture still compiles; 0 means "not recorded",
     * which is different from tone 5, and callers are expected to say so rather than render
     * a mark they do not have.
     */
    val toneNumber: Int = 0,
    /** Five-level contour from `pinyin_syllables.toneContour`, stored verbatim. */
    val toneContour: String = "",
    /** Grammatical category as recorded on the vocabulary row. Blank when unknown. */
    val partOfSpeech: String = "",
    /** Radical structure (左右, 上下, …) from `characters.structure`. Blank until known. */
    val structure: String = "",
    /** A [StorageValues.ContentProvenance] storage value, verbatim — not parsed here. */
    val provenance: String = StorageValues.ContentProvenance.UNKNOWN.storageValue,
    /** Whether a human has checked this entry's content. False is the honest default. */
    val isVerified: Boolean = false
) {
    /**
     * Where this entry came from, or null if the stored value is not one this build knows.
     *
     * Returns null rather than a fallback so a caller cannot accidentally render an
     * unrecognised provenance as though it were a known one — the whole point of the field
     * is that the app admits what it does not know.
     */
    val contentProvenance: StorageValues.ContentProvenance?
        get() = StorageValues.ContentProvenance.fromStorage(provenance)

    /** True when a stored tone is actually usable, i.e. one of the five real tones. */
    val hasTone: Boolean get() = toneNumber in 1..5
}

/**
 * Flat projection of the library query, one row per enrolled word.
 *
 * Room cannot reliably map a nullable `@Embedded` group from a LEFT JOIN, so the join
 * selects `srs.*` alongside the word columns with `COALESCE` defaults and reports whether a
 * scheduling row actually matched via [hasSrs]. [WordWithSrsRow.toWordWithSrs] then rebuilds
 * the nested shape in Kotlin, which keeps `srs` genuinely nullable.
 */
data class WordWithSrsRow(
    val id: Long,
    val userId: Long,
    val vocabularyId: Long,
    val hanzi: String,
    val pinyin: String,
    val toneNumber: Int,
    val toneContour: String,
    val meaning: String,
    val partOfSpeech: String,
    val hskLevel: Int,
    val radical: String,
    val structure: String,
    val strokeJson: String,
    val tags: String,
    val provenance: String,
    val isVerified: Boolean,
    val addedAt: Long,
    val isStarred: Boolean,
    val exampleSentenceId: Long?,
    val exampleCn: String,
    val examplePy: String,
    val exampleEn: String,
    val hasSrs: Boolean,
    val srsUserId: Long,
    val srsUserVocabularyId: Long,
    val srsVocabularyId: Long,
    val intervalDays: Int,
    val repetitions: Int,
    val easeFactor: Double,
    val dueDateMillis: Long,
    val lastReviewMillis: Long,
    val state: String,
    val lastRating: Int,
    val lapses: Int,
    val totalReviews: Int,
    val schedulerVersion: Int
) {
    fun toWordWithSrs(): WordWithSrs = WordWithSrs(
        word = WordView(
            id = id,
            userId = userId,
            vocabularyId = vocabularyId,
            hanzi = hanzi,
            pinyin = pinyin,
            toneNumber = toneNumber,
            toneContour = toneContour,
            meaning = meaning,
            partOfSpeech = partOfSpeech,
            hskLevel = hskLevel,
            radical = radical,
            structure = structure,
            exampleCn = exampleCn,
            examplePy = examplePy,
            exampleEn = exampleEn,
            strokeJson = strokeJson,
            tags = tags,
            provenance = provenance,
            isVerified = isVerified,
            exampleSentenceId = exampleSentenceId,
            addedAt = addedAt,
            isStarred = isStarred
        ),
        srs = if (hasSrs) {
            SrsStateEntity(
                userId = srsUserId,
                userVocabularyId = srsUserVocabularyId,
                vocabularyId = srsVocabularyId,
                intervalDays = intervalDays,
                repetitions = repetitions,
                easeFactor = easeFactor,
                dueDateMillis = dueDateMillis,
                lastReviewMillis = lastReviewMillis,
                state = state,
                lastRating = lastRating,
                lapses = lapses,
                totalReviews = totalReviews,
                schedulerVersion = schedulerVersion
            )
        } else {
            null
        }
    )
}

/**
 * The input to adding a word, before it is resolved against the shared content tier.
 *
 * The caller supplies the human-facing fields; the repository is responsible for finding or
 * creating the character, reading, level and vocabulary rows that back them.
 */
data class NewWordDraft(
    val userId: Long,
    val hanzi: String,
    val pinyin: String,
    val meaning: String,
    val hskLevel: Int = 1,
    val radical: String = "",
    val exampleCn: String = "",
    val examplePy: String = "",
    val exampleEn: String = "",
    val strokeJson: String = "",
    val tags: String = "",
    val source: String = StorageValues.VocabularySource.MANUAL.storageValue,
    val provenance: String = StorageValues.ContentProvenance.UNKNOWN.storageValue
)
