package com.example.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.data.model.WordWithSrs
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
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.SrsHardDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onWordSelected: (WordWithSrs) -> Unit = {}
) {
    val allWords by viewModel.userWords.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "DUE", "LEARNING", "MASTERED"
    var selectedWordForModal by remember { mutableStateOf<WordWithSrs?>(null) }
    var wordPendingDeletion by remember { mutableStateOf<WordWithSrs?>(null) }

    val filteredWords = remember(allWords, searchQuery, selectedFilter) {
        allWords.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                    item.word.hanzi.contains(searchQuery, ignoreCase = true) ||
                    item.word.pinyin.contains(searchQuery, ignoreCase = true) ||
                    item.word.meaning.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "DUE" -> item.isDue
                "LEARNING" -> item.srs?.state == "LEARNING" || item.srs?.state == "NEW"
                "MASTERED" -> item.srs?.state == "MASTERED"
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Vocabulary Library (${allWords.size})",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
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
        ) {
            // Search Bar & Filter Row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search Hanzi, Pinyin or English...", color = TextSubtle) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = LilacPrimary) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LilacPrimary,
                        unfocusedBorderColor = OutlineBorder,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        cursorColor = LilacPrimary,
                        focusedContainerColor = DarkSurfaceContainer,
                        unfocusedContainerColor = DarkSurfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_search_field")
                )

                Spacer(modifier = Modifier.height(10.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf(
                        "ALL" to "All Words",
                        "DUE" to "Due Today",
                        "LEARNING" to "In Progress",
                        "MASTERED" to "Mastered"
                    )
                    items(filters) { (key, label) ->
                        FilterChip(
                            selected = selectedFilter == key,
                            onClick = { selectedFilter = key },
                            label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LilacPrimary,
                                selectedLabelColor = LilacPrimaryDark,
                                containerColor = DarkSurfaceContainer,
                                labelColor = TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedFilter == key,
                                borderColor = OutlineBorder,
                                selectedBorderColor = LilacPrimary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Words List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredWords, key = { it.word.id }) { item ->
                    WordLibraryRow(
                        wordWithSrs = item,
                        onClick = {
                            selectedWordForModal = item
                            onWordSelected(item)
                        },
                        onPlayAudio = { viewModel.playWordAudio(item.word.hanzi) },
                        onDelete = { wordPendingDeletion = item }
                    )
                }

                if (filteredWords.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 28.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (allWords.isEmpty()) "Your vocabulary library is ready for its first word." else "No words match these filters.",
                                    color = TextLight,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (allWords.isEmpty()) "Add a Hanzi from the Routine tab to begin your review deck." else "Try another search term or clear a filter.",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }

    // Detail Dialog Modal
    selectedWordForModal?.let { wordWithSrs ->
        WordDetailDialog(
            wordWithSrs = wordWithSrs,
            onDismiss = { selectedWordForModal = null },
            onPlayAudio = { viewModel.playWordAudio(wordWithSrs.word.hanzi) },
            onPlaySentence = { viewModel.playSentenceAudio(wordWithSrs.word.exampleCn) }
        )
    }

    wordPendingDeletion?.let { wordWithSrs ->
        AlertDialog(
            onDismissRequest = { wordPendingDeletion = null },
            title = { Text("Remove ${wordWithSrs.word.hanzi}?", color = TextLight, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This removes the word and its review history from this device. This cannot be undone.",
                    color = TextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteWord(wordWithSrs.word.id)
                        if (selectedWordForModal?.word?.id == wordWithSrs.word.id) selectedWordForModal = null
                        wordPendingDeletion = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SrsAgainDark, contentColor = DarkBg)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { wordPendingDeletion = null }) { Text("Keep word", color = LilacPrimary) }
            },
            containerColor = DarkSurfaceCard,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun WordLibraryRow(
    wordWithSrs: WordWithSrs,
    onClick: () -> Unit,
    onPlayAudio: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("word_row_${wordWithSrs.word.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hanzi Box
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceContainer)
                    .border(1.dp, OutlineBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = wordWithSrs.word.hanzi,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextLight
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = wordWithSrs.word.pinyin,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LilacPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = DarkSurfaceElevated,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
                    ) {
                        Text(
                            text = "HSK ${wordWithSrs.word.hskLevel}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = LilacPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = wordWithSrs.word.meaning,
                    fontSize = 13.sp,
                    color = TextLight,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                // SRS Badge
                val srsColor = when (wordWithSrs.state) {
                    "MASTERED" -> SrsEasyDark
                    "REVIEW" -> SrsGoodDark
                    "LEARNING" -> SrsHardDark
                    else -> SrsAgainDark
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(srsColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${wordWithSrs.state} • Interval: ${wordWithSrs.srs?.intervalDays ?: 0}d",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            // Quick Audio TTS
            IconButton(onClick = onPlayAudio) {
                Icon(Icons.Default.VolumeUp, contentDescription = "Pronounce", tint = LilacPrimary)
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove word", tint = TextMuted)
            }
        }
    }
}

@Composable
fun WordDetailDialog(
    wordWithSrs: WordWithSrs,
    onDismiss: () -> Unit,
    onPlayAudio: () -> Unit,
    onPlaySentence: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LilacPrimary, contentColor = LilacPrimaryDark)
            ) {
                Text("Close", fontWeight = FontWeight.SemiBold)
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${wordWithSrs.word.hanzi} (${wordWithSrs.word.pinyin})",
                    fontWeight = FontWeight.SemiBold,
                    color = TextLight
                )
                IconButton(onClick = onPlayAudio) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, tint = LilacPrimary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Stroke guide
                InteractiveStrokeSection(
                    hanzi = wordWithSrs.word.hanzi,
                    strokeBreakdown = wordWithSrs.word.strokeJson
                )

                Spacer(modifier = Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurfaceContainer, RoundedCornerShape(16.dp))
                        .border(1.dp, OutlineBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Text("Meaning:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextMuted)
                    Text(wordWithSrs.word.meaning, fontSize = 14.sp, color = TextLight)

                    if (wordWithSrs.word.radical.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Radical:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextMuted)
                        Text(wordWithSrs.word.radical, fontSize = 14.sp, color = LilacPrimary)
                    }

                    if (wordWithSrs.word.exampleCn.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Context Sentence:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextMuted)
                            IconButton(onClick = onPlaySentence, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = LilacPrimary, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(wordWithSrs.word.exampleCn, fontSize = 14.sp, fontWeight = FontWeight.Normal, color = TextLight)
                        Text(wordWithSrs.word.examplePy, fontSize = 12.sp, color = LilacPrimary)
                        Text(wordWithSrs.word.exampleEn, fontSize = 12.sp, color = TextMuted)
                    }
                }
            }
        },
        containerColor = DarkSurfaceCard,
        shape = RoundedCornerShape(24.dp)
    )
}
