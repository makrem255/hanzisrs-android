package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.PronunciationButton
import com.example.audio.PronunciationButtonVariant
import com.example.audio.PronunciationRequest
import com.example.audio.PronunciationService
import com.example.data.ai.GeneratedWordData
import com.example.data.ai.WordDataOrigin
import com.example.ui.components.InteractiveStrokeSection
import com.example.ui.components.minimumTouchTarget
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsGoodContainer
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.AiGenerationState
import com.example.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddWordScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val wordSaveError by viewModel.wordSaveError.collectAsStateWithLifecycle()
    val isSavingWord by viewModel.isSavingWord.collectAsStateWithLifecycle()
    // Saveable so a rotation mid-form keeps what has been typed. The generated draft lives
    // in the view model and survives already; the edits the learner made to it did not,
    // which is the worst possible split: the expensive work was kept and the cheap work lost.
    var searchInput by rememberSaveable { mutableStateOf("") }

    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentPrimary,
        unfocusedBorderColor = OutlineBorder,
        focusedTextColor = TextLight,
        unfocusedTextColor = TextLight,
        focusedLabelColor = AccentPrimary,
        unfocusedLabelColor = TextMuted,
        cursorColor = AccentPrimary,
        focusedContainerColor = DarkSurfaceContainer,
        unfocusedContainerColor = DarkSurfaceContainer
    )

    // Back from the review step used to silently discard the draft on the first tap and
    // navigate on the second, with the icon unchanged between them. Losing an AI
    // generation and a set of corrections to a mis-tap with no confirmation is the kind of
    // thing a learner stops trusting an app over, so the destructive direction now asks.
    var confirmDiscard by remember { mutableStateOf(false) }

    // An utterance started here must not follow the learner onto the next screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.pronunciationService.stop() }
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
                        text = if (aiState is AiGenerationState.ReadyForReview) "Review & approve" else "Add word",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (aiState is AiGenerationState.ReadyForReview) {
                                confirmDiscard = true
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.testTag("add_word_back")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (aiState is AiGenerationState.ReadyForReview) {
                                "Discard this word"
                            } else {
                                "Back"
                            },
                            tint = AccentPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        // imePadding: the app runs edge to edge, so the window does not resize for the
        // keyboard and nothing is inset above it. The scroll alone is not enough — the
        // "Approve & Add to SRS Deck" button is the last thing on a long form, and it is
        // exactly what the keyboard was covering. With this the form's scrollable height
        // ends above the IME, so the primary action can always be scrolled into view.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
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
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentPrimary)
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
                                // Both examples were refused by the validator this field feeds:
                                // 咖啡 is two characters and `Validator.validateNewWord` allows one,
                                // and péngyou is two syllables where `PinyinAnalyzer` yields one.
                                // A library entry is a single character, because a character is the
                                // unit the stroke and writing-practice screens operate on.
                                text = "Enter one Hanzi character (e.g. 学, 茶) or its pinyin " +
                                    "(e.g. xue, cha). AI will auto-generate pinyin with tones, " +
                                    "radical, stroke order breakdown, and context sentences.",
                                fontSize = 12.sp,
                                color = TextMuted,
                                lineHeight = 17.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = searchInput,
                                onValueChange = { searchInput = it },
                                label = { Text("Chinese hanzi or pinyin") },
                                placeholder = { Text("e.g. 茶 or chá", color = TextSubtle) },
                                singleLine = true,
                                // Search, and it runs. The action key used to do nothing at
                                // all, so on a phone the only way to generate was to dismiss
                                // the keyboard and then find the button it had been covering.
                                keyboardActions = KeyboardActions(
                                    // Guarded exactly as the button below is. The search key
                                    // bypassed that guard, so it stayed live while a request was
                                    // already in flight - which, over the service's 30 second
                                    // timeouts, is a long time to have two generations running.
                                    onSearch = {
                                        if (searchInput.isNotBlank() && state !is AiGenerationState.Loading) {
                                            viewModel.generateWord(searchInput)
                                        }
                                    }
                                ),
                                keyboardOptions = KeyboardOptions(
                                    // No autocorrect on a pinyin query: the keyboard's
                                    // dictionary will confidently "fix" `pengyou` into
                                    // English, and the field's own validation is the only
                                    // thing that should be deciding what a query is.
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Search
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("word_search_input"),
                                shape = RoundedCornerShape(14.dp),
                                colors = customTextFieldColors
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { viewModel.generateWord(searchInput) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("generate_word_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentPrimary,
                                    contentColor = AccentPrimaryInk
                                ),
                                shape = RoundedCornerShape(24.dp),
                                enabled = searchInput.isNotBlank() && state !is AiGenerationState.Loading
                            ) {
                                if (state is AiGenerationState.Loading) {
                                    CircularProgressIndicator(
                                        color = AccentPrimaryInk,
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Generating SRS Learning Data...", color = AccentPrimaryInk)
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
                        pronunciationService = viewModel.pronunciationService,
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
                        onCancel = { confirmDiscard = true }
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this word?", color = TextLight, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "The details you generated and any edits you made will not be saved.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.resetAiState()
                        onNavigateBack()
                    },
                    modifier = Modifier.testTag("confirm_discard_word")
                ) {
                    Text("Discard", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text("Keep editing", color = AccentPrimary)
                }
            },
            containerColor = DarkSurfaceCard,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

// The HSK level chips below are a `FlowRow`, which is still experimental layout API. The
// opt-in has to be on *this* function, not on the screen that calls it: an `@OptIn` does not
// propagate into a composable's body, so the one on `AddWordScreen` was leaving the use at
// the chips unacknowledged.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordReviewAndApprovalView(
    generatedData: GeneratedWordData,
    customTextFieldColors: androidx.compose.material3.TextFieldColors,
    pronunciationService: PronunciationService,
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
    onCancel: () -> Unit
) {
    // Every field here is `rememberSaveable` and keyed on nothing but the draft.
    //
    // They were all `remember`, so a rotation mid-review discarded the learner's edits
    // while the view model went on holding the generated draft they had started from. The
    // result was a silent revert: the screen looked the same, the text was gone, and
    // nothing said so. The AI generation is a network round trip; losing the corrections
    // made to it is the part that actually costs the learner their time.
    var hanzi by rememberSaveable(generatedData.hanzi) { mutableStateOf(generatedData.hanzi) }
    var pinyin by rememberSaveable(generatedData.pinyin) { mutableStateOf(generatedData.pinyin) }
    var meaning by rememberSaveable(generatedData.meaning) { mutableStateOf(generatedData.meaning) }
    var hskLevel by rememberSaveable(generatedData.hskLevel) { mutableIntStateOf(generatedData.hskLevel) }
    var radical by rememberSaveable(generatedData.radical) { mutableStateOf(generatedData.radical) }
    var exampleCn by rememberSaveable(generatedData.exampleCn) { mutableStateOf(generatedData.exampleCn) }
    var examplePy by rememberSaveable(generatedData.examplePy) { mutableStateOf(generatedData.examplePy) }
    var exampleEn by rememberSaveable(generatedData.exampleEn) { mutableStateOf(generatedData.exampleEn) }
    var strokeBreakdown by rememberSaveable(generatedData.strokeBreakdown) {
        mutableStateOf(generatedData.strokeBreakdown)
    }

    // This form is ten fields long. The keyboard's action key is the only way to move
    // between them without putting the keyboard away and reaching for the next control,
    // so every field declares an action and every one of them is wired. A key that renders
    // the right glyph and then does nothing is worse than no action key, because it
    // teaches the learner that the key is not worth pressing.
    val focusManager = LocalFocusManager.current

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
                Icon(Icons.Default.Edit, contentDescription = null, tint = AccentPrimary)
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
                    Text("Character details", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextLight)
                    // `Variant.Tonal` here, where the other three screens use the plain icon:
                    // this is the one place a learner goes deliberately to hear what they just
                    // typed, so the control earns prominence rather than recedes into the row.
                    //
                    // The request is keyed on the text, so backspacing the field drops any state
                    // the button was showing. With a fixed id the spinner would outlive the
                    // character it was about.
                    PronunciationButton(
                        service = pronunciationService,
                        request = remember(hanzi, pinyin) {
                            PronunciationRequest.forDraftCharacter(
                                draftId = "add_word_hanzi",
                                hanzi = hanzi,
                                pinyin = pinyin
                            )
                        },
                        // Names the character being played, not the button. "Listen" is what the
                        // button does; on a form where the character is editable and may be
                        // mid-edit, a screen reader user needs to know *which* character.
                        contentDescription = "Hear $hanzi pronounced",
                        variant = PronunciationButtonVariant.Tonal,
                        testTag = "addword_hanzi_audio"
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Side by side only when there is room for it.
                //
                // Two fields at 1f/1.5f in a Row is a desktop habit: on a 360dp phone this
                // row is ~326dp wide, so the Hanzi field gets ~126dp and the pinyin field
                // ~189dp, and both labels ("Hanzi", "Pinyin (with tones)") ellipsize. Below
                // the breakpoint they stack, which costs a little vertical space and buys
                // labels that can be read and fields that can hold a full pinyin.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val sideBySide = maxWidth >= 380.dp
                    val fieldColors = customTextFieldColors

                    if (sideBySide) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = hanzi,
                                onValueChange = { hanzi = it },
                                label = { Text("Hanzi") },
                                singleLine = true,
                                // Next, and it actually goes next — Hanzi, then pinyin, then
                                // the meaning, all reachable from the keyboard. Without the
                                // action the key renders the right glyph and does nothing,
                                // which on a phone means reaching for the keyboard's own
                                // "next field" control instead.
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Next) }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("edit_hanzi_input"),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors
                            )
                            OutlinedTextField(
                                value = pinyin,
                                onValueChange = { pinyin = it },
                                label = { Text("Pinyin with tones") },
                                singleLine = true,
                                // No autocorrect: a keyboard dictionary rewrites `chá` as an
                                // English word suggestion, and pinyin is not one.
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Next) }
                                ),
                                modifier = Modifier
                                    .weight(1.5f)
                                    .testTag("edit_pinyin_input"),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = hanzi,
                                onValueChange = { hanzi = it },
                                label = { Text("Hanzi") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Next) }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("edit_hanzi_input"),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors
                            )
                            OutlinedTextField(
                                value = pinyin,
                                onValueChange = { pinyin = it },
                                label = { Text("Pinyin with tones") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Next) }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("edit_pinyin_input"),
                                shape = RoundedCornerShape(12.dp),
                                colors = fieldColors
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = meaning,
                    onValueChange = { meaning = it },
                    label = { Text("English Meaning") },
                    // A definition is prose, so this field is deliberately the one that can
                    // take more than one line. The action key still moves on rather than
                    // inserting a newline, because on a phone the key's label is the only
                    // indication of what it will do and "Next" is the useful one.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
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
                // FlowRow, because six chips in a fixed Row do not fit a phone.
                //
                // Measured: the six chips want ~354dp and a 360dp phone has ~326dp available
                // inside the screen and card padding. Compose neither clips nor scrolls a Row
                // of fixed-width children, so it squeezed each label into an ellipsis and
                // the learner could not see which level was selected. The chips wrap instead,
                // and each one is a full 48dp `selectable` rather than a 32dp chip.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (1..6).forEach { level ->
                        val selected = hskLevel == level
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) AccentPrimary else DarkSurfaceContainer,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (selected) AccentPrimary else OutlineBorder
                            ),
                            modifier = Modifier
                                .minimumTouchTarget()
                                .selectable(
                                    selected = selected,
                                    onClick = { hskLevel = level },
                                    role = Role.RadioButton
                                )
                                .testTag("hsk_chip_$level")
                        ) {
                            Text(
                                text = "HSK $level",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (selected) AccentPrimaryInk else TextMuted,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = radical,
                    onValueChange = { radical = it },
                    label = { Text("Radical (部首)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
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
                    Text("Contextual example sentence", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextLight)
                    // The blank case is handled by the button: an empty field yields
                    // `NothingToSay`, which disables the control without a caption, because
                    // the empty field beside it is the explanation. The old code got there by
                    // greying the icon and had no way to say *why* on a device with no voice.
                    PronunciationButton(
                        service = pronunciationService,
                        request = remember(exampleCn) {
                            PronunciationRequest.forDraftSentence(
                                draftId = "add_word_sentence",
                                sentence = exampleCn
                            )
                        },
                        contentDescription = "Hear the example sentence",
                        variant = PronunciationButtonVariant.Tonal,
                        testTag = "addword_sentence_audio"
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = exampleCn,
                    onValueChange = { exampleCn = it },
                    label = { Text("Sentence (Chinese)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = examplePy,
                    onValueChange = { examplePy = it },
                    label = { Text("Sentence (pinyin)") },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = exampleEn,
                    onValueChange = { exampleEn = it },
                    label = { Text("Sentence (English)") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = customTextFieldColors
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = strokeBreakdown,
                    onValueChange = { strokeBreakdown = it },
                    label = { Text("Stroke names, in order") },
                    supportingText = {
                        Text(
                            "Comma separated, e.g. 点 (Diǎn), 丿 (Piě), 一 (Héng). " +
                                "Leave it blank if you do not know — the review card will say " +
                                "so rather than guess.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    },
                    // Done, and the keyboard gets out of the way so the preview above and the
                    // approve button below are both reachable without a scroll. This is the
                    // last field on the form, which is what makes Done the honest label here.
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
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
                        containerColor = AccentPrimary,
                        contentColor = AccentPrimaryInk
                    ),
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = AccentPrimaryInk,
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
