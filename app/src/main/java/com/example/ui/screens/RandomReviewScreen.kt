package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.PronunciationButton
import com.example.audio.PronunciationRequest
import com.example.audio.RecognitionFailure
import com.example.audio.RecognitionState
import com.example.audio.RecognitionVerdict
import com.example.audio.SpeechRecognitionManager
import com.example.audio.UiSound
import com.example.audio.verdictFor
import com.example.data.review.RandomWordChoice
import com.example.data.review.RandomWordPool
import com.example.ui.components.AppCard
import com.example.ui.components.EmptyState
import com.example.ui.components.ErrorState
import com.example.ui.components.HSpace
import com.example.ui.components.Haptics
import com.example.ui.components.InlineNotice
import com.example.ui.components.LoadingState
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentAmberContainer
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentMint
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.Dimens
import com.example.ui.theme.HanziHero
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.PinyinLarge
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.RandomReviewUiState

/**
 * Open practice: a word the learner already chose to study, asked out loud, with nothing
 * written to the scheduler.
 *
 * ## What this screen deliberately does not do
 *
 * It does not grade. Nothing here reaches [MainViewModel.submitRating], `srs_state`,
 * `review_log` or a due date. A learner who spends ten minutes here has changed nothing about
 * their study plan, which is the whole point of a mode that exists to sit alongside the graded
 * deck rather than to replace it - recording a review for a word nobody was asked to rate would
 * quietly move the schedule of words that were.
 *
 * ## What the microphone actually establishes
 *
 * [SpeechRecognitionManager] performs speech-to-text. When the recogniser returns the word, the
 * screen says the word was recognised, and says nothing else: it does not claim the tones were
 * right, and it prints the hypothesis it matched against so the learner can see exactly what
 * comparison was made. A score would be this app's invention presented as a measurement, and
 * this screen's whole job is to be trustworthy about what it knows.
 *
 * The microphone is an *addition*, never a gate. Every path through - no permission, no
 * recogniser on the device, no network, no speech - reaches the same reveal, and the answer is
 * never behind a control that might not work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomReviewScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.randomReviewState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val haptics = rememberHaptics()

    // Screen-scoped on purpose: the recogniser binds a service and holds a microphone, and the
    // composition is the one place where "nobody is looking at this any more" is unambiguous.
    val speech = remember(context) { SpeechRecognitionManager(context.applicationContext) }
    val recognition by speech.state.collectAsState()

    var permissionNotice by remember { mutableStateOf<String?>(null) }
    var showMicRationale by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionNotice = null
            showMicRationale = false
            speech.startListening()
        } else {
            // The system's own rationale flag is the only honest way to tell "denied once, ask
            // again" apart from "denied for good". Guessing would send a learner hunting through
            // Settings for a permission they can still simply re-allow from here.
            val permanently = activity
                ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            permissionNotice = if (permanently) {
                "Microphone access is switched off for this app. You can turn it on in your " +
                    "phone's Settings - or reveal the answer and practise silently."
            } else {
                "We won't be able to hear you, so we can't check what you said. You can still " +
                    "reveal the answer and practise silently."
            }
        }
    }

    fun tryListening() {
        permissionNotice = null
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        when {
            granted -> speech.startListening()
            activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.RECORD_AUDIO
            ) == true -> showMicRationale = true
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(speech) {
        onDispose {
            // Leaving mid-listen must not leave a service bound or a microphone held by a screen
            // nobody can see, and audio started here must not follow the learner out of the app.
            speech.destroy()
            viewModel.pronunciationService.stop()
        }
    }

    LaunchedEffect(Unit) { viewModel.startRandomReview() }

    val showing = state as? RandomReviewUiState.Showing
    val wordId = showing?.choice?.word?.word?.id

    // One sound for the sitting opening and one for each word after it. Keyed on the id rather
    // than on the state object, so revealing a word cannot be mistaken for arriving at one.
    var sittingStarted by remember { mutableStateOf(false) }
    LaunchedEffect(wordId) {
        if (wordId != null) {
            if (sittingStarted) {
                viewModel.playSound(UiSound.NewWord)
            } else {
                sittingStarted = true
                viewModel.playSound(UiSound.Start)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // Zero insets because the outer Scaffold in MainActivity already padded the whole
                // NavHost by the status bar; TopAppBarDefaults.windowInsets would apply it again.
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text(
                            text = "Random Review",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextLight
                        )
                        if (showing != null) {
                            Text(
                                text = "Word ${showing.wordsShown} · practice only, nothing scheduled",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenH, vertical = Dimens.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val current = state) {
                is RandomReviewUiState.Loading -> LoadingState(label = "Finding a word…")

                is RandomReviewUiState.NoVocabulary -> EmptyState(
                    icon = Icons.Default.School,
                    title = "Nothing to practise yet",
                    message = "Random Review only ever asks about words you chose to learn, " +
                        "so add one to your library first.",
                    actionLabel = "Go back",
                    onAction = onNavigateBack
                )

                is RandomReviewUiState.Failed -> {
                    // No retry: the read failed and there is no way to re-issue it from here, so
                    // offering "Try again" would be a button that does nothing.
                    ErrorState(message = current.message)
                    Spacer(Modifier.height(Dimens.lg))
                    SecondaryButton(text = "Go back", onClick = onNavigateBack)
                }

                is RandomReviewUiState.Showing -> RandomReviewSession(
                    showing = current,
                    recognition = recognition,
                    permissionNotice = permissionNotice,
                    showMicRationale = showMicRationale,
                    viewModel = viewModel,
                    haptics = haptics,
                    onListen = ::tryListening,
                    onConfirmRationale = {
                        showMicRationale = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onDismissRationale = { showMicRationale = false },
                    onReveal = {
                        haptics.lightTick()
                        viewModel.revealRandomWord()
                    },
                    onNext = {
                        haptics.lightTick()
                        viewModel.nextRandomWord()
                    }
                )
            }
        }
    }
}

/**
 * The word, the attempt, and the controls - everything that changes when the word does.
 *
 * Split from [RandomReviewScreen] so the permission plumbing, the scaffold and the four top-level
 * states do not have to be threaded through every line of card content, and so the word itself
 * can be wrapped in one [AnimatedContent] without the app bar flickering along with it.
 */
