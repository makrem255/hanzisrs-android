package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.progress.AchievementState
import com.example.data.progress.LearnerProgress
import com.example.data.progress.LevelProgress
import com.example.data.progress.Milestone
import com.example.data.progress.SessionSummary
import com.example.data.progress.UnlockedAward
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AppTheme

/**
 * A learner's level, their accumulated facts, and the badges they have earned.
 *
 * Restrained on purpose. There is no confetti, no countdown, no "keep your streak alive", and
 * nothing that reports a loss. XP and a level are a description of study that has already
 * happened, and a badge is a fact about history that cannot be taken back - which is what makes
 * them worth showing and what makes the alternative, a badge that decays when a day is missed,
 * not worth showing at all.
 */
@Composable
internal fun ProgressContent(
    progress: LearnerProgress,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionHeader("Your progress")
        LevelCard(progress.level)
        if (progress.milestones.isNotEmpty()) {
            MilestoneStrip(progress.milestones)
        }
        // Badges whose metric nothing measures are dropped rather than shown with a zero: "0 of
        // 50" for a badge no engine is counting towards is a number the learner never earned.
        // The repository reports those keys so the omission is visible in the log instead.
        val measurable = progress.achievements.filter { it.progressValue != null }
        if (measurable.isNotEmpty()) {
            SectionHeader("Badges")
            // Measured from the width in hand, the same way the rest of the dashboard does it, so
            // a badge is the same size as a stat tile beside it on any screen.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                AchievementGrid(measurable, columns = columnsFor(maxWidth))
            }
        }
    }
}

/** Level, total XP, and how far through the current level the learner is. */
@Composable
internal fun LevelCard(level: LevelProgress) {
    DashboardCard(modifier = Modifier.testTag("progress_level")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "Level ${level.level}",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
                Text(
                    text = "${level.totalXp} XP from graded reviews",
                    fontSize = 12.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
            Text(
                text = "${level.xpIntoLevel} / ${level.xpForNextLevel}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textSecondary
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        LevelBar(fraction = level.fractionToNextLevel, testTag = "progress_level_bar")
        Spacer(modifier = Modifier.height(6.dp))
        // What is left, not what is at stake. There is no line here about a run ending.
        Text(
            text = "${level.xpForNextLevel - level.xpIntoLevel} XP to level ${level.level + 1}",
            fontSize = 11.sp,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.testTag("progress_level_remaining")
        )
    }
}

/**
 * The bar itself, separated out because the session summary draws the same shape.
 *
 * [fraction] is clamped rather than trusted: a value outside 0..1 would either overflow the track
 * or render as an empty bar at the wrong end, and a progress indicator is not a place to trust a
 * caller.
 */
@Composable
internal fun LevelBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = target, label = "levelBar")
    val tag = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AppTheme.colors.elevated)
            .then(tag)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AppTheme.colors.button)
        )
    }
}

/**
 * The learner's totals, as plain numbers.
 *
 * A horizontal strip rather than cards, because a milestone is a fact rather than something to
 * act on, and five bordered cards would give it more weight than it has earned.
 */
/**
 * A milestone strip item: the value, and what it counts.
 *
 * [ProgressMetric.describe] supplies the value line, so the noun and the thousands separators
 * live in one place rather than being re-spelled on every surface that shows a total.
 */
@Composable
private fun MilestoneStrip(milestones: List<Milestone>) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("progress_milestones"),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(milestones, key = { it.metric.key }) { milestone ->
            Column {
                Text(
                    text = milestone.metric.describe(milestone.value),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.textPrimary
                )
                Text(
                    text = milestone.metric.displayName,
                    fontSize = 11.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
        }
    }
}

/**
 * The badge catalogue, each tile showing what has actually been measured toward it.
 *
 * A locked badge states its progress as a fraction - "3 / 50" - rather than as a countdown
 * ("47 to go"). The fraction is a measurement of study done; the countdown is a number of actions
 * demanded, and it makes a badge feel like a debt instead of a description.
 */
