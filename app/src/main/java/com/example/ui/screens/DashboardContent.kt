package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.dashboard.DashboardSnapshot
import com.example.data.dashboard.DayActivity
import com.example.data.dashboard.DifficultCard
import com.example.data.dashboard.NextAction
import com.example.data.dashboard.Progress
import com.example.ui.components.AppCard
import com.example.ui.components.GoalBar
import com.example.ui.components.IconBadge
import com.example.ui.components.ProgressRing
import com.example.ui.components.SectionHeader
import com.example.ui.components.VSpace
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentMint
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.Dimens
import com.example.ui.theme.HeroSheen
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.theme.srsStateColor

/**
 * The learning dashboard, drawn from one [DashboardSnapshot].
 *
 * Purely presentational. Every number here came from the snapshot, which came from the
 * learner's own rows, so a figure cannot appear on this screen that no review record or stored
 * schedule accounts for. Where a value is genuinely unknown the screen says so - see
 * [accuracyLabel] - rather than substituting a zero.
 *
 * The layout is adaptive without a dependency on a window-size library: [columnsFor] reads the
 * width actually available, so the same composable lays out as a single column on a phone, two
 * on a tablet and three on a desktop window, and on a split-screen window that changes size
 * while it is open.
 */
@Composable
fun DashboardContent(
    snapshot: DashboardSnapshot,
    onStartReview: () -> Unit,
    onNavigateToAddWord: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onOpenWord: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = columnsFor(maxWidth)

        // A Column, not a LazyColumn: this is embedded as one item inside a screen that already
        // scrolls, and a lazy list nested in a lazy list is given unbounded height and throws.
        // The caller owns the scrolling; this owns the arrangement.
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
            NextActionCard(snapshot, onStartReview, onNavigateToAddWord, onNavigateToLibrary)

            AdaptiveGrid(columns, listOf(
                { TodayCard(snapshot) },
                { StreakCard(snapshot) }
            ))

            AdaptiveGrid(columns, listOf(
                { ProgressCard(snapshot) },
                { DistributionCard(snapshot.progress) }
            ))

            WeekCard(snapshot.recentDays, snapshot.asOfEpochDay)

            if (snapshot.difficult.isNotEmpty()) {
                DifficultCardList(snapshot.difficult, onOpenWord)
            }
        }
    }
}

/**
 * How many cards sit side by side.
 *
 * Derived from the width in hand rather than from a device class, because the width in hand is
 * what actually determines whether two cards fit. Reading it from the container rather than the
 * window also means a foldable or a split-screen window is handled by the same code.
 */
internal fun columnsFor(maxWidth: Dp): Int = when {
    maxWidth < 400.dp -> 1
    maxWidth < 720.dp -> 2
    else -> 3
}

/**
 * Lays out [children] in rows of [columns], without `FlowRow`.
 *
 * A manual chunk rather than an experimental layout API: the wrapping behaviour needed here is
 * a few lines, and a stable API is worth more than the general one.
 *
 * The last row is padded with empty weight so a partial row keeps the same card width as a full
 * one. Without the padding, two cards in a three-column grid would each be a third wider than
 * the four cards in the row above, and the column edges would not line up.
 */
