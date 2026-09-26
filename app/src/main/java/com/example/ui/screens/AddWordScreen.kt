package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.GeneratedWordData
import com.example.data.ai.WordDataOrigin
import com.example.ui.components.InteractiveStrokeSection
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.LilacPrimary
import com.example.ui.theme.LilacPrimaryDark
import com.example.ui.theme.LilacSecondary
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsGoodContainer
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.AiGenerationState
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddWordScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val aiState by viewModel.aiState.collectAsState()
    val wordSaveError by viewModel.wordSaveError.collectAsState()
    val isSavingWord by viewModel.isSavingWord.collectAsState()
    var searchInput by remember { mutableStateOf("") }

    val quickSuggestions = listOf("水 (shuǐ)", "爱 (ài)", "书 (shū)", "狗 (gǒu)", "吃 (chī)", "猫 (māo)", "火 (huǒ)")

    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LilacPrimary,
        unfocusedBorderColor = OutlineBorder,
        focusedTextColor = TextLight,
        unfocusedTextColor = TextLight,
        focusedLabelColor = LilacPrimary,
        unfocusedLabelColor = TextMuted,
        cursorColor = LilacPrimary,
        focusedContainerColor = DarkSurfaceContainer,
        unfocusedContainerColor = DarkSurfaceContainer
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (aiState is AiGenerationState.ReadyForReview) "Review & Approve Word" else "Add Word via AI",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (aiState is AiGenerationState.ReadyForReview) {
                            viewModel.resetAiState()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = LilacPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = aiState) {
                is AiGenerationState.Idle, is AiGenerationState.Loading, is AiGenerationState.Error -> {
                    // STEP 1: INPUT & GENERATE STEP
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = LilacPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "AI Chinese Word Enrichment",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextLight
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Enter a Hanzi character (e.g. 咖啡, 学) or Pinyin (e.g. shuǐ, péngyou). AI will auto-generate pinyin with tones, radical, stroke order breakdown, and context sentences.",
                                fontSize = 12.sp,
                                color = TextMuted,
                                lineHeight = 17.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = searchInput,
                                onValueChange = { searchInput = it },
                                label = { Text("Chinese Hanzi or Pinyin") },
                                placeholder = { Text("e.g. 茶 or chá", color = TextSubtle) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("word_search_input"),
                                shape = RoundedCornerShape(14.dp),
                                colors = customTextFieldColors
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Quick Suggestions
                            Text("Popular Examples:", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextMuted)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                quickSuggestions.take(4).forEach { item ->
                                    val hanziChar = item.substringBefore(" ")
                                    Surface(
                                        color = DarkSurfaceContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                                        modifier = Modifier.clickable {
                                            searchInput = hanziChar
                                            viewModel.generateWord(hanziChar)
                                        }
                                    ) {
                                        Text(
                                            text = item,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = LilacPrimary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = { viewModel.generateWord(searchInput) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("generate_word_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LilacPrimary,
                                    contentColor = LilacPrimaryDark
                                ),
                                shape = RoundedCornerShape(24.dp),
                                enabled = searchInput.isNotBlank() && state !is AiGenerationState.Loading
                            ) {
                                if (state is AiGenerationState.Loading) {
                                    CircularProgressIndicator(
                                        color = LilacPrimaryDark,
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Generating SRS Learning Data...", color = LilacPrimaryDark)
                                } else {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Auto-Generate & Review", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (state is AiGenerationState.Error) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Error: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                        // Offline sample data is now an explicit choice rather than a
                        // silent substitution. The result is labelled as local data.
                        TextButton(
                            onClick = { viewModel.useOfflineSampleFor(searchInput) },
                            enabled = searchInput.isNotBlank()
                        ) {
                            Text(
                                text = "Use offline sample data instead",
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                is AiGenerationState.ReadyForReview -> {
                    // STEP 2: MANDATORY REVIEW & APPROVAL SCREEN
                    WordReviewAndApprovalView(
                        generatedData = state.wordData,
                        customTextFieldColors = customTextFieldColors,
                        saveError = wordSaveError,
                        isSaving = isSavingWord,
                        onApprove = { hanzi, pinyin, meaning, hsk, radical, exCn, exPy, exEn, strokes ->
                            viewModel.approveAndSaveWord(
                                hanzi = hanzi,
                                pinyin = pinyin,
                                meaning = meaning,
                                hskLevel = hsk,
                                radical = radical,
                                exampleCn = exCn,
                                examplePy = exPy,
                                exampleEn = exEn,
                                strokeBreakdown = strokes,
                                onComplete = onNavigateBack
                            )
                        },
                        onPlayAudio = { viewModel.playWordAudio(it) },
                        onPlaySentence = { viewModel.playSentenceAudio(it) },
                        onCancel = { viewModel.resetAiState() }
                    )
                }
            }
        }
    }
}

@Composable
fun WordReviewAndApprovalView(
    generatedData: GeneratedWordData,
    customTextFieldColors: androidx.compose.material3.TextFieldColors,
    saveError: String?,
    isSaving: Boolean,
    onApprove: (
        hanzi: String,
        pinyin: String,
        meaning: String,
        hskLevel: Int,
        radical: String,
        exampleCn: String,
        examplePy: String,
        exampleEn: String,
        strokeBreakdown: String
    ) -> Unit,
    onPlayAudio: (String) -> Unit,
    onPlaySentence: (String) -> Unit,
    onCancel: () -> Unit
) {
    var hanzi by remember { mutableStateOf(generatedData.hanzi) }
    var pinyin by remember { mutableStateOf(generatedData.pinyin) }
    var meaning by remember { mutableStateOf(generatedData.meaning) }
    var hskLevel by remember { mutableIntStateOf(generatedData.hskLevel) }
    var radical by remember { mutableStateOf(generatedData.radical) }
    var exampleCn by remember { mutableStateOf(generatedData.exampleCn) }
    var examplePy by remember { mutableStateOf(generatedData.examplePy) }
    var exampleEn by remember { mutableStateOf(generatedData.exampleEn) }
    var strokeBreakdown by remember { mutableStateOf(generatedData.strokeBreakdown) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Approval Notice Banner
        Surface(
            color = DarkSurfaceElevated,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = LilacPrimary)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (generatedData.origin == WordDataOrigin.GEMINI) {
                        "AI suggestion: verify pinyin, meaning, sentences, and stroke details before saving."
                    } else {
                        "Built-in fallback: review and complete these details before saving. Add a Gemini key for AI enrichment."
                    },
                    fontSize = 12.sp,
                    color = TextLight,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Stroke Preview Section
        InteractiveStrokeSection(
            hanzi = hanzi,
            strokeBreakdown = strokeBreakdown
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Editable Form Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Character Details", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextLight)
                    IconButton(onClick = { onPlayAudio(hanzi) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Listen", tint = LilacPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = hanzi,
                        onValueChange = { hanzi = it },
                        label = { Text("Hanzi") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("edit_hanzi_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = customTextFieldColors
                    )
                    OutlinedTextField(
                        value = pinyin,
                        onValueChange = { pinyin = it },
                        label = { Text("Pinyin (with tones)") },
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("edit_pinyin_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = customTextFieldColors
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = meaning,
                    onValueChange = { meaning = it },
                    label = { Text("English Meaning") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_meaning_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(10.dp))

                // HSK Level Selector
                Text("HSK Level:", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextMuted)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..6).forEach { level ->
                        FilterChip(
                            selected = hskLevel == level,
                            onClick = { hskLevel = level },
                            label = { Text("HSK $level", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LilacPrimary,
                                selectedLabelColor = LilacPrimaryDark,
                                containerColor = DarkSurfaceContainer,
                                labelColor = TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = hskLevel == level,
                                borderColor = OutlineBorder,
                                selectedBorderColor = LilacPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = radical,
                    onValueChange = { radical = it },
                    label = { Text("Radical (部首)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Context Example Sentence Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Contextual Example Sentence", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextLight)
                    IconButton(onClick = { onPlaySentence(exampleCn) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Listen sentence", tint = LilacPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = exampleCn,
                    onValueChange = { exampleCn = it },
                    label = { Text("Sentence (Chinese)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = examplePy,
                    onValueChange = { examplePy = it },
                    label = { Text("Sentence (Pinyin)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = exampleEn,
                    onValueChange = { exampleEn = it },
                    label = { Text("Sentence (English)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = strokeBreakdown,
                    onValueChange = { strokeBreakdown = it },
                    label = { Text("Stroke Breakdown Names") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (saveError != null) {
                    Text(
                        text = saveError,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                // Primary Approve Button
                Button(
                    onClick = {
                        onApprove(
                            hanzi,
                            pinyin,
                            meaning,
                            hskLevel,
                            radical,
                            exampleCn,
                            examplePy,
                            exampleEn,
                            strokeBreakdown
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("approve_word_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LilacPrimary,
                        contentColor = LilacPrimaryDark
                    ),
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = LilacPrimaryDark,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Saving to your deck…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Approve & Add to SRS Deck", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight)
                ) {
                    Text("Cancel / Discard", color = TextMuted)
                }
            }
        }
    }
}
