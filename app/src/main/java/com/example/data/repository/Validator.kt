package com.example.data.repository

import com.example.data.model.NewWordDraft
import com.example.data.model.StorageValues
import com.example.data.srs.PinyinAnalyzer
import com.example.data.srs.SrsAlgorithmLimits

/**
 * Why a write was refused, in terms the UI can show a learner.
 *
 * Room's entity DSL covers NOT NULL, foreign keys and unique indices but has no way to express
 * a CHECK constraint, so numeric ranges and closed vocabularies have to be enforced here. The
 * point of returning a value rather than throwing is that the add-word and settings screens
 * can put the message next to the field that caused it.
 */
sealed interface ValidationError {
    val message: String

    data class Blank(override val message: String) : ValidationError

    data class TooLong(override val message: String, val maxLength: Int) : ValidationError

    data class OutOfRange(override val message: String, val min: Int, val max: Int) : ValidationError

    data class NotAllowed(override val message: String, val allowed: List<String>) : ValidationError

    data class NotACharacter(override val message: String) : ValidationError

    data class NotAPinyinSyllable(override val message: String) : ValidationError
}

/**
 * The value rules the schema cannot state.
 *
 * Deliberately exhaustive rather than defensive: each function returns the first problem it
 * finds so the caller gets one actionable message instead of a list, and each bound matches a
 * limit that is meaningful for the product rather than an arbitrary guard.
 */
object Validator {

    /**
     * One CJK character or one short Chinese word, which is what a library entry can hold.
     *
     * Up to [MAX_HANZI_LENGTH] characters: the overwhelming majority of Chinese words are one
     * or two, and four covers the idioms. Longer pastes are refused rather than truncated,
     * because truncating a phrase into a word changes what the learner asked to study. The
     * gate counts code points and every unit must be CJK from the basic ranges; rarer
     * extension-plane characters are conservatively refused rather than half-stored.
     */
    private val CJK_RUN = Regex("^[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]+$")

    /** Longest word the library accepts, in characters. */
    const val MAX_HANZI_LENGTH = 4

    /** Guard against a runaway paste; sized for a four-character word's reading. */
    const val MAX_PINYIN_LENGTH = 32
    const val MAX_MEANING_LENGTH = 400
    const val MAX_SENTENCE_LENGTH = 300
    const val MAX_NOTE_LENGTH = 500
    const val MAX_BIO_LENGTH = 280

    const val MIN_LEVEL_ORDINAL = 1
    const val MAX_LEVEL_ORDINAL = 6
    const val MAX_DAILY_WORD_LIMIT = 200

    private val MIN_TTS_SPEED = 0.5
    private val MAX_TTS_SPEED = 2.0

    /**
     * Checks a draft before any row is written.
     *
     * Returning `null` means the draft is acceptable. This runs inside the same transaction as
     * the insert, so a word is never half-written.
     */
    fun validateNewWord(draft: NewWordDraft): ValidationError? {
        val hanzi = draft.hanzi.trim()
        if (hanzi.isEmpty()) {
            return ValidationError.Blank("Enter a Chinese character.")
        }
        if (hanzi.codePointCount(0, hanzi.length) > MAX_HANZI_LENGTH) {
            return ValidationError.TooLong(
                "Enter a single word of up to $MAX_HANZI_LENGTH characters, not a sentence.",
                maxLength = MAX_HANZI_LENGTH,
            )
        }
        if (!CJK_RUN.matches(hanzi)) {
            return ValidationError.NotACharacter("\"$hanzi\" is not Chinese text.")
        }

        val pinyin = draft.pinyin.trim()
        if (pinyin.isEmpty()) {
            return ValidationError.Blank("Enter the pinyin reading.")
        }
        if (pinyin.length > MAX_PINYIN_LENGTH) {
            return ValidationError.TooLong("That pinyin is too long.", MAX_PINYIN_LENGTH)
        }
        // A word's reading spans syllables ("lǎoshī"), so each whitespace-separated token is
        // checked as a syllable rather than forcing the whole reading through one.
        val tokens = pinyin.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any { !PinyinAnalyzer.isPinyinToken(it) }) {
            return ValidationError.NotAPinyinSyllable("\"$pinyin\" is not pinyin.")
        }

        val meaning = draft.meaning.trim()
        if (meaning.isEmpty()) {
            return ValidationError.Blank("Enter a meaning.")
        }
        if (meaning.length > MAX_MEANING_LENGTH) {
            return ValidationError.TooLong("That meaning is too long.", MAX_MEANING_LENGTH)
        }

