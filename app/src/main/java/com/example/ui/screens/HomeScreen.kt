package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WordWithSrs
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onStartReview: () -> Unit,
    onNavigateToAddWord: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val allWords by viewModel.userWords.collectAsState()
    val dashboardState by viewModel.dashboardState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
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
                                text = "HanziFlow",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextLight
                            )
                            Text(
                                text = currentUser?.displayName ?: "Learner",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.sendDueReminderNotification() },
                        modifier = Modifier.testTag("send_notification_icon")
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = "Test Notification Reminder", tint = LilacPrimary)
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )

                    is DashboardUiState.Failed -> DashboardError(
                        message = state.message,
                        onRetry = { viewModel.refreshDashboard() }
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
                        Text(
                            text = "View all (${allWords.size})",
                            color = LilacPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { onNavigateToLibrary() }
                                .testTag("home_view_all_library")
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (allWords.isEmpty()) {
                        // An empty list with no explanation looks like a failure to load.
                        Text(
                            text = "Nothing in your collection yet. Add a word to get started.",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(allWords.take(6)) { wordWithSrs ->
                                RecentWordCard(
                                    wordWithSrs = wordWithSrs,
                                    onPlayAudio = { viewModel.playWordAudio(wordWithSrs.word.hanzi) }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PillarItem(
                            icon = "笔",
                            title = "Writing",
                            subtitle = "Stroke order",
                            modifier = Modifier.weight(1f)
                        )
                        PillarItem(
                            icon = "音",
                            title = "Audio",
                            subtitle = "Mandarin",
                            modifier = Modifier.weight(1f)
                        )
                        PillarItem(
                            icon = "义",
                            title = "Meaning",
                            subtitle = "In context",
                            modifier = Modifier.weight(1f)
                        )
                        PillarItem(
                            icon = "忆",
                            title = "Recall",
                            subtitle = "SM-2 spaced",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item(key = "tail") {
                Spacer(modifier = Modifier.height(40.dp))
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
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextLight)
            Text(subtitle, fontSize = 9.sp, color = TextMuted, maxLines = 1)
        }
    }
}

@Composable
private fun RecentWordCard(
    wordWithSrs: WordWithSrs,
    onPlayAudio: () -> Unit
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
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = LilacPrimary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                IconButton(
                    onClick = onPlayAudio,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Listen", tint = LilacPrimary, modifier = Modifier.size(16.dp))
                }
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

