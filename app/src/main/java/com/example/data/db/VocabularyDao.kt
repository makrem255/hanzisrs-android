package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.model.ExampleSentenceEntity
import com.example.data.model.LearningItemEntity
import com.example.data.model.VocabularyEntity
import com.example.data.model.WordWithSrsRow
import kotlinx.coroutines.flow.Flow

/**
 * Column list and joins behind every library read.
 *
 * Declared as a top-level `const` because Room needs a compile-time constant to splice into
 * `@Query`. The screens keep consuming [com.example.data.model.WordWithSrs], so these queries
 * project the normalised tables back into the flat shape they expect: `hasSrs` distinguishes
 * "no scheduling row" from "a scheduling row whose values all happen to be zero", and the
 * example sentence is picked once, deterministically, by preferring a verified sentence.
 */
private const val LIBRARY_PROJECTION = """
        SELECT
            uv.id                AS id,
            uv.userId            AS userId,
            uv.vocabularyId      AS vocabularyId,
            c.character          AS hanzi,
            ps.toneMarked        AS pinyin,
            -- Split out from `pinyin` because the tone-marked form alone is lossy: a
            -- screen cannot render a tone contour, show a neutral-tone flat mark, or
            -- offer a "tone N" label without a separate number, and none of that can be
            -- recovered from the accented string without re-parsing it.
            ps.toneNumber        AS toneNumber,
            -- A five-level contour in high/mid/low notation, straight from the syllable
            -- table. Kept as the stored string rather than a derived shape so the display
            -- never disagrees with the data.
            ps.toneContour       AS toneContour,
            v.meaning            AS meaning,
            v.partOfSpeech       AS partOfSpeech,
            lv.ordinal           AS hskLevel,
            c.radical            AS radical,
            c.structure          AS structure,
            v.strokeJson         AS strokeJson,
            v.tags               AS tags,
            -- How this entry came to exist, and whether anybody has checked it. Both were
            -- being written (`AddWordScreen` records one, the migrations stamp another) and
            -- neither was readable, so the app could not tell the learner whether a stroke
            -- breakdown or an example sentence was curated, imported, or model-generated.
            v.provenance         AS provenance,
            v.isVerified         AS isVerified,
            uv.addedAt           AS addedAt,
            uv.isStarred         AS isStarred,
            ex.id                AS exampleSentenceId,
            COALESCE(ex.sentenceCn, '')     AS exampleCn,
            COALESCE(ex.sentencePinyin, '') AS examplePy,
            COALESCE(ex.sentenceEn, '')     AS exampleEn,
            CASE WHEN s.vocabularyId IS NULL THEN 0 ELSE 1 END AS hasSrs,
            COALESCE(s.userId, 0)              AS srsUserId,
            COALESCE(s.userVocabularyId, 0)    AS srsUserVocabularyId,
            COALESCE(s.vocabularyId, 0)        AS srsVocabularyId,
            COALESCE(s.intervalDays, 0)     AS intervalDays,
            COALESCE(s.repetitions, 0)      AS repetitions,
            COALESCE(s.easeFactor, 2.5)     AS easeFactor,
            COALESCE(s.dueDateMillis, 0)    AS dueDateMillis,
            COALESCE(s.lastReviewMillis, 0) AS lastReviewMillis,
            COALESCE(s.state, 'NEW')        AS state,
            COALESCE(s.lastRating, 0)       AS lastRating,
            COALESCE(s.lapses, 0)           AS lapses,
            COALESCE(s.totalReviews, 0)     AS totalReviews,
            COALESCE(s.schedulerVersion, 0) AS schedulerVersion
        FROM user_vocabulary uv
        JOIN vocabulary v          ON v.id = uv.vocabularyId
        JOIN characters c          ON c.id = v.characterId
        JOIN pinyin_syllables ps   ON ps.id = v.pinyinId
        JOIN learning_levels lv    ON lv.id = v.levelId
        LEFT JOIN srs_state s      ON s.userId = uv.userId AND s.vocabularyId = uv.vocabularyId
        LEFT JOIN example_sentences ex ON ex.id = (
            SELECT e.id FROM example_sentences e
            WHERE e.vocabularyId = v.id AND TRIM(e.sentenceCn) <> ''
            ORDER BY e.isVerified DESC, e.id ASC
            LIMIT 1
        )
"""

@Dao
interface VocabularyDao {

