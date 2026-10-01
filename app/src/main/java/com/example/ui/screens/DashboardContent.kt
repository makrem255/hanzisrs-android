package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dashboard.DashboardSnapshot
import com.example.data.dashboard.DayActivity
import com.example.data.dashboard.DifficultCard
import com.example.data.dashboard.NextAction
import com.example.data.dashboard.Progress
import com.example.data.dashboard.Workload
import com.example.data.model.StorageValues.CardState
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.LilacPrimary
import com.example.ui.theme.LilacPrimaryDark
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsAgainDark
import com.example.ui.theme.SrsEasyDark
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.SrsHardDark
import com.example.ui.theme.srsStateColor
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle

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
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
internal fun columnsFor(maxWidth: androidx.compose.ui.unit.Dp): Int = when {
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
    // existed — which is exactly the wrong outcome on the wide screens where the extra
    // column was supposed to help. The breakpoint says how many *could* fit; this says how
    // many are actually needed.
    val safeColumns = columns.coerceIn(1, children.size.coerceAtLeast(1))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        children.chunked(safeColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** The hero: the one thing to do next. */
@Composable
private fun NextActionCard(
    snapshot: DashboardSnapshot,
    onStartReview: () -> Unit,
    onNavigateToAddWord: () -> Unit,
    onNavigateToLibrary: () -> Unit
) {
    val action = snapshot.recommendation
    DashboardCard(modifier = Modifier.testTag("dashboard_next_action")) {
        Text(
            text = when (action.kind) {
                NextAction.Kind.REVIEW_DUE, NextAction.Kind.LEARN_NEW -> "What to do today"
                NextAction.Kind.PRACTICE_DIFFICULT -> "You're caught up"
                NextAction.Kind.CAUGHT_UP -> "Nothing scheduled"
                NextAction.Kind.NOTHING_ENROLLED -> "Get started"
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = action.headline,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextLight,
            modifier = Modifier.testTag("dashboard_next_headline")
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = action.detail,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = TextMuted
        )

        Spacer(modifier = Modifier.height(16.dp))

        // The primary action is the only button on the card. A dashboard that offers four
        // equally weighted buttons has not answered "what should I do next".
        if (action.kind == NextAction.Kind.NOTHING_ENROLLED) {
            PrimaryButton(text = "Add a word", onClick = onNavigateToAddWord, tag = "dashboard_add_first")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (action.isStartable) {
                    PrimaryButton(
                        text = when (action.kind) {
                            NextAction.Kind.REVIEW_DUE -> "Start review"
                            NextAction.Kind.LEARN_NEW -> "Learn new words"
                            NextAction.Kind.PRACTICE_DIFFICULT -> "Practise"
                            else -> "Start review"
                        },
                        onClick = onStartReview,
                        tag = "dashboard_start",
                        modifier = Modifier.weight(1f)
                    )
                }
                SecondaryButton(
                    text = "Library",
                    onClick = onNavigateToLibrary,
                    tag = "dashboard_library",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Today's activity, or a truthful statement that there has been none. */
@Composable
private fun TodayCard(snapshot: DashboardSnapshot) {
    val today = snapshot.today
    val workload = snapshot.workload
    val goal = workload.limits.reviews
    val done = today?.reviewsCompleted ?: 0

    DashboardCard(modifier = Modifier.testTag("dashboard_today")) {
        SectionHeader("Today")
        Spacer(modifier = Modifier.height(12.dp))

        GoalBar(
            done = done,
            goal = goal,
            caption = "Reviews toward your daily goal"
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Weighted, and the gaps shrink with the card.
        //
        // Three `MiniStat`s with 18dp gaps need about 230dp. `columnsFor` returns 2 from
        // 400dp, so on a 411dp phone each card is ~(411-32-12)/2 = 183dp wide and 151dp of
        // usable inner width — which meant "Accuracy" and "New words" wrapped to two lines
        // in a card whose neighbour stayed one line, so the two cards in the row ended at
        // different heights. Weighting the stats divides the real width rather than
        // assuming it, and the gap is small enough to survive a narrow card.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("Accuracy", accuracyLabel(today), modifier = Modifier.weight(1f))
            MiniStat(
                label = "New words",
                value = "${today?.newWordsIntroduced ?: 0}/${workload.limits.newWords}",
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Mastered",
                value = "${today?.newWordsMastered ?: 0}",
                modifier = Modifier.weight(1f)
            )
        }
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
 * One constant because two functions in two files had drifted — this one used an em dash (U+2014)
 * and `sessionAccuracyLabel` a hyphen (U+002D) — while the KDoc above the other insisted that
 * "two surfaces using the same glyph means the learner has to learn one convention rather than
 * two". Two visually distinct placeholders for the same state is precisely the thing that
 * shared function existed to prevent, and the comment was actively discouraging anyone from
 * noticing.
 */
internal const val NO_ACCURACY_GLYPH = "—"

/** The streak, and whether it is alive. */
@Composable
private fun StreakCard(snapshot: DashboardSnapshot) {
    val p = snapshot.progress
    DashboardCard(modifier = Modifier.testTag("dashboard_streak")) {
        SectionHeader("Learning streak")
        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${p.currentStreakDays}",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = if (p.currentStreakDays > 0) SrsGoodDark else TextSubtle
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (p.currentStreakDays == 1) "day" else "days",
                fontSize = 14.sp,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                p.enrolled == 0 -> "No words yet"
                p.studiedToday -> "Extended today"
                p.currentStreakDays > 0 -> "Not yet extended today"
                else -> "Start one by reviewing a card"
            },
            fontSize = 12.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Longest ${p.longestStreakDays} · ${p.activeDays} active " +
                if (p.activeDays == 1) "day" else "days",
            fontSize = 12.sp,
            color = TextSubtle
        )
    }
}

/** Lifetime figures, and the mastery rate. */
@Composable
private fun ProgressCard(snapshot: DashboardSnapshot) {
    val p = snapshot.progress
    DashboardCard(modifier = Modifier.testTag("dashboard_progress")) {
        SectionHeader("Progress")
        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "${p.wordsLearned}",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = TextLight
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "of ${p.enrolled} learned",
                fontSize = 14.sp,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = p.masteryRate?.let { "Mastered: ${(it * 100).toInt()}% of collection" }
                ?: "Mastery appears once the collection has words",
            fontSize = 12.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${p.lifetimeReviews} reviews all time",
            fontSize = 12.sp,
            color = TextSubtle
        )
    }
}

/** The four scheduling states as a proportional bar. */
@Composable
private fun DistributionCard(progress: Progress) {
    DashboardCard(modifier = Modifier.testTag("dashboard_distribution")) {
        SectionHeader("Where your words are")
        Spacer(modifier = Modifier.height(12.dp))

        if (progress.enrolled == 0) {
            // An empty progress bar is indistinguishable from a full one to a learner who is
            // looking at it quickly, and "0%" would claim a measurement nobody took.
            Text(
                text = "Nothing in your collection yet.",
                fontSize = 13.sp,
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
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                progress.distribution.forEach { share ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${share.count}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = srsStateColor(share.state)
                        )
                        Text(
                            text = share.state.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 11.sp,
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
private fun WeekCard(days: List<DayActivity>, asOfEpochDay: Int) {
    DashboardCard(modifier = Modifier.testTag("dashboard_week")) {
        SectionHeader("This week")
        Spacer(modifier = Modifier.height(12.dp))

        if (!days.any { it.reviewsCompleted > 0 }) {
            Text(
                text = "No reviews recorded yet.",
                fontSize = 13.sp,
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
                    .height(84.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                // 11sp, the floor. The count on a bar is the number the
                                // whole chart exists to communicate, so it is the last
                                // thing that should have been rendered at 10sp.
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(2.dp))
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
                                .height((4 + 52 * animated).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (count > 0) LilacPrimary else DarkSurfaceElevated)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = weekdayLabel(epochDay),
                            // 11sp, and the first thing to overflow if a week ever grew
                            // past seven: `maxLines = 1` plus a single letter is what
                            // keeps a three-letter weekday abbreviation from becoming a
                            // three-line column under its own bar.
                            fontSize = 11.sp,
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
private fun DifficultCardList(
    cards: List<DifficultCard>,
    onOpenWord: (Long) -> Unit
) {
    DashboardCard(modifier = Modifier.testTag("dashboard_difficult")) {
        SectionHeader("Worth another look")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Words you have missed before, worst first.",
            fontSize = 12.sp,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards, key = { it.userVocabularyId }) { card ->
                Column(
                    modifier = Modifier
                        .width(132.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurfaceElevated)
                        .clickable { onOpenWord(card.userVocabularyId) }
                        .padding(12.dp)
                ) {
                    Text(text = card.character, fontSize = 30.sp, color = TextLight)
                    if (card.pinyin.isNotEmpty()) {
                        Text(text = card.pinyin, fontSize = 12.sp, color = LilacPrimaryDark)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = card.meaning,
                        fontSize = 11.sp,
                        color = TextMuted,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${card.lapses} ${if (card.lapses == 1) "miss" else "misses"}",
                        fontSize = 11.sp,
                        color = SrsAgainDark
                    )
                }
            }
        }
    }
}

// ---- shared pieces --------------------------------------------------------------------------------

@Composable
internal fun DashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextLight)
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
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextLight
        )
        // Wraps rather than clips. A truncated "Accuracy" reads as a different word; a
        // two-line label inside a card that has already been sized to fit is fine.
        Text(text = label, fontSize = 11.sp, color = TextMuted, maxLines = 2)
    }
}

/** Progress toward the learner's own daily limit, with the numbers stated. */
@Composable
private fun GoalBar(done: Int, goal: Int, caption: String) {
    val safeGoal = goal.coerceAtLeast(1)
    val fraction = (done.toFloat() / safeGoal).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = fraction, label = "goalBar")

    Column(modifier = Modifier.testTag("dashboard_goal")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = caption, fontSize = 12.sp, color = TextMuted)
            Text(
                text = "$done / $goal",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextLight
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(DarkSurfaceElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (animated >= 1f) SrsGoodDark else LilacPrimary)
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LilacPrimary,
            contentColor = LilacPrimaryDark
        ),
        modifier = modifier
            .height(48.dp)
            .testTag(tag)
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = LilacPrimaryDark)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DarkSurfaceElevated,
            contentColor = TextLight
        ),
        modifier = modifier
            .height(48.dp)
            .testTag(tag)
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Shown while the snapshot is being read.
 *
 * [label] names what is loading. It was hardcoded to "Loading your progress", and this
 * component is used for the dashboard as well as the progress tab — so a dashboard read that
 * took a moment told the learner their *progress* was loading, naming a different surface than
 * the one they were looking at.
 */
@Composable
fun DashboardLoading(label: String = "your progress", modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = LilacPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Loading $label", fontSize = 13.sp, color = TextMuted)
        }
    }
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label.replaceFirstChar { it.uppercase() } + " could not be loaded",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextLight
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                fontSize = 13.sp,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            SecondaryButton(text = "Try again", onClick = onRetry, tag = "dashboard_retry")
        }
    }
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