        if (draft.hskLevel !in MIN_LEVEL_ORDINAL..MAX_LEVEL_ORDINAL) {
            return ValidationError.OutOfRange(
                "HSK level must be between $MIN_LEVEL_ORDINAL and $MAX_LEVEL_ORDINAL.",
                MIN_LEVEL_ORDINAL,
                MAX_LEVEL_ORDINAL
            )
        }

        if (draft.exampleCn.trim().length > MAX_SENTENCE_LENGTH) {
            return ValidationError.TooLong("That example sentence is too long.", MAX_SENTENCE_LENGTH)
        }
        if (draft.exampleEn.trim().length > MAX_SENTENCE_LENGTH) {
            return ValidationError.TooLong("That translation is too long.", MAX_SENTENCE_LENGTH)
        }

        if (StorageValues.VocabularySource.fromStorage(draft.source) == null) {
            return ValidationError.NotAllowed(
                "Unknown word source.",
                StorageValues.VocabularySource.entries.map { it.storageValue }
            )
        }
        if (StorageValues.ContentProvenance.fromStorage(draft.provenance) == null) {
            return ValidationError.NotAllowed(
                "Unknown content provenance.",
                StorageValues.ContentProvenance.entries.map { it.storageValue }
            )
        }
        return null
    }

    /** A scheduler result must round-trip into `srs_state` without loss or nonsense. */
    fun validateSchedulerState(
        state: String,
        lastRating: Int,
        intervalDays: Int,
        easeFactor: Double
    ): ValidationError? {
        if (StorageValues.CardState.fromStorage(state) == null) {
            return ValidationError.NotAllowed(
                "Unknown card state \"$state\".",
                StorageValues.CardState.entries.map { it.storageValue }
            )
        }
        if (lastRating !in 0..4) {
            return ValidationError.OutOfRange("Rating must be between 0 and 4.", 0, 4)
        }
        if (intervalDays < 0) {
            return ValidationError.OutOfRange("Interval cannot be negative.", 0, Int.MAX_VALUE)
        }
        if (easeFactor < SrsAlgorithmLimits.MIN_EASE_FACTOR ||
            easeFactor > SrsAlgorithmLimits.MAX_EASE_FACTOR
        ) {
            return ValidationError.OutOfRange(
                "Ease factor is outside the permitted band.",
                SrsAlgorithmLimits.MIN_EASE_FACTOR.toInt(),
                SrsAlgorithmLimits.MAX_EASE_FACTOR.toInt()
            )
        }
        return null
    }

    fun validatePreferences(
        themeMode: String,
        ttsSpeed: Double,
        dailyNewWordLimit: Int,
        dailyReviewLimit: Int,
        reminderHour: Int
    ): ValidationError? {
        if (StorageValues.ThemeMode.fromStorage(themeMode) == null) {
            return ValidationError.NotAllowed(
                "Unknown theme mode.",
                StorageValues.ThemeMode.entries.map { it.storageValue }
            )
        }
        if (ttsSpeed < MIN_TTS_SPEED || ttsSpeed > MAX_TTS_SPEED) {
            return ValidationError.OutOfRange(
                "Speech rate must be between $MIN_TTS_SPEED and $MAX_TTS_SPEED.",
                MIN_TTS_SPEED.toInt(),
                MAX_TTS_SPEED.toInt()
            )
        }
        if (dailyNewWordLimit !in 0..MAX_DAILY_WORD_LIMIT) {
            return ValidationError.OutOfRange(
                "Daily new word limit must be between 0 and $MAX_DAILY_WORD_LIMIT.",
                0,
                MAX_DAILY_WORD_LIMIT
            )
        }
        if (dailyReviewLimit !in 0..MAX_DAILY_WORD_LIMIT) {
            return ValidationError.OutOfRange(
                "Daily review limit must be between 0 and $MAX_DAILY_WORD_LIMIT.",
                0,
                MAX_DAILY_WORD_LIMIT
            )
        }
        if (reminderHour !in 0..23) {
            return ValidationError.OutOfRange("Reminder hour must be between 0 and 23.", 0, 23)
        }
        return null
    }

    fun validateNote(note: String): ValidationError? =
        if (note.length > MAX_NOTE_LENGTH) {
            ValidationError.TooLong("That note is too long.", MAX_NOTE_LENGTH)
        } else {
            null
        }

    fun validateBio(bio: String): ValidationError? =
        if (bio.length > MAX_BIO_LENGTH) {
            ValidationError.TooLong("That bio is too long.", MAX_BIO_LENGTH)
        } else {
            null
        }
}
