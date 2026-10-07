package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.PronunciationButton
import com.example.audio.PronunciationRequest
import com.example.data.model.WordWithSrs
import com.example.ui.components.AppCard
import com.example.ui.components.IconBadge
import com.example.ui.components.ScreenTitle
import com.example.ui.components.SectionHeader
import com.example.ui.components.rememberNotificationRequest
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentMint
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.AccentViolet
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.Dimens
import com.example.ui.theme.HanziMedium
import com.example.ui.theme.PinyinText
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.viewmodel.DashboardUiState
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.ProgressUiState

/**
 * The Home destination: what to study today.
 *
 * The dashboard answers "what should I do now" and nothing else is allowed above it. Progress
 * used to sit underneath as a second block; it now has its own tab, so this screen leads with
 * the one action and then offers the smaller ways in.
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onStartReview: () -> Unit,
    onNavigateToAddWord: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToProgress: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToRandomReview: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val allWords by viewModel.userWords.collectAsStateWithLifecycle()

    // Both needed to tell the three things an empty `allWords` can mean apart: not asked yet,
    // asked and failed, and genuinely nothing.
    val wordsLoaded by viewModel.wordsLoaded.collectAsStateWithLifecycle()
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()
    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()

    // Kept referenced so a future polish pass can surface a live progress glance without
    // re-plumbing the view model; the value is real and is already collected elsewhere.
    val progressState by viewModel.progressState.collectAsStateWithLifecycle()

    var notificationMessage by remember { mutableStateOf<String?>(null) }
    val sendDueReminderPreview = rememberNotificationRequest(
        onGranted = { viewModel.sendDueReminderNotification() },
        onDenied = {
            notificationMessage =
                "Notifications are off. You can enable them in Android system settings."
        }
    )

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(notificationMessage) {
        notificationMessage?.let {
            snackbarHostState.showSnackbar(it)
            notificationMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAddWord,
                containerColor = AccentPrimary,
                contentColor = AccentPrimaryInk,
                shape = CircleShape,
                modifier = Modifier.testTag("add_word_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add New Hanzi")
            }
        },
        containerColor = DarkBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = Dimens.screenH,
                end = Dimens.screenH,
                top = Dimens.md,
                bottom = Dimens.contentBottom
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.lg)
        ) {
            item(key = "header") {
                ScreenTitle(
                    title = "你好",
                    subtitle = "Let's learn something today, " +
                        (currentUser?.displayName?.takeIf { it.isNotBlank() } ?: "learner") + ".",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = sendDueReminderPreview,
                                modifier = Modifier.testTag("send_notification_icon")
                            ) {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = "Preview the due review alert",
                                    tint = AccentPrimary
                                )
                            }
                            ProfileAvatar(
                                initial = (currentUser?.displayName?.firstOrNull() ?: '学').uppercaseChar(),
                                onClick = onNavigateToProfile
                            )
                        }
                    }
                )
            }

            // The dashboard: the four questions - what to study, how much is due, how am I
            // doing, what next - from real rows. Loading, empty and failure are all explicit.
            item(key = "dashboard") {
                when (val state = dashboardState) {
                    is DashboardUiState.Loading -> DashboardLoading(
                        label = "your dashboard",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )

                    is DashboardUiState.Failed -> DashboardError(
                        message = state.message,
                        onRetry = { viewModel.refreshDashboard() },
                        label = "dashboard"
                    )

                    is DashboardUiState.Ready -> DashboardContent(
                        snapshot = state.snapshot,
                        onStartReview = {
                            viewModel.resetDeckSession()
                            onStartReview()
                        },
                        onNavigateToAddWord = onNavigateToAddWord,
                        onNavigateToLibrary = onNavigateToLibrary,
                        onOpenWord = {
                            viewModel.resetDeckSession()
                            onStartReview()
                        }
                    )
                }
            }

            // Practice sits directly under the day's work and above the navigation grid:
            // after "what do I owe today" comes "what else can I do", and only then the
            // four destinations - which duplicate the bottom bar and therefore rank lower.
            item(key = "random_review") {
                AppCard(
                    onClick = onNavigateToRandomReview,
                    contentPadding = PaddingValues(Dimens.md),
                    modifier = Modifier.testTag("random_review_entry")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            icon = Icons.Default.Shuffle,
                            tint = AccentAmber,
                            background = AccentAmber.copy(alpha = 0.14f),
                            size = 34.dp,
                            cornerRadius = 11.dp
                        )
                        Spacer(Modifier.width(Dimens.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Random Review",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextLight
                            )
                            Text(
                                text = "Open practice · nothing gets rescheduled",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = TextMuted
                        )
                    }
                }
            }

            item(key = "quick_actions") {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.md)) {
                    SectionHeader("Quick actions")
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.md)) {
                        QuickAction(
                            icon = Icons.Default.PlayArrow,
                            title = "Review",
                            subtitle = "Due cards",
                            accent = AccentPrimary,
                            onClick = {
                                viewModel.resetDeckSession()
                                onStartReview()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        QuickAction(
                            icon = Icons.Default.AutoAwesome,
                            title = "Add word",
                            subtitle = "Hanzi or pinyin",
                            accent = AccentViolet,
                            onClick = onNavigateToAddWord,
                            modifier = Modifier.weight(1f).testTag("home_add_word")
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.md)) {
                        QuickAction(
                            icon = Icons.Default.School,
                            title = "Learn",
                            subtitle = "Your library",
                            accent = AccentCyan,
                            onClick = onNavigateToLibrary,
                            modifier = Modifier.weight(1f)
                        )
                        QuickAction(
                            icon = Icons.Default.Insights,
                            title = "Progress",
                            subtitle = "Streaks & badges",
                            accent = AccentMint,
                            onClick = onNavigateToProgress,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item(key = "recent") {
                Column {
                    SectionHeader(
                        title = "Recent vocabulary",
                        actionLabel = if (wordsLoaded && libraryError == null) {
                            "View all (${allWords.size})"
                        } else {
                            "View all"
                        },
                        onAction = onNavigateToLibrary
                    )
                    Spacer(Modifier.height(Dimens.sm))

                    if (allWords.isEmpty()) {
                        // Three states, not one. `allWords` starts empty before the query has
                        // run, so this must not tell a learner with a full library they have
                        // nothing; and a failed read degrades to an empty list, so the error
                        // case has to be shown rather than silently rendered as "nothing".
                        Text(
                            text = libraryError ?: when {
                                !wordsLoaded -> "Loading your collection…"
                                else -> "Nothing in your collection yet. Add a word to get started."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.testTag("home_collection_state")
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Dimens.md)) {
                            items(allWords.take(8)) { wordWithSrs ->
                                RecentWordCard(
                                    wordWithSrs = wordWithSrs,
                                    pronunciationService = viewModel.pronunciationService
                                )
                            }
                        }
                    }
                }
            }

            item(key = "pillars") {
                Column {
                    SectionHeader("What a review covers")
                    Spacer(Modifier.height(Dimens.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                        PillarItem("笔", "Writing", "Stroke order", Modifier.weight(1f))
                        PillarItem("音", "Audio", "Mandarin", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Dimens.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                        PillarItem("义", "Meaning", "In context", Modifier.weight(1f))
                        PillarItem("忆", "Recall", "SM-2 spaced", Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatar(
    initial: Char,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DarkSurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = AccentPrimary
        )
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(Dimens.md)
    ) {
        IconBadge(
            icon = icon,
            tint = accent,
            background = accent.copy(alpha = 0.14f),
            size = 34.dp,
            cornerRadius = 11.dp
        )
        Spacer(Modifier.height(Dimens.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextLight
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

@Composable
private fun PillarItem(
    icon: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    AppCard(modifier = modifier, contentPadding = PaddingValues(Dimens.md)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, style = HanziMedium, color = TextLight)
            Spacer(Modifier.height(Dimens.xs))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextLight
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun RecentWordCard(
    wordWithSrs: WordWithSrs,
    pronunciationService: com.example.audio.PronunciationService
) {
    AppCard(
        modifier = Modifier.width(150.dp),
        contentPadding = PaddingValues(Dimens.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "HSK ${wordWithSrs.word.hskLevel}",
                style = MaterialTheme.typography.labelSmall,
                color = AccentPrimary
            )
            PronunciationButton(
                service = pronunciationService,
                request = remember(wordWithSrs.word.id) {
                    PronunciationRequest.forWord(
                        sourceId = wordWithSrs.word.id,
                        hanzi = wordWithSrs.word.hanzi,
                        pinyin = wordWithSrs.word.pinyin,
                        toneNumber = wordWithSrs.word.toneNumber
                    )
                },
                contentDescription = "Hear ${wordWithSrs.word.hanzi} pronounced",
                testTag = "home_recent_audio"
            )
        }

        Spacer(Modifier.height(Dimens.sm))

        Text(
            text = wordWithSrs.word.hanzi,
            style = HanziMedium,
            color = TextLight
        )
        Text(
            text = wordWithSrs.word.pinyin,
            style = PinyinText,
            color = AccentCyan
        )
        Text(
            text = wordWithSrs.word.meaning,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1
        )
    }
}
