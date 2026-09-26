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
import androidx.compose.material.icons.filled.VolumeUp
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
    val dueWords by viewModel.dueWords.collectAsState()
    val allWords by viewModel.userWords.collectAsState()
    val dueCount by viewModel.dueCount.collectAsState()
    val reviewedSessionCount by viewModel.reviewedSessionCount.collectAsState()

    // Calculate SRS stats
    val learningCount = allWords.count { it.srs?.state == "LEARNING" }
    val newCount = allWords.count { it.srs?.state == "NEW" || it.srs == null }
    val reviewCount = allWords.count { it.srs?.state == "REVIEW" }
    val masteredCount = allWords.count { it.srs?.state == "MASTERED" }

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
            // 1. HERO DUE WORDS & START REVIEW BANNER
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                color = DarkSurfaceElevated,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = LilacPrimary, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Daily SRS Routine", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LilacPrimary)
                                }
                            }

                            Surface(
                                color = DarkSurfaceContainer,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "${allWords.size} words in deck",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (dueCount > 0) "$dueCount Words Due Today" else "All Caught Up Today!",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextLight,
                            letterSpacing = (-0.5).sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (dueCount > 0) {
                                "Practice Writing, Mandarin TTS, Context Sentences & SRS SM-2 recall difficulty scoring."
                            } else {
                                "Excellent! You have completed all scheduled SRS reviews for today."
                            },
                            fontSize = 13.sp,
                            color = TextMuted,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = {
                                viewModel.resetDeckSession()
                                onStartReview()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LilacPrimary,
                                contentColor = LilacPrimaryDark
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("start_review_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = LilacPrimaryDark)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (dueCount > 0) "Start 4-Pillars Review ($dueCount Due)" else "Practice Full Deck (${allWords.size})",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. THE 4 PILLARS OF CHINESE LEARNING (Features Overview)
            item {
                Text(
                    text = "The 4-Pillar Daily System",
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
                        icon = "✍️",
                        title = "Writing",
                        subtitle = "Animated strokes",
                        modifier = Modifier.weight(1f)
                    )
                    PillarItem(
                        icon = "🔊",
                        title = "Audio",
                        subtitle = "Mandarin TTS",
                        modifier = Modifier.weight(1f)
                    )
                    PillarItem(
                        icon = "📖",
                        title = "Meaning",
                        subtitle = "Context sentences",
                        modifier = Modifier.weight(1f)
                    )
                    PillarItem(
                        icon = "🧠",
                        title = "SRS Engine",
                        subtitle = "SM-2 intervals",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. SRS MASTERY LEVEL DISTRIBUTION
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SRS Mastery Distribution",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextLight
                            )
                            Surface(
                                color = SrsGoodContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "$masteredCount Mastered",
                                    color = SrsGoodDark,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatBox(count = newCount, label = "New", color = SrsAgainDark)
                            StatBox(count = learningCount, label = "Learning", color = SrsHardDark)
                            StatBox(count = reviewCount, label = "Review", color = SrsGoodDark)
                            StatBox(count = masteredCount, label = "Mastered", color = SrsEasyDark)
                        }
                    }
                }
            }

            // 4. QUICK ADD & AI GENERATE HIGHLIGHT
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToAddWord() },
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
                            Text("Add Word with AI Auto-Fill", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextLight)
                            Text("Type Hanzi or Pinyin → AI generates Pinyin, sentences & stroke order → Review & Approve", fontSize = 12.sp, color = TextMuted)
                        }
                    }
                }
            }

            // 5. RECENT WORDS IN YOUR DECK
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Vocabulary",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
                    Text(
                        text = "View All (${allWords.size})",
                        color = LilacPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { onNavigateToLibrary() }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(allWords.take(6)) { wordWithSrs ->
                        RecentWordCard(
                            wordWithSrs = wordWithSrs,
                            onPlayAudio = { viewModel.playWordAudio(wordWithSrs.word.hanzi) }
                        )
                    }
                }
            }

            item {
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
private fun StatBox(
    count: Int,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = color.copy(alpha = 0.15f),
            shape = CircleShape,
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "$count",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 11.sp, color = TextLight, fontWeight = FontWeight.Medium)
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
                    Icon(Icons.Default.VolumeUp, contentDescription = "Listen", tint = LilacPrimary, modifier = Modifier.size(16.dp))
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

