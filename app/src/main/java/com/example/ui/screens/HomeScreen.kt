package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.PronunciationButton
import com.example.audio.PronunciationRequest
import com.example.audio.PronunciationService
import com.example.data.model.WordWithSrs
import com.example.ui.components.IconTarget
import com.example.ui.components.rememberNotificationRequest
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.LilacPrimary
import com.example.ui.theme.LilacPrimaryDark
import com.example.ui.theme.LilacSecondary
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsAgainDark
import com.example.ui.theme.SrsEasyDark
import com.example.ui.theme.SrsGoodContainer
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.SrsHardDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.DashboardUiState
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.ProgressUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onStartReview: () -> Unit,
    onNavigateToAddWord: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val allWords by viewModel.userWords.collectAsStateWithLifecycle()

    // Both needed to tell the three things an empty `allWords` can mean apart: not asked yet,
    // asked and failed, and genuinely nothing. See the collection strip below.
    val wordsLoaded by viewModel.wordsLoaded.collectAsStateWithLifecycle()
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()
    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val progressState by viewModel.progressState.collectAsStateWithLifecycle()

    // A posted notification is the only cross-process thing this screen starts, and on
    // Android 13+ it may need a permission the learner has not been asked for yet.
    var notificationMessage by remember { mutableStateOf<String?>(null) }
    val sendDueReminderPreview = rememberNotificationRequest(
        onGranted = { viewModel.sendDueReminderNotification() },
        onDenied = {
            notificationMessage =
                "Notifications are off. You can enable them in Android system settings."
        }
    )

    val snackbarHostState = remember { SnackbarHostState() }

    // Refused, or permanently denied. Android does not distinguish the two, and the honest
    // message is the same either way.
    LaunchedEffect(notificationMessage) {
        notificationMessage?.let {
            snackbarHostState.showSnackbar(it)
            notificationMessage = null
        }
    }

    Scaffold(
        // The one message this screen has to be able to say is "your notification permission
        // was refused". Without somewhere to say it, the bell would be the same silent
        // no-op it was before - the request would be made, and nothing would follow.
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // This app bar does not add the status-bar inset itself: the outer
                // Scaffold in MainActivity already padded the whole NavHost by it, and the
                // insets were therefore applied twice - once by that padding and once by
                // TopAppBarDefaults.windowInsets - pushing every title down by an extra
                // ~24-48dp. AuthScreen is the reason this is fixed here rather than by
                // zeroing the outer Scaffold's contentWindowInsets: it has no app bar of its
                // own and depends on that outer padding for its top inset.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(LilacPrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("学", color = LilacPrimaryDark, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextLight
                            )
                            Text(
                                text = currentUser?.displayName?.takeIf { it.isNotBlank() }
                                    ?: "Learner",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }
                },
                actions = {
                    // Through `rememberNotificationRequest`, not straight to the view model.
                    //
                    // This button posted the notification unconditionally, and
                    // `NotificationHelper` swallows the `SecurityException` that `POST_NOTIFICATIONS`
                    // raises on Android 13+ when it has not been granted. It is requested
                    // nowhere else in the app, so on a fresh install this was a control that
                    // did nothing, gave no feedback, and looked identical to one that had
                    // worked. The settings screen's equivalent button already asked; two
                    // spellings of one rule, and only one of them was right.
                    IconButton(
                        onClick = sendDueReminderPreview,
                        modifier = Modifier.testTag("send_notification_icon")
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = "Preview the due review alert", tint = LilacPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBg
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAddWord,
                containerColor = LilacPrimary,
                contentColor = LilacPrimaryDark,
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // The dashboard answers the four questions - what to study, how much is due, how
            // am I doing, what next - from real rows. Loading, empty and failure are all
            // explicit: a dashboard that quietly draws zeroes when it cannot read its data is
            // indistinguishable from a learner who has done nothing, and the two deserve
            // opposite advice.
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
                        // Tapping a difficult word has nowhere to go yet: there is no
                        // single-word screen. Rather than navigate somewhere arbitrary, this
                        // starts a review, which is the action the word is being surfaced for.
                        onOpenWord = {
                            viewModel.resetDeckSession()
                            onStartReview()
                        }
                    )
                }
            }

            // Level, totals and badges. After the dashboard rather than inside it, because
            // this is history and the dashboard is "what next" - putting badges above the queue
            // would lead with something already earned instead of something to do.
            item(key = "progress") {
                when (val state = progressState) {
                    is ProgressUiState.Loading -> DashboardLoading(
                        label = "your progress",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )

                    is ProgressUiState.Failed -> DashboardError(
                        message = state.message,
                        onRetry = { viewModel.refreshProgress() },
                        label = "progress"
                    )

                    is ProgressUiState.Ready -> ProgressContent(progress = state.progress)
                }
            }

            // Recent vocabulary. Real rows from the learner's own collection, unchanged.
            item(key = "recent") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent vocabulary",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextLight
                        )
                        // A `TextButton`, not a `clickable` `Text`.
                        //
                        // The clickable text was ~18dp tall — about a third of the way to the
                        // smallest thing a fingertip can reliably land on — and it announced
                        // itself to a screen reader as plain text with no indication it was
                        // a control. The label now says what it does, and the target is a
                        // real one.
                        TextButton(
                            onClick = onNavigateToLibrary,
                            modifier = Modifier.testTag("home_view_all_library"),
                            colors = ButtonDefaults.textButtonColors(contentColor = LilacPrimary),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 8.dp
                            )
                        ) {
                            Text(
                                // Withheld until there is an answer, for the same reason the
                                // library screen withholds its own count - and that fix stopped
                                // here from being applied twice.
                                //
                                // `allWords` starts empty before the query runs, so this told a
                                // learner with a full collection they had nothing. Worse on a
                                // failed read: the view model degrades an unreadable database to
                                // an empty list because a `List` flow has no error variant, so a
                                // full disk rendered "View all (0)" directly above "Your
                                // vocabulary could not be read".
                                text = if (wordsLoaded && libraryError == null) {
                                    "View all (${allWords.size})"
                                } else {
                                    "View all"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (allWords.isEmpty()) {
                        // An empty list with no explanation looks like a failure to load.
                        //
                        // Three states, not one. `allWords` starts empty before the query has
                        // run, so this used to tell a learner with a full library that they had
                        // nothing in their collection, every time they opened the app. The
                        // library screen has guarded this exact hazard with a `wordsLoaded` gate
                        // since the last audit; the home screen never got the same treatment.
                        // The error case is here too, and it is the one that mattered: a failed
                        // read degrades to an empty list, so without this a full disk told a
                        // learner their collection was gone. Written `error ?: when { … }`
                        // because a `by`-delegated property cannot be smart-cast.
                        Text(
                            text = libraryError ?: when {
                                !wordsLoaded -> "Loading your collection…"
                                else -> "Nothing in your collection yet. Add a word to get started."
                            },
                            fontSize = 13.sp,
                            color = TextMuted,
                            modifier = Modifier.testTag("home_collection_state")
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(allWords.take(6)) { wordWithSrs ->
                                RecentWordCard(
                                    wordWithSrs = wordWithSrs,
                                    pronunciationService = viewModel.pronunciationService
                                )
                            }
                        }
                    }
                }
            }

            // Quick add.
            item(key = "add") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToAddWord() }
                        .testTag("home_add_word"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(DarkSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = LilacPrimary)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add a word",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = TextLight
                            )
                            Text(
                                text = "Type hanzi or pinyin. Fill in pinyin, meaning and examples yourself or with AI.",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            // What a review actually covers. Kept, but last: it describes the app rather than
            // the learner's progress, and it was previously the second thing on the screen.
            // The subtitles state what really happens - the stroke view shows a named stroke
            // sequence to trace, it does not animate stroke order.
            item(key = "pillars") {
                Column {
                    Text(
                        text = "What a review covers",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Two columns on a phone, four when there is room.
                    //
                    // Four columns across a 360dp phone gives each card ~(360-32-24)/4 =
                    // 76dp. "Stroke order" at 11sp is about 68dp, so the subtitle was
                    // ellipsised to "Stroke…" in a card whose whole job is to say what the
                    // stroke view contains — and the truncation is silent, because
                    // `maxLines = 1` clips rather than wraps. Two columns gives ~156dp per
                    // card, which fits every label here with room to spare, and the four
                    // still sit on one row on a tablet where the width is real.
                    val pillarItems = listOf(
                        Triple("笔", "Writing", "Stroke order"),
                        Triple("音", "Audio", "Mandarin"),
                        Triple("义", "Meaning", "In context"),
                        Triple("忆", "Recall", "SM-2 spaced")
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val perRow = if (maxWidth >= 400.dp) 4 else 2
                        pillarItems.chunked(perRow).forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { (glyph, title, subtitle) ->
                                    PillarItem(
                                        icon = glyph,
                                        title = title,
                                        subtitle = subtitle,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                // Keeps the last row's items the same width as a full row's,
                                // so a half-empty final row does not produce one wide card
                                // next to two narrow ones.
                                repeat(perRow - row.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            item(key = "tail") {
                // Clears the floating action button: 56dp of button plus 16dp of margin. It
                // was 40dp, so on a phone the FAB sat over the bottom of the list and the
                // last card's meaning was partly behind it — the one control that adds
                // something, covering the content.
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun PillarItem(
    icon: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextLight)
            // 11sp, the floor this app uses for body-adjacent text. It was 9sp, which is
            // below the point where the glyphs are reliably distinguishable on a 6.1" screen
            // held at arm's length.
            //
            // `maxLines = 1` is gone. The two-column layout above exists to stop these labels
            // being ellipsised - the comment there records "Stroke order" becoming "Stroke…" -
            // but the constraint it fixes is measured in dp, and `BoxWithConstraints` does not
            // scale with the user's font scale while this text does. At 1.5x the labels outgrow
            // the same fixed column and clip all over again, with the `maxLines` doing it
            // silently. Wrapping costs one line of height in the one place on this screen with
            // room to spare, and it cannot lose text.
            Text(subtitle, fontSize = 11.sp, color = TextMuted)
        }
    }
}

@Composable
private fun RecentWordCard(
    wordWithSrs: WordWithSrs,
    pronunciationService: PronunciationService
) {
    Card(
        modifier = Modifier.width(135.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = DarkSurfaceElevated,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "HSK ${wordWithSrs.word.hskLevel}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = LilacPrimary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // 48dp, where it was 24dp. The size is no longer this file's business —
                // `PronunciationButton` owns the floor, and owns the spinner, the replay and
                // the failure caption that the bare `IconTarget` had no way to show.
                //
                // Only one of these six cards can be playing, and which one is decided by the
                // `sourceId` each request carries. That is why the button is handed the service
                // rather than an `onPlayAudio` callback: a callback cannot be told what the
                // engine is doing, so a row that had finished playing looked identical to one
                // that was still going.
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

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = wordWithSrs.word.hanzi,
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
                color = TextLight
            )

            Text(
                text = wordWithSrs.word.pinyin,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = LilacPrimary
            )

            Text(
                text = wordWithSrs.word.meaning,
                fontSize = 11.sp,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}
