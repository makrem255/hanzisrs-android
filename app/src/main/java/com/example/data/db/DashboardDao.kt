package com.example.data.db

import androidx.room.Dao
import androidx.room.Query
import com.example.data.dashboard.DailyLimits
import com.example.data.dashboard.ScheduledCardRow
import kotlinx.coroutines.flow.Flow

/**
 * One word the learner has struggled with, as stored.
 *
 * `state` is a raw string rather than a `CardState` because Room has no built-in mapping from a
 * TEXT column to an enum. It is translated once, in the repository, through the same
 * [com.example.data.model.StorageValues.CardState.fromStorage] every other read uses - so a
 * stored value the enum does not recognise becomes "unknown" in one place instead of crashing
 * the dashboard.
 */
data class DifficultCardRow(
    val userVocabularyId: Long,
    val vocabularyId: Long,
    val character: String,
    val pinyin: String,
    val meaning: String,
    val state: String,
    val lapses: Int,
    val easeFactor: Double,
    val totalReviews: Int
)

/** The learner's own daily limits, read as a projection so a missing row cannot hide them. */
data class DailyLimitsRow(
    val dailyNewWordLimit: Int,
    val dailyReviewLimit: Int
)

/**
 * Reads that exist only to answer "how am I doing?".
 *
 * Separate from [SrsStateDao] because these are not scheduling operations. They read across the
 * content and learner tiers at once - a card joined to its character, its meaning and its
 * pinyin - and putting them in the scheduling DAO would give it a second, unrelated reason to
 * change.
 *
 * Every query here is a projection rather than a row the app then counts. Counting in the UI
 * meant loading every card a learner has ever enrolled in order to produce four integers.
 */
@Dao
interface DashboardDao {

    /**
     * Every card in the learner's collection, scheduled or not.
     *
     * `LEFT JOIN` from the enrolment rather than an inner join from `srs_state`, because a word
     * can be in the collection before it has been scheduled, and that word is real: it belongs
     * in the total and in the new count. An inner join would quietly understate both, and the
     * dashboard would report a smaller collection than the library shows.
     */
    @Query(
        """
        SELECT
            uv.id              AS userVocabularyId,
            s.state            AS state,
            s.dueDateMillis    AS dueDateMillis,
            COALESCE(s.lapses, 0)         AS lapses,
            COALESCE(s.easeFactor, 2.5)    AS easeFactor,
            COALESCE(s.totalReviews, 0)    AS totalReviews
        FROM user_vocabulary uv
        LEFT JOIN srs_state s
            ON s.userVocabularyId = uv.id AND s.userId = uv.userId
        WHERE uv.userId = :userId AND uv.status = 'ACTIVE'
        """
    )
    fun observeCards(userId: Long): Flow<List<ScheduledCardRow>>

    /**
     * The learner's hardest words, worst first.
     *
     * The `lapses > 0` predicate is the honest part. Without it this would list every card the
     * scheduler happens to have given a low ease factor to, including a word answered `EASY`
     * every time whose factor merely started low - which is not evidence of difficulty. A card
     * has to have actually failed to appear here.
     *
     * The tie-breakers make the list stable: two words with the same lapse count and ease
     * factor would otherwise be returned in an arbitrary order, and a list that reshuffles
     * between refreshes is one the learner cannot recognise.
     */
    @Query(
        """
        SELECT
            uv.id              AS userVocabularyId,
            v.id               AS vocabularyId,
            c.character        AS character,
            COALESCE(ps.toneMarked, '') AS pinyin,
            v.meaning          AS meaning,
            s.state            AS state,
            s.lapses           AS lapses,
            s.easeFactor       AS easeFactor,
            s.totalReviews     AS totalReviews
        FROM user_vocabulary uv
        JOIN srs_state s ON s.userVocabularyId = uv.id AND s.userId = uv.userId
        JOIN vocabulary v ON v.id = uv.vocabularyId
        JOIN characters c ON c.id = v.characterId
        LEFT JOIN pinyin_syllables ps ON ps.id = v.pinyinId
        WHERE uv.userId = :userId
          AND uv.status = 'ACTIVE'
          AND s.lapses > 0
        ORDER BY s.lapses DESC, s.easeFactor ASC, s.totalReviews DESC, uv.id ASC
        LIMIT :limit
        """
    )
    fun observeDifficult(userId: Long, limit: Int): Flow<List<DifficultCardRow>>

    /**
     * The learner's daily limits, defaulted in SQL.
     *
     * A learner with no preferences row - possible if a profile was created before preferences
     * were introduced - must still get a dashboard, so the projection supplies the same defaults
     * as [com.example.data.model.UserPreferenceEntity] rather than returning nulls the screen
     * would have to guess at.
     */
    @Query(
        """
        SELECT
            COALESCE(p.dailyNewWordLimit, 10) AS dailyNewWordLimit,
            COALESCE(p.dailyReviewLimit, 60)  AS dailyReviewLimit
        FROM (SELECT 1) AS anchor
        LEFT JOIN user_preferences p ON p.userId = :userId
        """
    )
    fun observeLimits(userId: Long): Flow<DailyLimitsRow>
}

/** Kept next to the DAO so the projection and its use are read together. */
fun DailyLimitsRow.toDailyLimits(): DailyLimits =
    DailyLimits(newWords = dailyNewWordLimit, reviews = dailyReviewLimit)