@Composable
internal fun AdaptiveGrid(columns: Int, children: List<@Composable () -> Unit>) {
    // Never more columns than there are children. A 3-wide grid holding 2 cards reserves
    // a third of the width for an empty slot, so the two cards are sized as though a third
    // existed - which is exactly the wrong outcome on the wide screens where the extra
    // column was supposed to help. The breakpoint says how many *could* fit; this says how
    // many are actually needed.
    val safeColumns = columns.coerceIn(1, children.size.coerceAtLeast(1))
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
        children.chunked(safeColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.md)) {
                row.forEach { child ->
                    Box(modifier = Modifier.weight(1f)) { child() }
                }
                repeat(safeColumns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The hero: the one thing to do next.
 *
 * This is the only card on the screen with a gradient, because it is the only card that is
 * asking the learner to do something. Everything below it is a report.
 */
@Composable
internal fun NextActionCard(
    snapshot: DashboardSnapshot,
    onStartReview: () -> Unit,
    onNavigateToAddWord: () -> Unit,
    onNavigateToLibrary: () -> Unit
) {
    val action = snapshot.recommendation
    AppCard(
        modifier = Modifier.testTag("dashboard_next_action"),
        brush = HeroSheen
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = when (action.kind) {
                    NextAction.Kind.REVIEW_DUE -> Icons.Default.PlayArrow
                    NextAction.Kind.LEARN_NEW -> Icons.Default.AutoAwesome
                    NextAction.Kind.PRACTICE_DIFFICULT -> Icons.Default.TrendingUp
                    NextAction.Kind.CAUGHT_UP -> Icons.Default.TrendingUp
                    NextAction.Kind.NOTHING_ENROLLED -> Icons.Default.AutoAwesome
                },
                tint = AccentPrimary,
                background = AccentPrimary.copy(alpha = 0.16f),
                size = 44.dp
            )
            Spacer(Modifier.width(Dimens.md))
            Column {
                Text(
                    text = when (action.kind) {
                        NextAction.Kind.REVIEW_DUE, NextAction.Kind.LEARN_NEW -> "What to do today"
                        NextAction.Kind.PRACTICE_DIFFICULT -> "You're caught up"
                        NextAction.Kind.CAUGHT_UP -> "Nothing scheduled"
                        NextAction.Kind.NOTHING_ENROLLED -> "Get started"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Text(
                    text = action.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextLight,
                    modifier = Modifier.testTag("dashboard_next_headline")
                )
            }
        }

        VSpace(Dimens.sm)

        Text(
            text = action.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )

        VSpace(Dimens.lg)

        // The primary action is the only button on the card. A dashboard that offers four
        // equally weighted buttons has not answered "what should I do next".
        if (action.kind == NextAction.Kind.NOTHING_ENROLLED) {
            com.example.ui.components.PrimaryButton(
                text = "Add a word",
                onClick = onNavigateToAddWord,
                modifier = Modifier.testTag("dashboard_add_first")
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                if (action.isStartable) {
                    com.example.ui.components.PrimaryButton(
                        text = when (action.kind) {
                            NextAction.Kind.REVIEW_DUE -> "Start review"
                            NextAction.Kind.LEARN_NEW -> "Learn new words"
                            NextAction.Kind.PRACTICE_DIFFICULT -> "Practise"
                            else -> "Start review"
                        },
                        onClick = onStartReview,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_start")
                    )
                }
                com.example.ui.components.SecondaryButton(
                    text = "Library",
                    onClick = onNavigateToLibrary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_library")
                )
            }
        }
    }
}

/** Today's activity, or a truthful statement that there has been none. */
@Composable
internal fun TodayCard(snapshot: DashboardSnapshot) {
    val today = snapshot.today
    val workload = snapshot.workload
    val goal = workload.limits.reviews
    val done = today?.reviewsCompleted ?: 0

    AppCard(modifier = Modifier.testTag("dashboard_today")) {
        SectionHeader("Today")
        VSpace(Dimens.md)

        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = if (goal > 0) done.toFloat() / goal.coerceAtLeast(1) else 0f,
                accent = AccentPrimary,
                diameter = 84.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$done",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextLight
                    )
                    Text(
                        text = "of $goal",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
            Spacer(Modifier.width(Dimens.lg))
            Column(modifier = Modifier.weight(1f)) {
                MiniStat("Accuracy", accuracyLabel(today))
                VSpace(Dimens.sm)
                MiniStat(
                    label = "New words",
                    value = "${today?.newWordsIntroduced ?: 0}/${workload.limits.newWords}"
                )
                VSpace(Dimens.sm)
                MiniStat("Mastered", value = "${today?.newWordsMastered ?: 0}")
            }
        }

        VSpace(Dimens.md)
        GoalBar(
            progress = if (goal > 0) done.toFloat() / goal.coerceAtLeast(1) else 0f,
            accent = if (done >= goal && goal > 0) SrsGoodDark else AccentPrimary
        )
        VSpace(Dimens.xs)
        Text(
            text = "Reviews toward your daily goal",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.testTag("dashboard_goal")
        )
    }
}

/**
 * Today's accuracy, or the absence of it.
 *
 * The `null` case is a real state, not an oversight: a learner who has not studied today has
 * not been accurate or inaccurate, and printing `0%` would be a number they never earned.
 */
internal fun accuracyLabel(today: DayActivity?): String {
    val rate = today?.accuracy ?: return NO_ACCURACY_GLYPH
    return "${(rate * 100).toInt()}%"
}

/**
 * What both accuracy surfaces print when there is no accuracy to print.
 *
 * One constant because two functions in two files had drifted - one used an em dash and the
 * other a hyphen - while the KDoc above the other insisted that "two surfaces using the same
 * glyph means the learner has to learn one convention rather than two".
 */
internal const val NO_ACCURACY_GLYPH = "—"

/** The streak, and whether it is alive. */
@Composable
internal fun StreakCard(snapshot: DashboardSnapshot) {
    val p = snapshot.progress
    AppCard(modifier = Modifier.testTag("dashboard_streak")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Default.LocalFireDepartment,
                tint = if (p.currentStreakDays > 0) AccentAmber else TextMuted,
                background = (if (p.currentStreakDays > 0) AccentAmber else TextMuted).copy(alpha = 0.14f),
                size = 36.dp
            )
            Spacer(Modifier.width(Dimens.sm))
            SectionHeader("Learning streak")
        }
        VSpace(Dimens.md)

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${p.currentStreakDays}",
                style = MaterialTheme.typography.displaySmall,
                color = if (p.currentStreakDays > 0) AccentAmber else TextSubtle
            )
            Spacer(Modifier.width(Dimens.xs))
            Text(
                text = if (p.currentStreakDays == 1) "day" else "days",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(bottom = Dimens.sm)
            )
        }
        VSpace(Dimens.xs)
        Text(
            text = when {
                p.enrolled == 0 -> "No words yet"
                p.studiedToday -> "Extended today"
                p.currentStreakDays > 0 -> "Not yet extended today"
                else -> "Start one by reviewing a card"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        VSpace(Dimens.sm)
        Text(
            text = "Longest ${p.longestStreakDays} · ${p.activeDays} active " +
                if (p.activeDays == 1) "day" else "days",
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtle
        )
    }
}

