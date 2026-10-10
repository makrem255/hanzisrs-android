package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.ScreenTitle
import com.example.ui.components.StatTile
import com.example.ui.theme.AppTheme
import com.example.ui.theme.Dimens
import com.example.ui.viewmodel.DashboardUiState
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.ProgressUiState

/**
 * The Progress destination.
 *
 * This surface used to live inside Home, below the dashboard, as a second scroll item. It is
 * promoted to its own tab so "what should I do today" (Home) and "what have I done" (Progress)
 * are separate questions with separate screens, and neither is buried under the other.
 *
 * Every figure comes from the same real sources as before: [ProgressContent] reads the gamified
 * level and badges, and the tiles read the dashboard snapshot's lifetime totals. Nothing here is
 * recomputed or invented.
 */
@Composable
fun ProgressScreen(
    viewModel: MainViewModel,
    onNavigateToLibrary: () -> Unit = {}
) {
    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val progressState by viewModel.progressState.collectAsStateWithLifecycle()

    Scaffold(containerColor = AppTheme.colors.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("progress_screen"),
            contentPadding = PaddingValues(
                start = Dimens.screenH,
                end = Dimens.screenH,
                top = Dimens.md,
                bottom = Dimens.contentBottom
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.lg)
        ) {
            item(key = "title") {
                ScreenTitle(
                    title = "Progress",
                    subtitle = "Everything you have earned, measured from your own reviews"
                )
            }

            // Lifetime totals, from the dashboard snapshot.
            when (val state = dashboardState) {
                is DashboardUiState.Loading -> item(key = "totals_loading") {
                    DashboardLoading(label = "your statistics")
                }

                is DashboardUiState.Failed -> item(key = "totals_failed") {
                    DashboardError(
                        message = state.message,
                        onRetry = { viewModel.refreshDashboard() },
                        label = "statistics"
                    )
                }

                is DashboardUiState.Ready -> {
                    val p = state.snapshot.progress
                    item(key = "totals") {
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.md)
                            ) {
                                StatTile(
                                    label = "Words learned",
                                    value = "${p.wordsLearned}",
                                    accent = AppTheme.colors.button,
                                    modifier = Modifier.weight(1f).testTag("progress_stat_learned")
                                )
                                StatTile(
                                    label = "Mastered",
                                    value = p.masteryRate?.let { "${(it * 100).toInt()}%" } ?: NO_ACCURACY_GLYPH,
                                    accent = AppTheme.colors.success,
                                    modifier = Modifier.weight(1f).testTag("progress_stat_mastered")
                                )
                            }
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.md)
                            ) {
                                StatTile(
                                    label = "Current streak",
                                    value = "${p.currentStreakDays}",
                                    accent = AppTheme.colors.textPrimary,
                                    caption = "Longest ${p.longestStreakDays}",
                                    modifier = Modifier.weight(1f).testTag("progress_stat_streak")
                                )
                                StatTile(
                                    label = "Reviews all time",
                                    value = "${p.lifetimeReviews}",
                                    accent = AppTheme.colors.textPrimary,
                                    modifier = Modifier.weight(1f).testTag("progress_stat_reviews")
                                )
                            }
                        }
                    }

                    item(key = "week") {
                        WeekCard(state.snapshot.recentDays, state.snapshot.asOfEpochDay)
                    }
                }
            }

            item(key = "level_and_badges") {
                when (val state = progressState) {
                    is ProgressUiState.Loading -> DashboardLoading(label = "your progress")

                    is ProgressUiState.Failed -> DashboardError(
                        message = state.message,
                        onRetry = { viewModel.refreshProgress() },
                        label = "progress"
                    )

                    is ProgressUiState.Ready -> ProgressContent(progress = state.progress)
                }
            }

            item(key = "empty_hint") {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        text = "Badges are facts about study that has already happened and cannot " +
                            "be taken back. Nothing here decays when a day is missed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.textSecondary
                    )
                }
            }
        }
    }
}

