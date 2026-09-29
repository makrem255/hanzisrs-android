package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ExampleSentenceEntity
import com.example.data.model.NewWordDraft
import com.example.data.model.PinyinSyllableEntity
import com.example.data.model.StorageValues
import com.example.data.model.UserVocabularyEntity
import com.example.data.model.VocabularyEntity
import com.example.data.model.WordWithSrs
import com.example.data.srs.PinyinAnalyzer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** The outcome of adding a word, distinguishing "you already have it" from "that is invalid". */
sealed interface SaveWordResult {
    /**
     * @param reusedExistingContent true when the character, reading and vocabulary rows already
     *   existed because another learner had added the same word, so this save created only the
     *   enrolment and its schedule. The UI can mention the shared deck without pretending the
     *   word was newly learned.
     */
    data class Saved(
        val userVocabularyId: Long,
        val vocabularyId: Long,
        val reusedExistingContent: Boolean
    ) : SaveWordResult

    data object Duplicate : SaveWordResult
    data class Invalid(val error: ValidationError) : SaveWordResult
}

/**
 * Owns the learner's word collection.
 *
 * The content a word needs — a glyph, a reading, a level, a definition, an example — is
 * resolved against the shared tables here rather than by the caller, so two learners adding
 * 好 at the same time converge on one `vocabulary` row and one `characters` row instead of
 * racing to create duplicates.
 */
class WordRepository(private val database: AppDatabase) {

    private val vocabularyDao = database.vocabularyDao()
    private val characterDao = database.characterDao()
    private val pinyinDao = database.pinyinDao()
    private val levelDao = database.learningLevelDao()
    private val exampleDao = database.exampleSentenceDao()
    private val enrollmentDao = database.userVocabularyDao()
    private val srsDao = database.srsStateDao()

    fun getWordsForUser(userId: Long): Flow<List<WordWithSrs>> =
        vocabularyDao.observeLibraryForUser(userId).map { rows -> rows.map { it.toWordWithSrs() } }

    /**
     * Cards that are due, re-evaluated once a minute.
     *
     * The filter lives in SQL so an overdue card is found by the `(userId, dueDateMillis)`
     * index instead of by loading the whole library and discarding most of it. Re-running the
     * query on a tick is what makes a card that becomes due while the deck is open appear
     * without the learner leaving the screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getDueWordsForUser(userId: Long): Flow<List<WordWithSrs>> =
        dueClock().flatMapLatest { now ->
            vocabularyDao.observeDueForUser(userId, now).map { rows -> rows.map { it.toWordWithSrs() } }
        }

    /**
     * How many cards are due, for the badge on the home tab.
     *
     * This answers a question the deck's own query cannot answer cheaply. `dueWords` projects
     * every due card through four inner joins and a correlated `ORDER BY isVerified DESC, id ASC
     * LIMIT 1` subquery over `example_sentences`, once per row, purely so a screen can take
     * `.size` of the result. Measured on a 118-character library that projection cost 6.7 ms
     * against 1.5 ms for the `SELECT COUNT(*)` used here - and the badge is subscribed at the app
     * container, so it was paying that on every review write and every tick of the minute clock,
     * on every screen, to render a two-digit number.
     *
     * `LearnerDao.observeDueCount` is a pure index range count on `srs_state(userId,
     * dueDateMillis)`, so its cost does not grow with the size of the learner's collection.
     *
     * It rides the same [dueClock] for the same reason: a card whose due time passes while the
     * app is open has to reach the badge without the learner navigating. The two therefore agree
     * on *when* a card counts as due, and `LibraryReadCostTest` holds them to the same number.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDueCount(userId: Long): Flow<Int> =
        dueClock().flatMapLatest { now -> srsDao.observeDueCount(userId, now) }

    /**
     * Cards that are due at the moment of the call, as a single answer.
     *
     * The reactive [getDueWordsForUser] is the right thing for anything on screen, but it
     * cannot answer "what is due *right now*": it is a `flatMapLatest` over a minute clock
     * and it only emits when a collector is subscribed and an emission is due. A caller
     * acting on a button — "review what is still due", at the end of a sitting — needs the
     * value the database holds now, because the reviews it just wrote may not have reached
     * the flow yet. Reading a flow's last emission in that situation returns the deck the
     * learner has just finished, which is the opposite of what was asked for.
     *
     * One query, no clock, no caching. This is a one-shot decision, not a subscription.
     */
    suspend fun getDueWordsForUserOnce(userId: Long): List<WordWithSrs> =
        vocabularyDao.dueForUserNow(userId, System.currentTimeMillis()).map { it.toWordWithSrs() }