    @Query("SELECT * FROM vocabulary WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VocabularyEntity?

    /**
     * The content identity of a word: one character, one reading, one level.
     *
     * Returning the whole row rather than just its id lets the caller reuse the meaning and
     * tags of an entry that another learner already created, instead of storing a second
     * copy of the same word.
     */
    @Query(
        """
        SELECT * FROM vocabulary
        WHERE characterId = :characterId AND pinyinId = :pinyinId
        LIMIT 1
        """
    )
    suspend fun getByCharacterAndPinyin(characterId: Long, pinyinId: Long): VocabularyEntity?

    @Query(
        """
        SELECT v.* FROM vocabulary v
        JOIN characters c ON c.id = v.characterId
        WHERE c.character = :hanzi
        LIMIT 50
        """
    )
    suspend fun getByHanzi(hanzi: String): List<VocabularyEntity>

    /**
     * See [CharacterDao.insertIfAbsent]. REPLACE here would cascade-delete the shared
     * content out from under every learner enrolled in it.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(vocabulary: VocabularyEntity): Long

    @Query("SELECT COUNT(*) FROM vocabulary")
    suspend fun count(): Int

    // ---- Library read model ----------------------------------------------------------------

    @Transaction
    @Query("$LIBRARY_PROJECTION WHERE uv.userId = :userId AND uv.status = 'ACTIVE' ORDER BY uv.addedAt DESC, uv.id DESC")
    fun observeLibraryForUser(userId: Long): Flow<List<WordWithSrsRow>>

    @Transaction
    @Query(
        """
        $LIBRARY_PROJECTION
        WHERE uv.userId = :userId
          AND uv.status = 'ACTIVE'
          AND s.vocabularyId IS NOT NULL
          AND s.dueDateMillis <= :now
        ORDER BY s.dueDateMillis ASC
        """
    )
    fun observeDueForUser(userId: Long, now: Long): Flow<List<WordWithSrsRow>>

    /**
     * The same due set as [observeDueForUser], evaluated once.
     *
     * `now` is passed in rather than read with `strftime('now')` so the instant is the
     * caller's — testable, and identical to the one the reactive path would have used. The
     * predicate is otherwise deliberately the same three clauses: active enrolment, a
     * scheduling row present, and due. A word with no `srs_state` row is not "due", it is
     * un-scheduled, and `observeDueForUser` excludes it for the same reason this does.
     */
    @Transaction
    @Query(
        """
        $LIBRARY_PROJECTION
        WHERE uv.userId = :userId
          AND uv.status = 'ACTIVE'
          AND s.vocabularyId IS NOT NULL
          AND s.dueDateMillis <= :now
        ORDER BY s.dueDateMillis ASC
        """
    )
    suspend fun dueForUserNow(userId: Long, now: Long): List<WordWithSrsRow>

    @Transaction
    @Query("$LIBRARY_PROJECTION WHERE uv.id = :userVocabularyId LIMIT 1")
    suspend fun getRowById(userVocabularyId: Long): WordWithSrsRow?

    @Transaction
    @Query(
        """
        $LIBRARY_PROJECTION
        WHERE uv.userId = :userId AND c.character = :hanzi AND uv.status = 'ACTIVE'
        LIMIT 1
        """
    )
    suspend fun getRowByHanzi(userId: Long, hanzi: String): WordWithSrsRow?
}

@Dao
interface ExampleSentenceDao {

    @Query("SELECT * FROM example_sentences WHERE vocabularyId = :vocabularyId ORDER BY id ASC")
    suspend fun getForVocabulary(vocabularyId: Long): List<ExampleSentenceEntity>

    @Query(
        """
        SELECT * FROM example_sentences
        WHERE vocabularyId = :vocabularyId AND sentenceCn = :sentenceCn
        LIMIT 1
        """
    )
    suspend fun getByText(vocabularyId: Long, sentenceCn: String): ExampleSentenceEntity?

    /** IGNORE, so re-adding an identical sentence does not duplicate it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(sentence: ExampleSentenceEntity): Long
}

@Dao
interface LearningItemDao {

    @Query("SELECT * FROM learning_items WHERE levelId = :levelId ORDER BY position ASC")
    fun observeForLevel(levelId: Long): Flow<List<LearningItemEntity>>

    @Query("SELECT * FROM learning_items WHERE levelId = :levelId ORDER BY position ASC")
    suspend fun getForLevel(levelId: Long): List<LearningItemEntity>

    @Query(
        """
        SELECT li.* FROM learning_items li
        JOIN learning_levels lv ON lv.id = li.levelId
        WHERE lv.code = :levelCode
        ORDER BY li.position ASC
        """
    )
    suspend fun getForLevelCode(levelCode: String): List<LearningItemEntity>

    /**
     * IGNORE, so seeding a level twice is a no-op instead of a constraint failure on the
     * `(levelId, position)` and `(levelId, vocabularyId)` unique indices.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(items: List<LearningItemEntity>): List<Long>
}
