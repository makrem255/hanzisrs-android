package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.WordWithSrs
import com.example.data.srs.SrsRating
import com.example.data.srs.SrsAlgorithm
import com.example.ui.components.InteractiveStrokeSection
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeDeckReviewScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val dueWords by viewModel.dueWords.collectAsState()
    val allWords by viewModel.userWords.collectAsState()
    val reviewSessionWordIds by viewModel.reviewSessionWordIds.collectAsState()

    // Notifications can enter this screen directly, without the dashboard's Start action.
    LaunchedEffect(reviewSessionWordIds, dueWords, allWords) {
        if (reviewSessionWordIds == null) {
            viewModel.ensureReviewSession(if (dueWords.isNotEmpty()) dueWords else allWords)
        }
    }

    val reviewDeck = remember(reviewSessionWordIds, allWords) {
        reviewSessionWordIds?.mapNotNull { wordId ->
            allWords.firstOrNull { it.word.id == wordId }
        }.orEmpty()
    }

    val currentDeckIndex by viewModel.currentDeckIndex.collectAsState()
    val isFlipped by viewModel.isCardFlipped.collectAsState()
    val isSlowTts by viewModel.isSlowTts.collectAsState()
    val reviewedCount by viewModel.reviewedSessionCount.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var selectedPillarTab by remember { mutableIntStateOf(0) } // 0 = Writing & Strokes, 1 = Meaning & Radical, 2 = Context Sentence

    val isFinished = currentDeckIndex >= reviewDeck.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Daily SRS Review",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextLight
                        )
                        if (!isFinished && reviewDeck.isNotEmpty()) {
                            Text(
                                text = "Card ${currentDeckIndex + 1} of ${reviewDeck.size}",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LilacPrimary)
                    }
                },
                actions = {
                    // Slow TTS Toggle
                    IconButton(onClick = { viewModel.toggleSlowTts() }) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Toggle TTS Speed",
                            tint = if (isSlowTts) LilacPrimary else TextMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        if (reviewSessionWordIds == null && allWords.isNotEmpty()) {
            ReviewSessionLoadingView(modifier = Modifier.padding(padding))
        } else if (isFinished || reviewDeck.isEmpty()) {
            ReviewSessionCompletedView(
                totalReviewed = reviewedCount,
                onBack = onNavigateBack,
                onRestart = { viewModel.startReviewSession(allWords) },
                modifier = Modifier.padding(padding)
            )
        } else {
            val currentWordWithSrs = reviewDeck[currentDeckIndex]
            val nextIntervals = remember(currentWordWithSrs.srs) {
                SrsRating.entries.associateWith { rating ->
                    SrsAlgorithm.calculateNextReview(currentWordWithSrs.srs, rating).nextReviewLabel
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Progress Bar
                LinearProgressIndicator(
                    progress = { (currentDeckIndex + 1).toFloat() / reviewDeck.size.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = LilacPrimary,
                    trackColor = DarkSurfaceContainer
                )

                Spacer(modifier = Modifier.height(12.dp))

                // The Swipable Flashcard Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .rotate(offsetX.value / 40f)
                        .pointerInput(currentDeckIndex) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (offsetX.value > 250f) {
                                        // Swiped Right -> Good
                                        coroutineScope.launch {
                                            offsetX.animateTo(1000f, spring())
                                            viewModel.submitRating(currentWordWithSrs, SrsRating.GOOD)
                                            offsetX.snapTo(0f)
                                        }
                                    } else if (offsetX.value < -250f) {
                                        // Swiped Left -> Hard
                                        coroutineScope.launch {
                                            offsetX.animateTo(-1000f, spring())
                                            viewModel.submitRating(currentWordWithSrs, SrsRating.HARD)
                                            offsetX.snapTo(0f)
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            offsetX.animateTo(0f, spring())
                                        }
                                    }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    coroutineScope.launch {
                                        offsetX.snapTo(offsetX.value + dragAmount)
                                    }
                                }
                            )
                        }
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxSize(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Card Header: HSK Badge & Mandarin Audio TTS Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = DarkSurfaceElevated,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
                                ) {
                                    Text(
                                        text = "HSK ${currentWordWithSrs.word.hskLevel}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LilacPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    color = if (currentWordWithSrs.isDue) SrsAgainDark.copy(alpha = 0.25f) else SrsGoodDark.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (currentWordWithSrs.isDue) SrsAgainDark else SrsGoodDark)
                                ) {
                                    Text(
                                        text = currentWordWithSrs.state,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (currentWordWithSrs.isDue) SrsAgainDark else SrsGoodDark,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                // Audio TTS Button
                                FilledTonalButton(
                                    onClick = { viewModel.playWordAudio(currentWordWithSrs.word.hanzi) },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = LilacPrimary,
                                        contentColor = LilacPrimaryDark
                                    ),
                                    shape = CircleShape,
                                    modifier = Modifier.size(42.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Icon(Icons.Default.VolumeUp, contentDescription = "Hear Pronunciation", tint = LilacPrimaryDark)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Large Hanzi Display
                            Text(
                                text = currentWordWithSrs.word.hanzi,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Normal,
                                color = TextLight,
                                textAlign = TextAlign.Center
                            )

                            if (!isFlipped) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Recall the pronunciation and meaning before revealing the answer.",
                                    fontSize = 13.sp,
                                    color = TextMuted,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = viewModel::flipCard,
                                    shape = RoundedCornerShape(22.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = LilacPrimary,
                                        contentColor = LilacPrimaryDark
                                    )
                                ) {
                                    Icon(Icons.Default.Flip, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reveal answer", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            AnimatedVisibility(visible = isFlipped) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    // Pinyin with Tone marks
                                    Text(
                                        text = currentWordWithSrs.word.pinyin,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = LilacPrimary,
                                        textAlign = TextAlign.Center
                                    )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 4-Pillar Pill Tabs (Writing, Meaning, Context Sentence)
                            Surface(
                                shape = CircleShape,
                                color = DarkSurfaceContainer,
                                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(3.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(CircleShape)
                                            .background(if (selectedPillarTab == 0) LilacPrimary else Color.Transparent)
                                            .clickable { selectedPillarTab = 0 },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "✍️ Writing",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (selectedPillarTab == 0) LilacPrimaryDark else TextSubtle
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(CircleShape)
                                            .background(if (selectedPillarTab == 1) LilacPrimary else Color.Transparent)
                                            .clickable { selectedPillarTab = 1 },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "📖 Meaning",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (selectedPillarTab == 1) LilacPrimaryDark else TextSubtle
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(CircleShape)
                                            .background(if (selectedPillarTab == 2) LilacPrimary else Color.Transparent)
                                            .clickable { selectedPillarTab = 2 },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "💬 Context",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (selectedPillarTab == 2) LilacPrimaryDark else TextSubtle
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Tab 0: PILLAR 1 - Interactive Animated Stroke Order & Tracing
                            if (selectedPillarTab == 0) {
                                InteractiveStrokeSection(
                                    hanzi = currentWordWithSrs.word.hanzi,
                                    strokeBreakdown = currentWordWithSrs.word.strokeJson
                                )
                            }

                            // Tab 1: PILLAR 2 & 3 - Definition, Radical, Part of speech
                            if (selectedPillarTab == 1) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(DarkSurfaceContainer)
                                        .border(1.dp, OutlineBorder, RoundedCornerShape(20.dp))
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text("English Translation", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = currentWordWithSrs.word.meaning,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextLight
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    if (currentWordWithSrs.word.radical.isNotBlank()) {
                                        Text("Radical (部首)", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                                        Text(
                                            text = currentWordWithSrs.word.radical,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = LilacPrimary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Hearing, contentDescription = null, tint = SrsGoodDark, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Tap TTS button to practice pronunciation listening",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }

                            // Tab 2: PILLAR 3 - Contextual Example Sentence
                            if (selectedPillarTab == 2) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(DarkSurfaceContainer)
                                        .border(1.dp, OutlineBorder, RoundedCornerShape(20.dp))
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Example Sentence", fontSize = 12.sp, color = LilacPrimary, fontWeight = FontWeight.SemiBold)
                                        IconButton(
                                            onClick = { viewModel.playSentenceAudio(currentWordWithSrs.word.exampleCn) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.VolumeUp, contentDescription = "Play sentence", tint = LilacPrimary, modifier = Modifier.size(20.dp))
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = currentWordWithSrs.word.exampleCn,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextLight
                                    )

                                    if (currentWordWithSrs.word.examplePy.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = currentWordWithSrs.word.examplePy,
                                            fontSize = 13.sp,
                                            color = LilacPrimary,
                                            fontWeight = FontWeight.Normal
                                        )
                                    }

                                    if (currentWordWithSrs.word.exampleEn.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = currentWordWithSrs.word.exampleEn,
                                            fontSize = 13.sp,
                                            color = TextMuted
                                        )
                                    }
                                }
                            }
                                }
                            }
                        }
                    }
                }

                if (isFlipped) {
                    Spacer(modifier = Modifier.height(12.dp))

                // PILLAR 4: SRS SM-2 RATING BUTTONS (Again, Hard, Good, Easy)
                Text(
                    text = "Rate Recall Difficulty (Updates Spaced Repetition):",
                    fontSize = 12.sp,
                    color = TextMuted,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Again (<1d)
                    SrsRatingButton(
                        rating = SrsRating.AGAIN,
                        label = "Again",
                        interval = nextIntervals.getValue(SrsRating.AGAIN),
                        color = SrsAgainDark,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.AGAIN) }
                    )

                    // 2. Hard (~1.2x)
                    SrsRatingButton(
                        rating = SrsRating.HARD,
                        label = "Hard",
                        interval = nextIntervals.getValue(SrsRating.HARD),
                        color = SrsHardDark,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.HARD) }
                    )

                    // 3. Good (~2.5x)
                    SrsRatingButton(
                        rating = SrsRating.GOOD,
                        label = "Good",
                        interval = nextIntervals.getValue(SrsRating.GOOD),
                        color = SrsGoodDark,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.GOOD) }
                    )

                    // 4. Easy (>3.5x)
                    SrsRatingButton(
                        rating = SrsRating.EASY,
                        label = "Easy",
                        interval = nextIntervals.getValue(SrsRating.EASY),
                        color = SrsEasyDark,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.EASY) }
                    )
                }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SrsRatingButton(
    rating: SrsRating,
    label: String,
    interval: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedButton(
        onClick = onClick,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = color.copy(alpha = 0.2f),
            contentColor = color
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = modifier
            .height(52.dp)
            .testTag("srs_rate_${label.lowercase()}"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
            Text(interval, fontSize = 10.sp, color = color.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun ReviewSessionCompletedView(
    totalReviewed: Int,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(SrsGoodContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SrsGoodDark, modifier = Modifier.size(42.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Review Session Finished!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextLight
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "You practiced the 4-Pillar routine on $totalReviewed cards today. Your spaced repetition intervals have been recalculated.",
                    fontSize = 13.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = LilacPrimary, contentColor = LilacPrimaryDark),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Return to Dashboard", fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onRestart,
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text("Review Entire Deck Again", color = TextLight, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ReviewSessionLoadingView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(color = LilacPrimary)
    }
}
