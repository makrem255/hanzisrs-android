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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.PronunciationButton
import com.example.audio.PronunciationRequest
import com.example.audio.PronunciationService
import com.example.data.model.StorageValues
import com.example.data.model.WordWithSrs
import com.example.ui.components.IconTarget
import com.example.ui.components.InteractiveStrokeSection
import com.example.ui.components.PrimaryButton
import com.example.ui.components.minimumTouchTarget
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsAgainDark
import com.example.ui.theme.SrsEasyDark
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.SrsHardDark
import com.example.ui.theme.srsStateColorOrNull
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.MainViewModel
import com.example.util.plural

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onWordSelected: (WordWithSrs) -> Unit = {},
    onNavigateToAddWord: () -> Unit = {}
) {
    val allWords by viewModel.userWords.collectAsStateWithLifecycle()
    // Without this the screen cannot tell "you have no words" from "the query has not come back
    // yet", and `stateIn` starts the flow off empty - so every time a learner with forty words
    // opened this tab they were shown the title "Vocabulary Library (0)" and told their library
    // "is ready for its first word", for as long as the query took. The deck already reads this
    // flag for exactly this reason.
    val wordsLoaded by viewModel.wordsLoaded.collectAsStateWithLifecycle()

    // Distinct from "loaded and empty", and checked before it everywhere below.
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()
    // Saveable so a rotation does not throw away the search the learner was in the middle
    // of, and so returning to this tab from a review shows the same filtered list.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(Filter.ALL) }

    // So the keyboard's search key can take itself off the screen. The list below is
    // filtered live from `searchQuery`, so the results already exist; the key's job is to
    // stop covering them.
    val focusManager = LocalFocusManager.current
    var selectedWordForModal by remember { mutableStateOf<WordWithSrs?>(null) }
    var wordPendingDeletion by remember { mutableStateOf<WordWithSrs?>(null) }

    // An utterance started here must not follow the learner onto the next screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.pronunciationService.stop() }
    }

    val filteredWords = remember(allWords, searchQuery, selectedFilter) {
        allWords.filter { item ->
            // Tones are stripped before matching, so `ni3 hao3` finds `nǐ hǎo` and `cha`
            // finds `chá`. A learner typing pinyin on a phone keyboard has no tone marks
            // most of the time — the marks are hard to produce and the numeric form is what
            // a dictionary uses — and the previous literal `contains` matched neither.
            val matchesQuery = searchQuery.isBlank() ||
                    item.word.hanzi.contains(searchQuery, ignoreCase = true) ||
                    item.word.pinyin.contains(searchQuery, ignoreCase = true) ||
                    item.word.meaning.contains(searchQuery, ignoreCase = true) ||
                    item.word.exampleCn.contains(searchQuery, ignoreCase = true) ||
                    PinyinSearch.foldToneMarks(item.word.pinyin)
                        .contains(PinyinSearch.foldToneMarks(searchQuery), ignoreCase = true)

            // Compared against the enum's storage values rather than bare literals. `Filter`'s
            // own KDoc records that these literals were replaced with an enum on one side of
            // this screen and left as strings three lines away from it.
            val matchesFilter = when (selectedFilter) {
                Filter.DUE -> item.isDue
                Filter.LEARNING -> item.srs?.state == StorageValues.CardState.LEARNING.storageValue ||
                    item.srs?.state == StorageValues.CardState.NEW.storageValue
                Filter.MASTERED -> item.srs?.state == StorageValues.CardState.MASTERED.storageValue
                Filter.ALL -> true
            }

            matchesQuery && matchesFilter
        }
    }

    Scaffold(
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
                    Text(
                        // The count is a claim about the learner's collection, so it is withheld
                        // until there is an answer. "(0)" during the query is a statement that
                        // they have no words, which is both wrong and alarming — and on a failed
                        // read it was not merely premature, it was false: the view model emits an
                        // empty list because that is the only honest degradation available on a
                        // `List` flow, so a full disk rendered as "Vocabulary Library (0)" above
                        // a card inviting the learner to re-enter everything they had.
                        text = if (wordsLoaded && libraryError == null) {
                            "Learn (${allWords.size})"
                        } else {
                            "Learn"
                        },
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
                // The search field is the first thing on this screen, so what the keyboard
                // covers is the *results* of typing — the reason the field is there. The app
                // runs edge to edge, so the window does not resize for the IME; without
                // this the list stays full height behind the keyboard.
                .imePadding()
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
                    label = { Text("Search") },
                    placeholder = { Text("Hanzi, pinyin or English", color = TextSubtle) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentPrimary) },
                    singleLine = true,
                    // Search key on a live-filtered list. The action key used to do nothing
                    // at all, so on a phone the only way to get the keyboard out of the
                    // way was to press back.
                    keyboardOptions = KeyboardOptions(
                        // No autocorrect: a search box is a query box. The keyboard's
                        // dictionary rewrites pinyin into English without being asked, and
                        // a query the app silently changed is a query the learner cannot
                        // trust to be what they typed.
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        // Dismiss, not "search". The list below is already filtered live
                        // from `searchQuery`, so there is no search to run — the results
                        // exist while the keyboard is still up. What the key *should* do is
                        // get the keyboard out of the way so the learner can see them, and
                        // it used to do nothing at all.
                        onSearch = { focusManager.clearFocus() }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = OutlineBorder,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        cursorColor = AccentPrimary,
                        focusedContainerColor = DarkSurfaceContainer,
                        unfocusedContainerColor = DarkSurfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_search_field")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Chips at 48dp with `selectable`, where they were 32dp `FilterChip`s.
                // A 32dp chip is a miss often enough to matter, and there is no announced
                // selected state on a bare filter either.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(Filter.entries) { filter ->
                        val selected = selectedFilter == filter
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) AccentPrimary else DarkSurfaceContainer,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) AccentPrimary else OutlineBorder
                            ),
                            modifier = Modifier
                                .selectable(
                                    selected = selected,
                                    onClick = { selectedFilter = filter },
                                    role = Role.Tab
                                )
                                // The 48dp half of the fix the comment below describes. The
                                // `selectable` half had landed and the height had not: the chip
                                // measured 12dp padding plus 13sp text, about 42dp, leaving a
                                // 6dp shortfall on the control a learner uses to partition their
                                // whole library. The import was here the whole time, unreferenced.
                                .minimumTouchTarget()
                                .testTag("library_filter_${filter.name.lowercase()}")
                        ) {
                            Text(
                                text = filter.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) AccentPrimaryInk else TextMuted,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
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
                        pronunciationService = viewModel.pronunciationService,
                        onClick = {
                            selectedWordForModal = item
                            onWordSelected(item)
                        },
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
                                // A loading state should look like one. A card that says
                                // "Loading your library..." and nothing else reads as a
                                // half-rendered screen, and the second line has to be blank to
                                // avoid saying something it does not know yet.
                                if (!wordsLoaded && libraryError == null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .padding(bottom = 14.dp),
                                        color = TextMuted,
                                        strokeWidth = 2.5.dp
                                    )
                                }
                                Text(
                                    // Four distinct situations, not three. The first was
                                    // previously shown to anyone whose query was still in flight,
                                    // which told a learner with a full library that it was empty.
                                    // The read *failed* case was worse and had nowhere to go: the
                                    // view model degrades an unreadable database to an empty list
                                    // because there is no error variant on a `List` flow, so a
                                    // learner whose words were intact and whose disk was full was
                                    // told their vocabulary was ready for its first word, and
                                    // invited to type it all in again.
                                    //
                                    // Written `error ?: when { … }` rather than as the first
                                    // `when` branch because a `by`-delegated property cannot be
                                    // smart-cast, and the compiler will say so.
                                    text = libraryError?.let { "Your vocabulary could not be read" }
                                        ?: when {
                                            !wordsLoaded -> "Loading your library..."
                                            allWords.isEmpty() -> "Your vocabulary library is ready for its first word."
                                            else -> "No words match these filters."
                                        },
                                    color = TextLight,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = libraryError
                                        ?: when {
                                            !wordsLoaded -> ""
                                            allWords.isEmpty() -> "Add your first Hanzi and start building your personal language library."
                                            else -> "Try another search term or clear a filter."
                                        },
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                                // The tab previously had no way forward: adding a word required
                                // leaving for Home. The empty library is the one place a
                                // creation action is the primary action, not a shortcut.
                                if (libraryError == null && wordsLoaded && allWords.isEmpty()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    PrimaryButton(
                                        text = "Add word",
                                        onClick = onNavigateToAddWord,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
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

    // The full reference entry, as a bottom sheet. See `WordDetailSheet` for why this is
    // not an `AlertDialog`.
    selectedWordForModal?.let { wordWithSrs ->
        WordDetailSheet(
            wordWithSrs = wordWithSrs,
            pronunciationService = viewModel.pronunciationService,
            onDismiss = { selectedWordForModal = null }
        )
    }

    wordPendingDeletion?.let { wordWithSrs ->
        AlertDialog(
            onDismissRequest = { wordPendingDeletion = null },
            title = { Text("Remove ${wordWithSrs.word.hanzi}?", color = TextLight, fontWeight = FontWeight.SemiBold) },
            text = {
                // "and its review history" was here, and it was the one false sentence in the app
                // that pointed the wrong way. `LearnerProgress` documents the opposite as a
                // deliberate decision: `review_log` points at the shared content rather than at
                // the enrolment, so it "is deliberately left alone, so a learner's history
                // survives them dropping a word", and its DAO is append-only by design. The
                // enrolment, its schedule and its place in the due queue do go.
                //
                // Wrong in the direction that matters: a learner removing a word who believed
                // the record was gone was told it was.
                Text(
                    "This removes the word, its schedule and its place in your review queue. " +
                        "Your past reviews of it are kept. This cannot be undone.",
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
                TextButton(onClick = { wordPendingDeletion = null }) { Text("Keep word", color = AccentPrimary) }
            },
            containerColor = DarkSurfaceCard,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun WordLibraryRow(
    wordWithSrs: WordWithSrs,
    pronunciationService: PronunciationService,
    onClick: () -> Unit,
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
                        color = AccentPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = DarkSurfaceElevated,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
                    ) {
                        Text(
                            text = "HSK ${wordWithSrs.word.hskLevel}",
                            // 11sp, the floor. It was 9sp — smaller than the meaning text
                            // below it, which is a classification badge about the least
                            // important thing on the row.
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
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

                // SRS Badge. Was a `when` over the raw stored `String` with an
                // `else -> SrsAgainDark`, which is the colour that means "you failed this word
                // again" — so an unrecognised state told the learner their word had lapsed.
                // `null` is now rendered as grey: a state this build does not know about is not
                // the same claim as a state that says the learner failed.
                val srsColor = srsStateColorOrNull(wordWithSrs.state) ?: TextMuted

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(srsColor)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    // The state name is the learner's word for it, not the storage token, and an
                    // interval of zero is not printed for a card that has never been scheduled.
                    val srs = wordWithSrs.srs
                    Text(
                        text = srsStateLabelOrNull(wordWithSrs.state) +
                            if (srs == null) {
                                " - no schedule yet"
                            } else {
                                " - every ${srs.intervalDays} " +
                                    plural(srs.intervalDays, "day", "days")
                            },
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            // Quick audio. Takes the service rather than an `onPlayAudio: () -> Unit`, because
            // the button needs the resulting *state* to know whether it is loading, playing, or
            // broken — and a bare callback carries none of that. Which is the whole defect:
            // the old signature could not have shown a spinner, so the first tap after launch
            // looked exactly like the tap after it.
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
                testTag = "library_row_audio"
            )

            IconTarget(
                onClick = onDelete,
                modifier = Modifier.testTag("library_row_delete")
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${wordWithSrs.word.hanzi} from your collection",
                    tint = TextMuted
                )
            }
        }
    }
}

/**
 * A word's full reference entry, as a bottom sheet rather than a dialog.
 *
 * This was an `AlertDialog`, which is a poor fit for three separate reasons:
 *
 *  1. **Height.** `AlertDialog` reserves its own title and button rows, so the body — the
 *     only part that varies in length — gets whatever is left. With the stroke-order guide
 *     expanded this view is taller than a 640dp phone, and the dialog clipped it with the
 *     *Close* button pinned over the content. The character being looked up, the stroke
 *     order, and the example sentence were the three things most likely to be off-screen.
 *  2. **Position.** A dialog centres the content. This view is a character study: a
 *     Tian-grid glyph wants a stable, known position, and centring it meant the glyph
 *     moved vertically as the user switched between the guide and practice modes.
 *  3. **Reach.** A bottom sheet puts its content in the lower two-thirds of the screen,
 *     which is where a thumb already is.
 *
 * Dismissal is by the sheet's own drag handle, tap-outside, or back press — and there is an
 * explicit close button as well, because gesture-only dismissal is unreachable for anyone
 * navigating by switch access or TalkBack without a direct-touch gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailSheet(
    wordWithSrs: WordWithSrs,
    pronunciationService: PronunciationService,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurfaceCard,
        contentColor = TextLight,
        scrimColor = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // The glyph and its reading sit above the fold, so the sheet answers "what is
            // this character" before the user has to scroll to ask.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = wordWithSrs.word.hanzi,
                        fontSize = 44.sp,
                        color = TextLight
                    )
                    Text(
                        text = wordWithSrs.word.pinyin,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentPrimary
                    )
                }
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
                    testTag = "detail_audio"
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconTarget(onClick = onDismiss, modifier = Modifier.testTag("detail_close")) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close word details",
                        tint = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Provenance, stated above the content rather than buried under it.
            //
            // This was a guess — the sheet labelled a word "Added by you" or "Library"
            // based on nothing, because `WordView` did not carry the `vocabulary.provenance`
            // column that `AddWordScreen` and the migrations have been writing all along.
            // The column is now projected, and the chip prints the recorded value. An
            // unrecognised value renders as "Unverified source" rather than being rounded
            // to the nearest known category, because the point of the field is that the
            // app says what it knows.
            val provenance = wordWithSrs.word.contentProvenance
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailChip(
                    text = when (provenance) {
                        StorageValues.ContentProvenance.CURATED -> "Curated"
                        StorageValues.ContentProvenance.AI_GENERATED -> "AI-generated"
                        StorageValues.ContentProvenance.IMPORTED,
                        StorageValues.ContentProvenance.LEGACY_IMPORT -> "Imported"
                        StorageValues.ContentProvenance.UNKNOWN, null -> "Source not recorded"
                    },
                    color = when (provenance) {
                        StorageValues.ContentProvenance.CURATED -> SrsGoodDark
                        StorageValues.ContentProvenance.AI_GENERATED -> SrsHardDark
                        else -> TextMuted
                    }
                )
                DetailChip(text = "HSK ${wordWithSrs.word.hskLevel}", color = TextMuted)
                if (wordWithSrs.word.isVerified) {
                    DetailChip(text = "Verified", color = SrsGoodDark)
                }
            }

            // Tone as a number and a contour, not only as an accented syllable. The mark
            // on a vowel shows a level; it cannot show the shape, and it is invisible on a
            // neutral tone. These come from the same `pinyin_syllables` row the syllable
            // was read from, so they cannot disagree with it.
            if (wordWithSrs.word.hasTone) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("Tone ")
                        append(wordWithSrs.word.toneNumber)
                        if (wordWithSrs.word.toneContour.isNotBlank()) {
                            append(" · ")
                            append(wordWithSrs.word.toneContour)
                        }
                        if (wordWithSrs.word.partOfSpeech.isNotBlank()) {
                            append(" · ")
                            append(wordWithSrs.word.partOfSpeech)
                        }
                        if (wordWithSrs.word.structure.isNotBlank()) {
                            append(" · ")
                            append(wordWithSrs.word.structure)
                            append(" structure")
                        }
                    },
                    fontSize = 11.sp,
                    color = TextSubtle
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            InteractiveStrokeSection(
                hanzi = wordWithSrs.word.hanzi,
                strokeBreakdown = wordWithSrs.word.strokeJson
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceContainer, RoundedCornerShape(16.dp))
                    .border(1.dp, OutlineBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text("Meaning", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextMuted)
                Text(wordWithSrs.word.meaning, fontSize = 15.sp, color = TextLight)

                if (wordWithSrs.word.radical.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Radical", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextMuted)
                    Text(wordWithSrs.word.radical, fontSize = 15.sp, color = AccentPrimary)
                }

                if (wordWithSrs.word.exampleCn.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Example sentence",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                        // `forSentenceOfWord` so this control and the header's control — both
                        // enabled, both on this word — cannot both claim to be playing.
                        PronunciationButton(
                            service = pronunciationService,
                            request = remember(wordWithSrs.word.id) {
                                PronunciationRequest.forSentenceOfWord(
                                    sourceId = wordWithSrs.word.id,
                                    sentence = wordWithSrs.word.exampleCn
                                )
                            },
                            contentDescription = "Hear the example sentence",
                            testTag = "detail_sentence_audio"
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(wordWithSrs.word.exampleCn, fontSize = 15.sp, fontWeight = FontWeight.Normal, color = TextLight)
                    if (wordWithSrs.word.examplePy.isNotBlank()) {
                        Text(wordWithSrs.word.examplePy, fontSize = 12.sp, color = AccentPrimary)
                    }
                    if (wordWithSrs.word.exampleEn.isNotBlank()) {
                        Text(wordWithSrs.word.exampleEn, fontSize = 12.sp, color = TextMuted)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Spaced-repetition state, with the numbers that produced it. An SRS state
            // label on its own ("In progress") is an assertion; the interval and the review
            // count are the evidence for it.
            //
            // `srs` is null for a word that has never been scheduled, and that is a different
            // fact from an interval of zero - there is no schedule, rather than a schedule
            // that happens to be immediate. The `?: 0` that used to be here stated a number
            // the database does not contain, on the same line as the review count.
            val srs = wordWithSrs.srs
            Text(
                text = buildString {
                    append("Review state: ")
                    append(srsStateLabelOrNull(wordWithSrs.state) ?: "Unknown")
                    if (srs == null) {
                        append(" - not scheduled yet")
                    } else {
                        append(" - reviewed ")
                        append(srs.repetitions)
                        append(' ')
                        append(plural(srs.repetitions, "time", "times"))
                        append(", next in ")
                        append(srs.intervalDays)
                        append(' ')
                        append(plural(srs.intervalDays, "day", "days"))
                    }
                },
                fontSize = 11.sp,
                color = TextSubtle
            )
        }
    }
}

/** A small pill of metadata, used only inside the detail sheet. */
@Composable
private fun DetailChip(text: String, color: Color) {
    Surface(
        color = DarkSurfaceElevated,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