    suspend fun findWordByHanzi(userId: Long, hanzi: String): WordWithSrs? =
        vocabularyDao.getRowByHanzi(userId, hanzi)?.toWordWithSrs()

    suspend fun getWordById(userVocabularyId: Long): WordWithSrs? =
        vocabularyDao.getRowById(userVocabularyId)?.toWordWithSrs()

    /**
     * Adds a word to a learner's collection and gives it an initial schedule.
     *
     * The whole thing is one transaction. That matters for two reasons: a partially resolved
     * word (a glyph with no vocabulary row) must never be observable, and the enrolment and its
     * `srs_state` row must appear together, since the foreign key between them means a
     * committed enrolment with no schedule would be a card the due queue silently skips.
     */
    suspend fun saveNewWordWithInitialSrs(
        draft: NewWordDraft,
        initialDueImmediate: Boolean = true
    ): SaveWordResult {
        Validator.validateNewWord(draft)?.let { return SaveWordResult.Invalid(it) }

        return database.withTransaction {
            val now = System.currentTimeMillis()

            val characterId = resolveCharacterId(draft.hanzi.trim(), now, draft.radical)
            val pinyinId = resolvePinyinId(draft.pinyin.trim(), now)
            val level = levelDao.getNearestToHsk(draft.hskLevel)
                ?: return@withTransaction SaveWordResult.Invalid(
                    ValidationError.NotAllowed(
                        "No learning level is configured, so this word cannot be filed.",
                        emptyList()
                    )
                )

            val (vocabularyId, reused) = resolveVocabularyId(
                draft = draft,
                characterId = characterId,
                pinyinId = pinyinId,
                levelId = level.id,
                now = now
            )

            if (enrollmentDao.getByVocabulary(draft.userId, vocabularyId) != null) {
                return@withTransaction SaveWordResult.Duplicate
            }

            val enrollmentId = try {
                enrollmentDao.insert(
                    UserVocabularyEntity(
                        userId = draft.userId,
                        vocabularyId = vocabularyId,
                        source = draft.source,
                        status = StorageValues.EnrollmentStatus.ACTIVE.storageValue
                    )
                )
            } catch (conflict: android.database.sqlite.SQLiteConstraintException) {
                // The pre-check above catches the ordinary case; this catches the race between
                // two concurrent adds of the same word.
                return@withTransaction SaveWordResult.Duplicate
            }

            val dueAt = if (initialDueImmediate) now else now + ONE_DAY_MILLIS
            val newRowId = srsDao.insertIfEnrolled(
                userId = draft.userId,
                userVocabularyId = enrollmentId,
                intervalDays = 1,
                repetitions = 0,
                easeFactor = DEFAULT_EASE_FACTOR,
                dueDateMillis = dueAt,
                now = now
            )
            if (newRowId <= 0L) {
                // Unreachable while the enrolment and this insert share a transaction, but a
                // silently unscheduled card is worse than a reported failure.
                throw IllegalStateException(
                    "Enrolment $enrollmentId was created without a scheduling row"
                )
            }

            SaveWordResult.Saved(enrollmentId, vocabularyId, reused)
        }
    }

    suspend fun setNote(userVocabularyId: Long, userId: Long, note: String): Boolean {
        Validator.validateNote(note)?.let { return false }
        return enrollmentDao.setNote(userVocabularyId, userId, note) > 0
    }

    suspend fun setStarred(userVocabularyId: Long, userId: Long, starred: Boolean): Boolean =
        enrollmentDao.setStarred(userVocabularyId, userId, starred) > 0

    suspend fun suspendEnrollment(userVocabularyId: Long, userId: Long): Boolean =
        enrollmentDao.setStatus(
            userVocabularyId,
            userId,
            StorageValues.EnrollmentStatus.SUSPENDED.storageValue
        ) > 0

    /**
     * Removes a word from a learner's collection.
     *
     * Scoped by `userId` as well as the row id, so a learner cannot delete somebody else's
     * enrolment by guessing its id. The enrolment's `srs_state` row goes with it via the
     * foreign key cascade; `review_log` points at the shared content instead and is kept, so
     * dropping a word does not erase the record of having studied it.
     */
    suspend fun deleteWord(userVocabularyId: Long, userId: Long): Boolean =
        enrollmentDao.deleteForUser(userVocabularyId, userId) > 0

    // ---- content resolution ----------------------------------------------------------------