@Composable
private fun RandomReviewSession(
    showing: RandomReviewUiState.Showing,
    recognition: RecognitionState,
    permissionNotice: String?,
    showMicRationale: Boolean,
    viewModel: MainViewModel,
    haptics: Haptics,
    onListen: () -> Unit,
    onConfirmRationale: () -> Unit,
    onDismissRationale: () -> Unit,
    onReveal: () -> Unit,
    onNext: () -> Unit
) {
    val entry = showing.choice
    val word = entry.word.word
    val revealed = showing.revealed

    val hypothesis = (recognition as? RecognitionState.Heard)?.hypothesis
    val verdict = verdictFor(target = word.hanzi, hypothesis = hypothesis)
    val listening = recognition is RecognitionState.Listening

    // The one moment in this screen that earns a firm haptic. It fires on the *transition* into
    // "recognised" rather than on every hypothesis, so a stream of partial results cannot
    // machine-gun the chime - and it says nothing about pronunciation, only about the match.
    LaunchedEffect(verdict) {
        if (verdict == RecognitionVerdict.Recognised) {
            haptics.confirm()
            viewModel.playSound(UiSound.Success)
        }
    }

    if (entry.pool == RandomWordPool.AllEnrolled) {
        InlineNotice(
            text = "Nothing has been graded yet, so these come from your whole library rather " +
                "than from words you've already studied.",
            icon = Icons.Default.Info
        )
        Spacer(Modifier.height(Dimens.md))
    }

    permissionNotice?.let { message ->
        InlineNotice(text = message, tone = AccentAmber, icon = Icons.Default.MicOff)
        Spacer(Modifier.height(Dimens.md))
    }

    if (showMicRationale) {
        AppCard(
            containerColor = AccentAmberContainer,
            borderColor = AccentAmber.copy(alpha = 0.4f)
        ) {
            Text(
                text = "To check what you say, we need to use your microphone. The words are " +
                    "compared on this device - nothing is kept, scored or uploaded.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSubtle
            )
            Spacer(Modifier.height(Dimens.md))
            Row(modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    text = "Not now",
                    onClick = onDismissRationale,
                    modifier = Modifier.weight(1f)
                )
                HSpace(Dimens.md)
                PrimaryButton(
                    text = "Continue",
                    onClick = onConfirmRationale,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(Dimens.md))
    }

    // The card is the only thing keyed on the word, so the app bar, the notices and the controls
    // below stay put while it changes - a screen that slides entirely when a word does reads as
    // having navigated somewhere rather than as having turned over.
    AnimatedContent(
        targetState = entry,
        transitionSpec = {
            if (initialState.word.word.id == targetState.word.word.id) {
                // Same word: only `revealed` moved, and the reveal is animated inside the card by
                // its own staggered [RevealItem]s. Cross-fading the card here would re-run them.
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(tween(300)) +
                    slideInVertically(tween(360)) { it / 8 } +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(360))) togetherWith
                    (fadeOut(tween(180)) +
                        slideOutVertically(tween(220)) { -it / 10 } +
                        scaleOut(targetScale = 0.95f, animationSpec = tween(220)))
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { current ->
        WordCard(
            entry = current,
            revealed = revealed,
            haptics = haptics
        )
    }

    Spacer(Modifier.height(Dimens.xl))

    val status = statusText(
        recognition = recognition,
        hypothesis = hypothesis,
        verdict = verdict,
        revealed = revealed
    )
    if (status.isNotBlank()) {
        if (verdict == RecognitionVerdict.Recognised) {
            RecognisedBadge(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Dimens.md))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(verdict, recognition),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Dimens.lg))
    }

    if (!revealed) {
        AnimatedMicButton(
            onClick = onListen,
            listening = listening,
            enabled = !listening,
            icon = Icons.Default.Mic,
            contentDescription = "Say the word out loud"
        )
        Spacer(Modifier.height(Dimens.lg))
        SecondaryButton(
            text = if (listening) "Listening…" else "Reveal the answer",
            onClick = onReveal,
            modifier = Modifier.fillMaxWidth(0.85f),
            enabled = !listening
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PronunciationButton(
                service = viewModel.pronunciationService,
                request = PronunciationRequest.forWord(
                    sourceId = word.id,
                    hanzi = word.hanzi,
                    pinyin = word.pinyin,
                    toneNumber = word.toneNumber
                ),
                contentDescription = "Play ${word.hanzi}"
            )
            if (word.exampleCn.isNotBlank()) {
                Spacer(Modifier.width(Dimens.md))
                PronunciationButton(
                    service = viewModel.pronunciationService,
                    request = PronunciationRequest.forSentenceOfWord(
                        sourceId = word.id,
                        sentence = word.exampleCn
                    ),
                    contentDescription = "Play the example sentence"
                )
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                text = "Say it again",
                onClick = onListen,
                modifier = Modifier.fillMaxWidth(0.5f),
                accent = AccentCyan
            )
        }
        Spacer(Modifier.height(Dimens.lg))
        PrimaryButton(text = "Next word", onClick = onNext)
    }
}