/** Lifetime figures, and the mastery rate. */
@Composable
internal fun ProgressCard(snapshot: DashboardSnapshot) {
    val p = snapshot.progress
    AppCard(modifier = Modifier.testTag("dashboard_progress")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = Icons.Default.TrendingUp,
                tint = AccentMint,
                background = AccentMint.copy(alpha = 0.14f),
                size = 36.dp
            )
            Spacer(Modifier.width(Dimens.sm))
            SectionHeader("Progress")
        }
        VSpace(Dimens.md)

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${p.wordsLearned}",
                style = MaterialTheme.typography.displaySmall,
                color = TextLight
            )
            Spacer(Modifier.width(Dimens.xs))
            Text(
                text = "of ${p.enrolled} learned",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(bottom = Dimens.sm)
            )
        }

        VSpace(Dimens.md)

        Text(
            text = p.masteryRate?.let { "Mastered: ${(it * 100).toInt()}% of collection" }
                ?: "Mastery appears once the collection has words",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        VSpace(Dimens.xs)
        Text(
            text = "${p.lifetimeReviews} reviews all time",
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtle
        )
    }
}

/** The four scheduling states as a proportional bar. */
@Composable
internal fun DistributionCard(progress: Progress) {
    AppCard(modifier = Modifier.testTag("dashboard_distribution")) {
        SectionHeader("Where your words are")
        VSpace(Dimens.md)

        if (progress.enrolled == 0) {
            // An empty progress bar is indistinguishable from a full one to a learner who is
            // looking at it quickly, and "0%" would claim a measurement nobody took.
            Text(
                text = "Nothing in your collection yet.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.testTag("dashboard_distribution_empty")
            )
        } else {
            val total = progress.distribution.sumOf { it.count }.toFloat()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            ) {
                progress.distribution.forEach { share ->
                    if (share.count > 0) {
                        Box(
                            modifier = Modifier
                                .weight(share.count / total)
                                .fillMaxSize()
                                .background(srsStateColor(share.state))
                        )
                    }
                }
            }
            VSpace(Dimens.md)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                progress.distribution.forEach { share ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${share.count}",
                            style = MaterialTheme.typography.titleMedium,
                            color = srsStateColor(share.state)
                        )
                        Text(
                            text = share.state.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

/**
 * The last seven days of real review counts.
 *
 * Bars are drawn from [DayActivity.reviewsCompleted] and nothing else. Missing days are drawn
 * as empty rather than omitted, so a gap in a streak is visible as a gap instead of being
 * quietly compressed out of the chart.
 */
@Composable
internal fun WeekCard(days: List<DayActivity>, asOfEpochDay: Int) {
    AppCard(modifier = Modifier.testTag("dashboard_week")) {
        SectionHeader("This week")
        VSpace(Dimens.md)

        if (!days.any { it.reviewsCompleted > 0 }) {
            Text(
                text = "No reviews recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.testTag("dashboard_week_empty")
            )
        } else {
            val byDay = days.associateBy { it.epochDay }
            val window = (asOfEpochDay - 6..asOfEpochDay).toList()
            val peak = window.maxOf { byDay[it]?.reviewsCompleted ?: 0 }.coerceAtLeast(1)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
                verticalAlignment = Alignment.Bottom
            ) {
                window.forEach { epochDay ->
                    val count = byDay[epochDay]?.reviewsCompleted ?: 0
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (count > 0) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Spacer(Modifier.height(Dimens.xxs))
                        }
                        // A minimum height keeps an empty day visible as a day rather than as
                        // an absence, which is the whole point of charting the gaps.
                        val fraction = count.toFloat() / peak
                        val animated by animateFloatAsState(
                            targetValue = fraction,
                            label = "weekBar"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((4 + 58 * animated).dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (count > 0) AccentPrimary else DarkSurfaceElevated)
                        )
                        Spacer(Modifier.height(Dimens.xs))
                        Text(
                            text = weekdayLabel(epochDay),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = TextSubtle
                        )
                    }
                }
            }
        }
    }
}