@Composable
internal fun AchievementGrid(achievements: List<AchievementState>, columns: Int = 2) {
    AdaptiveGrid(columns = columns, children = achievements.mapIndexed { index, achievement ->
        { AchievementTile(achievement, entranceIndex = index) }
    })
}

/**
 * One badge, rising into place as the grid appears.
 *
 * The cascade is capped at [ENTRANCE_STAGGER_MS] apart and [ENTRANCE_MAX_DELAY_MS] in total, so a
 * screen full of badges resolves in well under a second. Beyond that it stops reading as a group
 * arriving and starts reading as a list being typed out, and a learner who opens Progress every
 * day would be waiting on a flourish they have already seen.
 *
 * The flag starts false and is flipped once by [LaunchedEffect]: [AnimatedVisibility] animates on
 * a *change* in `visible`, so composing it already true would show the enter transition never
 * running at all.
 */
@Composable
private fun AchievementTile(achievement: AchievementState, entranceIndex: Int = 0) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val delay = (entranceIndex * ENTRANCE_STAGGER_MS).coerceAtMost(ENTRANCE_MAX_DELAY_MS)

    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(tween(280, delayMillis = delay)) +
            slideInVertically(tween(360, delayMillis = delay)) { it / 3 },
        exit = fadeOut(tween(0))
    ) {
        AchievementBody(achievement)
    }
}

private const val ENTRANCE_STAGGER_MS = 45
private const val ENTRANCE_MAX_DELAY_MS = 320