/**
 * The word itself: character first, answer second, the answer withheld until it is asked for.
 *
 * The character is set in [HanziHero] - the design system's own focal size - rather than at a
 * literal, so it sits on the same scale as the review deck and keeps whatever font scaling the
 * learner has set on their phone. Everything under the rule is inside a [RevealItem], so the
 * pinyin lands, then the meaning, then the example, rather than all four arriving at once.
 */
@Composable
private fun WordCard(
    entry: RandomWordChoice,
    revealed: Boolean,
    haptics: Haptics
) {
    val word = entry.word.word

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = DarkSurfaceCard,
        borderColor = OutlineBorder,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Dimens.xl,
            vertical = Dimens.xxl
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (revealed) "ANSWER" else "SAY IT OUT LOUD",
                style = MaterialTheme.typography.labelSmall,
                color = if (revealed) AccentMint else AccentCyan
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (word.hskLevel > 0) "HSK ${word.hskLevel}" else word.radical,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }

        Spacer(Modifier.height(Dimens.xl))

        Text(
            text = word.hanzi,
            style = HanziHero,
            color = TextLight,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.sm)
        )

        Spacer(Modifier.height(Dimens.lg))

        RevealItem(visible = revealed, order = 0) {
            AnswerLine(
                label = "PINYIN",
                value = word.pinyin.ifBlank { "—" },
                valueStyle = PinyinLarge,
                valueColor = AccentCyan
            )
            Spacer(Modifier.height(Dimens.md))
        }

        RevealItem(visible = revealed, order = 1) {
            AnswerLine(label = "MEANING", value = word.meaning)
            Spacer(Modifier.height(Dimens.md))
        }

        RevealItem(visible = revealed, order = 2) {
            if (word.exampleCn.isNotBlank()) {
                AnswerLine(
                    label = "EXAMPLE",
                    value = buildString {
                        append(word.exampleCn)
                        if (word.exampleEn.isNotBlank()) {
                            append("\n")
                            append(word.exampleEn)
                        }
                    },
                    valueStyle = MaterialTheme.typography.bodyMedium,
                    valueColor = TextSubtle
                )
            } else {
                Text(
                    text = "No example sentence for this word yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }

        if (!revealed) {
            Spacer(Modifier.height(Dimens.md))
            Text(
                text = "The meaning appears once you've tried it.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The one line shown under the card, and what colour it is.
 *
 * Three honest answers rather than a score: the recogniser produced this word, it produced
 * something else, or it produced nothing. The caveat about what recognition does *not* establish
 * travels with the success case, because a green tick and no qualification is how a learner ends
 * up believing their tones were graded.
 */
private fun statusText(
    recognition: RecognitionState,
    hypothesis: String?,
    verdict: RecognitionVerdict,
    revealed: Boolean
): String = when {
    recognition is RecognitionState.Listening -> "Listening…"
    recognition is RecognitionState.Processing -> "Finishing…"
    verdict == RecognitionVerdict.Recognised ->
        "We heard “$hypothesis” — that's this word. Recognition confirms the words, not your " +
            "tones."
    verdict == RecognitionVerdict.Different -> "We heard “$hypothesis”, not this word."
    recognition is RecognitionState.Failed -> failureText(recognition.failure)
    revealed -> ""
    else -> "Say the word out loud, then reveal the answer."
}

private fun statusColor(
    verdict: RecognitionVerdict,
    recognition: RecognitionState
): androidx.compose.ui.graphics.Color = when {
    verdict == RecognitionVerdict.Recognised -> AccentMint
    verdict == RecognitionVerdict.Different -> AccentAmber
    recognition is RecognitionState.Failed -> AccentAmber
    else -> TextMuted
}

/**
 * Turns a recogniser failure into a sentence a learner can act on.
 *
 * Each branch is a different problem with a different fix, and collapsing them into "couldn't
 * hear you" would blame the learner for a missing service, a dead network or a permission they
 * were never asked for. [RecognitionFailure.Cancelled] is empty: it means the learner left, and
 * there is nobody left to read it.
 */
internal fun failureText(failure: RecognitionFailure): String = when (failure) {
    RecognitionFailure.PermissionDenied ->
        "The microphone isn't available, so we can't check what you said."
    RecognitionFailure.NotSupported ->
        "This phone can't listen for speech, so we can't check what you said."
    RecognitionFailure.LanguageUnavailable ->
        "This phone doesn't have Mandarin speech recognition, so we can't check what you said."
    RecognitionFailure.NetworkRequired ->
        "Speech recognition needs a connection right now. Reveal the answer instead."
    RecognitionFailure.MicrophoneBusy ->
        "The microphone is busy. Try again in a moment."
    RecognitionFailure.NoSpeech ->
        "We didn't catch that. Try again, a little louder."
    RecognitionFailure.Cancelled -> ""
    RecognitionFailure.Unknown ->
        "We couldn't listen just now. Reveal the answer if you'd rather not try again."
}