/** The words that actually gave this learner trouble. */
@Composable
internal fun DifficultCardList(
    cards: List<DifficultCard>,
    onOpenWord: (Long) -> Unit
) {
    AppCard(modifier = Modifier.testTag("dashboard_difficult")) {
        SectionHeader("Worth another look", subtitle = "Words you have missed before, worst first.")
        VSpace(Dimens.md)

        LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
            items(cards, key = { it.userVocabularyId }) { card ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurfaceElevated)
                        .clickable { onOpenWord(card.userVocabularyId) }
                        .padding(Dimens.md)
                ) {
                    Text(
                        text = card.character,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextLight
                    )
                    if (card.pinyin.isNotEmpty()) {
                        Text(
                            text = card.pinyin,
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentCyan
                        )
                    }
                    Spacer(Modifier.height(Dimens.xs))
                    Text(
                        text = card.meaning,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(Dimens.sm))
                    Text(
                        text = "${card.lapses} ${if (card.lapses == 1) "miss" else "misses"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentRed
                    )
                }
            }
        }
    }
}

// ---- shared pieces --------------------------------------------------------------------------

/** A card. Delegates to the design-system surface so every panel matches. */
@Composable
internal fun DashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier = modifier, content = content)
}

/**
 * A labelled number.
 *
 * Takes a modifier so a caller that is laying these out in a shared row can weight them
 * against the width actually available, rather than each one wrapping to however many lines
 * its label happens to need at whatever width the card turned out to be.
 */
@Composable
internal fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = TextLight
        )
        // Wraps rather than clips. A truncated "Accuracy" reads as a different word; a
        // two-line label inside a card that has already been sized to fit is fine.
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 2
        )
    }
}

/**
 * Shown while the snapshot is being read.
 *
 * [label] names what is loading. It was hardcoded to "Loading your progress", and this
 * component is used for the dashboard as well as the progress tab - so a dashboard read that
 * took a moment told the learner their *progress* was loading, naming a different surface than
 * the one they were looking at.
 */
@Composable
fun DashboardLoading(label: String = "your progress", modifier: Modifier = Modifier) {
    com.example.ui.components.LoadingState(label = "Loading $label", modifier = modifier)
}

/**
 * Shown when the data could not be read. Says what to do about it.
 *
 * [label] is the surface that failed to load, for the same reason as [DashboardLoading]: these
 * two components serve both the dashboard and the progress tab, and the heading used to be
 * fixed to "Progress" regardless of which one the learner was actually on.
 */
@Composable
fun DashboardError(
    message: String,
    onRetry: () -> Unit,
    label: String = "progress",
    modifier: Modifier = Modifier
) {
    com.example.ui.components.ErrorState(
        message = message,
        title = label.replaceFirstChar { it.uppercase() } + " could not be loaded",
        onRetry = onRetry,
        modifier = modifier
    )
}

/** Short weekday label for the week chart. */
private fun weekdayLabel(epochDay: Int): String {
    val dayOfWeek = java.time.LocalDate.ofEpochDay(epochDay.toLong()).dayOfWeek.value
    return when (dayOfWeek) {
        1 -> "M"
        2 -> "T"
        3 -> "W"
        4 -> "T"
        5 -> "F"
        6 -> "S"
        else -> "S"
    }
}
