package com.example.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.example.data.db.AchievementProgressRow
import com.example.data.db.AppDatabase
import com.example.data.db.ProgressCountsRow
import com.example.data.db.RatingTallyRow
import com.example.data.progress.AchievementState
import com.example.data.progress.BadgeRule
import com.example.data.progress.LearnerProgress
import com.example.data.progress.ProgressEngine
import com.example.data.progress.ProgressMetric
import com.example.data.progress.RatingTally
import com.example.data.progress.SessionSummary
import com.example.data.progress.UnlockedAward
import com.example.data.srs.SrsRating
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Measures a learner's progress and awards the badges their activity has earned.
 *
 * The arithmetic is [ProgressEngine]'s and this class only supplies rows. That split is the whole
 * design: everything worth arguing about - how much an answer is worth, when a badge unlocks,
 * whether re-running the pass can announce the same badge twice - is decided by pure functions
 * that can be tested without a database, and this is left holding the queries and the write.
 *
 * Nothing is invented. Every metric is counted from the rows that describe the activity, and a
 * badge whose `metricKey` no engine measures is reported as unmapped rather than awarded on an
 * assumed zero.
 */
class GamificationRepository(
    private val database: AppDatabase
) {

    private val progressDao = database.progressDao()
    private val achievementDao = database.achievementDao()
    private val userAchievementDao = database.userAchievementDao()
    private val reviewLogDao = database.reviewLogDao()

    private companion object {
        const val TAG = "GamificationRepo"
    }

    /**
     * The learner's progress, as one value that updates as they study.
     *
     * The counts and the tallies are read as two flows because they answer different questions:
     * the tallies are a lifetime ledger and are what XP is summed from, while the counts can move
     * in both directions - a forgotten card leaves the mastered count, a deleted word leaves the
     * collection - and so have to be re-read rather than accumulated.
     */
    fun observeProgress(userId: Long): Flow<LearnerProgress> =
        combine(
            progressDao.observeProgressCounts(userId),
            reviewLogDao.observeRatingTallies(userId),
            progressDao.observeAchievementsWithProgress(userId)
        ) { counts, tallies, achievements ->
            val metrics = counts.toMetrics()
            val xp = ProgressEngine.totalXp(tallies.toRatingTallies())
            val states = achievements.map { it.toAchievementState(metrics) }
            LearnerProgress(
                level = ProgressEngine.levelProgress(xp),
                milestones = ProgressEngine.milestones(metrics),
                achievements = states,
                unlockedCount = states.count { it.isUnlocked }
            )
        }

    /**
     * Runs one award pass and returns only the badges this pass crossed.
     *
     * Called when a session ends rather than on every answer, so a learner working through twenty
     * cards sees one evaluation rather than twenty. The write is idempotent and the decision is
     * taken inside the same transaction, so calling this twice is safe and calling it when
     * nothing has changed is silent.
     *
     * @return the newly earned badges, with enough to render them. Empty when nothing new.
     */
    suspend fun evaluateAwards(userId: Long, now: Long): List<UnlockedAward> {
        val catalogue = achievementDao.getAllActive()
        if (catalogue.isEmpty()) return emptyList()

        // Read, decide and write as one transaction. The alternative - read the counts, decide
        // outside, then write - lets two concurrent passes both see a badge as locked and both
        // announce it, which trains the learner to distrust announcements.
        val result = database.withTransaction {
            val rules = catalogue.map {
                BadgeRule(
                    id = it.id,
                    code = it.code,
                    metricKey = it.metricKey,
                    threshold = it.thresholdValue,
                    tier = it.tier
                )
            }
            val metrics = progressDao.readProgressCounts(userId).toMetrics()
            val already = userAchievementDao.unlockedIdsForUser(userId).toSet()
            val outcome = ProgressEngine.evaluate(rules, metrics, already)

            for (decision in outcome.decisions) {
                userAchievementDao.recordProgress(
                    userId = userId,
                    achievementId = decision.achievementId,
                    progressValue = decision.progressValue,
                    threshold = decision.threshold,
                    now = now
                )
            }
            outcome
        }

        // Logged rather than shown. A badge naming a metric no engine measures cannot be
        // evaluated, and the two things that could be done about it in the UI are both lies:
        // showing "0 of 600" claims a measurement nobody took, and hiding it quietly means a
        // badge can sit in the catalogue forever with no sign it is unreachable. The log is where
        // a developer finds out, and the screen shows no number at all.
        if (result.unmappedMetricKeys.isNotEmpty()) {
            Log.w(
                TAG,
                "Badge catalogue for user $userId names metrics with no engine: " +
                    result.unmappedMetricKeys.joinToString()
            )
        }

        val newlyUnlockedIds = result.newlyUnlocked.map { it.achievementId to it.code }

        if (newlyUnlockedIds.isEmpty()) return emptyList()

        // Read the rows back rather than trusting the ids the upsert returned: the copy the UI
        // shows has to be the copy stored, and `user_achievements.id` is what `markSeen` needs.
        val copyByCode = catalogue.associateBy { it.code }
        return newlyUnlockedIds.mapNotNull { (achievementId, code) ->
            val badge = copyByCode[code] ?: return@mapNotNull null
            val row = userAchievementDao.get(userId, achievementId) ?: return@mapNotNull null
            val unlockedAt = row.unlockedAt ?: return@mapNotNull null
            UnlockedAward(
                userAchievementId = row.id,
                achievementId = achievementId,
                code = badge.code,
                title = badge.title,
                description = badge.description,
                iconKey = badge.iconKey,
                tier = badge.tier,
                unlockedAt = unlockedAt
            )
        }
    }

    /**
     * Marks an announcement as shown, so it is not shown again.
     *
     * Guarded on `seenAt IS NULL` in the statement, so a second call is a no-op rather than
     * moving the timestamp of an acknowledgement the learner already made.
     */
    suspend fun markSeen(userId: Long, userAchievementId: Long, now: Long): Boolean =
        userAchievementDao.markSeen(userAchievementId, userId, now) > 0

    /**
     * What one session actually produced, read from the log rather than from memory.
     *
     * Built from `review_log` so the summary cannot disagree with the schedule it describes: the
     * answers, the ratings and therefore the accuracy and the XP are the same rows the scheduler
     * wrote, not counters a screen kept alongside.
     *
     * @param durationMillis wall-clock time if the caller timed it, else null - which is reported
     *   as unknown rather than as zero.
     */
    suspend fun summariseSession(
        userId: Long,
        sessionId: Long,
        durationMillis: Long? = null
    ): SessionSummary {
        val ratings = reviewLogDao.getForSession(sessionId)
            .filter { it.userId == userId }
            .mapNotNull { SrsRating.fromValue(it.rating) }
        return ProgressEngine.summariseSession(ratings, durationMillis)
    }

    /**
     * The badges earned in a session that have not been announced yet.
     *
     * The filter lives here rather than at the call site so no surface can forget it: a badge
     * re-shown at the next session end is a badge that reads as a bug, and `seenAt` is the only
     * record that it was already celebrated.
     */
    fun observeUnannouncedAwards(userId: Long): Flow<List<UnlockedAward>> =
        progressDao.observeAchievementsWithProgress(userId)
            .map { rows ->
                rows.filter { it.unlockedAt != null && it.seenAt == null && it.userAchievementId != null }
                    .map { it.toUnseenAward() }
            }

    /** A catalogue badge as the learner sees it, with progress only where it is measurable. */
    private fun AchievementProgressRow.toAchievementState(
        metrics: Map<ProgressMetric, Int>
    ): AchievementState {
        val metric = ProgressMetric.forKey(metricKey)
        val measured = if (metric == null) null else metrics[metric]
        return AchievementState(
            achievementId = achievementId,
            code = code,
            title = title,
            description = description,
            category = category,
            tier = tier,
            iconKey = iconKey,
            metricKey = metricKey,
            threshold = threshold,
            // Null rather than the stored value when the metric is unmapped: a stored zero would
            // be read as "you are at 0 of 50" for a badge nothing is counting towards.
            progressValue = if (metric == null) null else (measured ?: progressValue ?: 0),
            unlockedAt = unlockedAt
        )
    }

    private fun AchievementProgressRow.toUnseenAward(): UnlockedAward = UnlockedAward(
        userAchievementId = requireNotNull(userAchievementId),
        achievementId = achievementId,
        code = code,
        title = title,
        description = description,
        iconKey = iconKey,
        tier = tier,
        unlockedAt = requireNotNull(unlockedAt)
    )

    private fun ProgressCountsRow.toMetrics(): Map<ProgressMetric, Int> = mapOf(
        ProgressMetric.WordsCollected to wordsCollected,
        ProgressMetric.WordsMastered to wordsMastered,
        ProgressMetric.ReviewsCompleted to reviewsCompleted,
        ProgressMetric.SessionsCompleted to sessionsCompleted,
        ProgressMetric.StreakDays to streakDays
    )

    private fun List<RatingTallyRow>.toRatingTallies(): List<RatingTally> = mapNotNull { row ->
        SrsRating.fromValue(row.rating)?.let { RatingTally(it, row.tally) }
    }
}