    /**
     * Finds the glyph row, creating it on first sight of this character anywhere in the app.
     *
     * [radical] is the caller's value and used to be hard-coded to `""` here, which discarded it
     * silently: `NewWordDraft.radical` was validated, stored in the draft, and then never read
     * again, while the library projection read this column and rendered it. Every word added
     * through Add Word therefore showed a blank radical cell even after the learner had typed or
     * accepted one on the review-and-approve screen.
     *
     * Only applied when this call is the one creating the row. A character already known keeps
     * whatever radical it was first catalogued with, because the character is shared content: one
     * learner's first sighting of 水 should not overwrite the catalogue for everyone else.
     */
    private suspend fun resolveCharacterId(
        hanzi: String,
        now: Long,
        radical: String = ""
    ): Long {
        characterDao.getByCharacter(hanzi)?.let { return it.id }
        val codePoint = hanzi.codePointAt(0)
        val inserted = characterDao.insertIfAbsent(
            CharacterEntity(
                character = hanzi,
                codePoint = codePoint,
                radical = radical.trim(),
                createdAt = now
            )
        )
        // IGNORE returns -1 when a concurrent writer won the race; either way the row exists now.
        if (inserted > 0) return inserted
        return requireNotNull(characterDao.getByCharacter(hanzi) ?: characterDao.getByCodePoint(codePoint)) {
            "Character row for $hanzi disappeared immediately after insert"
        }.id
    }

    /** Finds the reading row, deriving the tone and the initial/final split from the text. */
    private suspend fun resolvePinyinId(pinyin: String, now: Long): Long {
        val analysis = PinyinAnalyzer.analyze(pinyin)
        pinyinDao.get(analysis.syllable, analysis.toneNumber)?.let { return it.id }

        val inserted = pinyinDao.insertIfAbsent(
            PinyinSyllableEntity(
                syllable = analysis.syllable,
                toneNumber = analysis.toneNumber,
                toneMarked = analysis.toneMarked,
                initial = analysis.initial,
                final = analysis.final,
                toneContour = analysis.toneContour,
                createdAt = now
            )
        )
        if (inserted > 0) return inserted
        return requireNotNull(pinyinDao.get(analysis.syllable, analysis.toneNumber)) {
            "Pinyin row for $pinyin disappeared immediately after insert"
        }.id
    }

    /**
     * Finds or creates the shared vocabulary row for this character-read pair.
     *
     * When the row already exists the stored meaning and stroke data win over the incoming
     * draft: the content tier is the single source of truth for a word, so two learners
     * describing the same word differently cannot fork it into two entries.
     */
    private suspend fun resolveVocabularyId(
        draft: NewWordDraft,
        characterId: Long,
        pinyinId: Long,
        levelId: Long,
        now: Long
    ): Pair<Long, Boolean> {
        vocabularyDao.getByCharacterAndPinyin(characterId, pinyinId)?.let { existing ->
            attachExample(existing.id, draft, now)
            return existing.id to true
        }

        val inserted = vocabularyDao.insertIfAbsent(
            VocabularyEntity(
                characterId = characterId,
                pinyinId = pinyinId,
                levelId = levelId,
                meaning = draft.meaning.trim(),
                strokeJson = draft.strokeJson,
                tags = draft.tags,
                provenance = draft.provenance,
                createdAt = now
            )
        )
        if (inserted > 0) {
            attachExample(inserted, draft, now)
            return inserted to false
        }

        val existing = requireNotNull(vocabularyDao.getByCharacterAndPinyin(characterId, pinyinId)) {
            "Vocabulary row for ${draft.hanzi}/${draft.pinyin} disappeared after insert"
        }
        attachExample(existing.id, draft, now)
        return existing.id to true
    }

    /**
     * Attaches the draft's example sentence, if it has one and the vocabulary entry does not
     * already carry that exact sentence.
     */
    private suspend fun attachExample(vocabularyId: Long, draft: NewWordDraft, now: Long) {
        val sentence = draft.exampleCn.trim()
        if (sentence.isEmpty()) return
        if (exampleDao.getByText(vocabularyId, sentence) != null) return
        exampleDao.insertIfAbsent(
            ExampleSentenceEntity(
                vocabularyId = vocabularyId,
                sentenceCn = sentence,
                sentencePinyin = draft.examplePy.trim(),
                sentenceEn = draft.exampleEn.trim(),
                provenance = draft.provenance,
                createdAt = now
            )
        )
    }

    private fun dueClock(): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(DUE_CLOCK_INTERVAL_MILLIS)
        }
    }

    companion object {
        const val ONE_DAY_MILLIS = 86_400_000L
        const val DEFAULT_EASE_FACTOR = 2.5
        private const val DUE_CLOCK_INTERVAL_MILLIS = 60_000L
    }
}