@Composable
private fun AchievementBody(achievement: AchievementState) {
    val earned = achievement.isUnlocked
    DashboardCard(
        modifier = Modifier
            .testTag("badge_${achievement.code}")
            .then(
                if (earned) Modifier else Modifier.border(
                    width = 1.dp,
                    color = AppTheme.colors.border,
                    shape = RoundedCornerShape(20.dp)
                )
            )
    ) {
        Text(
            text = achievement.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (earned) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = achievement.description,
            fontSize = 11.sp,
            color = AppTheme.colors.textSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        val measured = achievement.progressValue
        if (earned) {
            Text(
                text = "Earned",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.success,
                modifier = Modifier.testTag("badge_${achievement.code}_state")
            )
        } else {
            Text(
                text = if (measured == null) "Progress not yet tracked" else "$measured / ${achievement.threshold}",
                fontSize = 11.sp,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.testTag("badge_${achievement.code}_progress")
            )
            if (measured != null) {
                Spacer(modifier = Modifier.height(6.dp))
                LevelBar(
                    fraction = measured.toFloat() / achievement.threshold.coerceAtLeast(1),
                    testTag = "badge_${achievement.code}_bar"
                )
            }
        }
    }
}

/**
 * What one sitting of study produced.
 *
 * Every number here is read back from the review log, so the summary cannot disagree with the
 * schedule it describes. The tone is deliberately flat: it reports what happened and stops. There
 * is no praise for showing up regardless of what was answered, because the answers are the part
 * that matters - a session of twenty `Again` ratings is a session that did not go well, and
 * saying "well done for studying!" about it would be the gamification working against the
 * learning.
 */
@Composable
internal fun SessionSummaryCard(
    summary: SessionSummary,
    awards: List<UnlockedAward> = emptyList(),
    modifier: Modifier = Modifier
) {
    DashboardCard(modifier = modifier.testTag("session_summary")) {
        // No heading of its own, deliberately. This printed "Session complete" at 17sp while
        // its only caller already printed "Session complete" at 22sp twenty-four dp above it,
        // so a completed sitting announced itself twice on one screen. The heading belongs to
        // whatever is presenting the card, because only the presenter knows whether the sitting
        // was completed or simply produced nothing — the deck screen says "Nothing to review"
        // in the same position, and a card that asserted "Session complete" would contradict it.
        Spacer(modifier = Modifier.height(12.dp))

        if (summary.answers == 0) {
            Text(
                text = "No cards were answered in this session, so there is nothing to report.",
                fontSize = 13.sp,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.testTag("session_summary_empty")
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniStat("answered", summary.answers.toString())
                // Null when nothing was answered. Unreachable in this branch, but the honest type
                // is the reason the dash exists: the label decides what to print, not the caller.
                MiniStat("correct", sessionAccuracyLabel(summary.accuracy))
                MiniStat("XP", summary.xpEarned.toString())
            }

            Spacer(modifier = Modifier.height(12.dp))
            RatingBreakdown(summary)

            summary.durationMillis?.let { millis ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = durationLabel(millis),
                    fontSize = 11.sp,
                    color = AppTheme.colors.textSecondary
                )
            }
        }

        if (awards.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (awards.size == 1) "Badge earned" else "Badges earned",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.textPrimary,
                modifier = Modifier.testTag("session_awards_header")
            )
            Spacer(modifier = Modifier.height(8.dp))
            awards.forEach { award ->
                AwardRow(award, Modifier.testTag("session_award_${award.code}"))
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

/** How the answers were distributed, as a single stacked bar plus a legend. */
@Composable
private fun RatingBreakdown(summary: SessionSummary) {
    val total = summary.answers.coerceAtLeast(1)
    Column(modifier = Modifier.testTag("session_breakdown")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            // Inlined rather than extracted to a sub-composable because `weight` is a `RowScope`
            // member: a helper taking a plain `Modifier` has no row to be a fraction of, and a
            // segment silently sized to nothing is a bar that lies about its own proportions.
            RatingShare(summary.again, total, AppTheme.colors.error)
            RatingShare(summary.hard, total, AppTheme.colors.hard)
            RatingShare(summary.good, total, AppTheme.colors.success)
            RatingShare(summary.easy, total, AppTheme.colors.easy)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RatingLegend("Again", summary.again, AppTheme.colors.error)
            RatingLegend("Hard", summary.hard, AppTheme.colors.hard)
            RatingLegend("Good", summary.good, AppTheme.colors.success)
            RatingLegend("Easy", summary.easy, AppTheme.colors.easy)
        }
    }
}

/** One rating's share of the session's answers. Takes its width from the surrounding row. */
@Composable
private fun RowScope.RatingShare(count: Int, total: Int, color: Color) {
    // A zero segment still participates as weight 0, which keeps the remaining shares summing to
    // the whole bar rather than widening to fill the row.
    Box(
        modifier = Modifier
            .weight(count.toFloat() / total.coerceAtLeast(1))
            .height(8.dp)
            .background(color)
    )
}

@Composable
private fun RatingLegend(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = "$label $count", fontSize = 11.sp, color = AppTheme.colors.textSecondary)
    }
}

@Composable
private fun AwardRow(award: UnlockedAward, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppTheme.colors.success)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = award.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
            Text(text = award.description, fontSize = 11.sp, color = AppTheme.colors.textSecondary)
        }
    }
}

/**
 * "100%", or the same dash the dashboard uses for an unknown accuracy.
 *
 * A dash rather than `0%` for the same reason `accuracyLabel` uses one: a session with nothing in
 * it has no accuracy, and a dash is the only honest thing to print. Two surfaces using the same
 * glyph means the learner has to learn one convention rather than two — which is now actually
 * true, because both read [NO_ACCURACY_GLYPH]. This one had been using a plain hyphen while the
 * dashboard used an em dash, so the two were visibly different characters under a comment
 * claiming they were the same.
 */
internal fun sessionAccuracyLabel(accuracy: Float?): String =
    if (accuracy == null) NO_ACCURACY_GLYPH else "${(accuracy.coerceIn(0f, 1f) * 100).toInt()}%"

/** Coarse on purpose: a session summary does not need seconds, and rounding to minutes is honest. */
internal fun durationLabel(millis: Long): String {
    val safe = millis.coerceAtLeast(0)
    val minutes = safe / 60_000
    return when {
        safe < 60_000 -> "under a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

