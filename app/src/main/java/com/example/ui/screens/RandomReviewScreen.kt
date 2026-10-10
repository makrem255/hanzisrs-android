package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.example.data.review.RANDOM_REVIEW_MAX_ATTEMPTS
import com.example.data.review.RANDOM_REVIEW_SUCCESS_HOLD_MS
import com.example.data.review.SELECTION_FULL_MS
import com.example.data.review.SELECTION_FULL_STEPS
import com.example.data.review.SELECTION_SHORT_MS
import com.example.data.review.SELECTION_SHORT_STEPS
import com.example.data.review.countsAsAttempt
import com.example.data.review.helpRevealed
import com.example.data.review.meetsReviewMinimum
import com.example.data.review.selectionPool
import com.example.ui.components.AppCard
import com.example.ui.components.ErrorState
import com.example.ui.components.HSpace
import com.example.ui.components.InlineNotice
import com.example.ui.components.LoadingState
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentAmberContainer
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentMint
import com.example.ui.theme.AccentRed
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
import kotlinx.coroutines.delay

/**
 * Random Review, rebuilt as an arcade: doorway, spinning wheel, pronunciation duel.
 *
 * ## What this screen deliberately does not do
 *
 * It does not grade. Nothing here reaches [MainViewModel.submitRating], `srs_state`,
 * `review_log` or a due date - the wheel, the attempts and the help state are practice, and
 * recording a review for a word nobody was asked to rate would quietly move the schedule of
 * words that were.
 *
 * ## What the microphone actually establishes
 *
 * [SpeechRecognitionManager] performs speech-to-text, and the verdicts below compare that text
 * with the word on screen. A match says the recogniser heard the word; it says nothing about
 * tones, and the success copy says so out loud. There is deliberately no score anywhere in
 * this file: a number derived from speech-to-text would be this app's invention presented as a
 * measurement, and no genuine on-device Mandarin pronunciation-assessment service exists in
 * this app's architecture to source one from. This limitation is stated on screen (help
 * dialog, success copy) rather than hidden behind a percentage.
 *
 * ## Attempt integrity
 *
 * Only a completed utterance about the *current* word increments the counter. Technical
 * failures, silence and late results for a previous word show guidance but never count, and a
 * stale hypothesis can never paint the next word green: every consumption rechecks the word
 * id after its settle delay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomReviewScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAddWord: () -> Unit,
) {
    val state by viewModel.randomReviewState.collectAsStateWithLifecycle()
    val words by viewModel.userWords.collectAsStateWithLifecycle()
    val wordsLoaded by viewModel.wordsLoaded.collectAsStateWithLifecycle()
    val libraryError by viewModel.libraryError.collectAsStateWithLifecycle()
    val pending by viewModel.pendingSelection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val haptics = rememberHaptics()

    // Screen-scoped on purpose: the recogniser binds a service and holds a microphone, and the
    // composition is the one place where "nobody is looking at this any more" is unambiguous.
    val speech = remember(context) { SpeechRecognitionManager(context.applicationContext) }
    val recognition by speech.state.collectAsState()

    // Returning from the add-words detour must land back on the live word, not on a fresh
    // doorway sitting over it: the session outlives the composition, the phase does not.
    var phase by remember {
        mutableStateOf(
            if (viewModel.randomReviewState.value is RandomReviewUiState.Showing) {
                ReviewPhase.Word
            } else {
                ReviewPhase.Entry
            },
        )
    }
    var selectionFull by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf(AttemptFeedback.None) }
    var listenWordId by remember { mutableStateOf<Long?>(null) }
    var lastConsumed by remember { mutableStateOf<ConsumedHeard?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    var showMicRationale by remember { mutableStateOf(false) }

    val showing = state as? RandomReviewUiState.Showing
    val sessionWordId = showing?.choice?.word?.word?.id
    val attempts = showing?.attempts ?: 0
    val helpOpen = showing != null && helpRevealed(attempts) && showing.revealed

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionNotice = null
            showMicRationale = false
            listenWordId = sessionWordId
            speech.startListening()
        } else {
            // The system's own rationale flag is the only honest way to tell "denied once, ask
            // again" apart from "denied for good". Guessing would send a learner hunting through
            // Settings for a permission they can still simply re-allow from here.
            val permanently = activity
                ?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
            permissionNotice = if (permanently) {
                "Microphone access is switched off for this app. You can turn it on in your " +
                    "phone's Settings - or wait for the help state and practise silently."
            } else {
                "We won't be able to hear you, so we can't check what you said. You can still " +
                    "keep going - the help state opens after three tries either way."
            }
        }
    }

    fun tryListening() {
        if (phase != ReviewPhase.Word || feedback != AttemptFeedback.None) return
        permissionNotice = null
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        when {
            granted -> {
                listenWordId = sessionWordId
                speech.startListening()
            }

            activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.RECORD_AUDIO,
            ) == true -> showMicRationale = true

            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun exitToBack() {
        speech.cancel()
        viewModel.cancelSelection()
        viewModel.endRandomReview()
        onNavigateBack()
    }

    DisposableEffect(speech) {
        onDispose {
            // Leaving mid-anything must not leave a service bound, a microphone held, audio
            // started here following the learner out, or a pending draw that fires later. The
            // *session* is deliberately not ended here: the add-words detour disposes this
            // composition and must return to the live word.
            speech.destroy()
            viewModel.pronunciationService.stop()
            viewModel.cancelSelection()
        }
    }

    // A new word silences the old one: without this, a hypothesis arriving for the previous
    // word would meet the new word below and could paint it green for something it never heard.
    LaunchedEffect(sessionWordId) {
        speech.reset()
        feedback = AttemptFeedback.None
        lastConsumed = null
    }

    // Consuming what was heard. Debounced past the settle window so partial hypotheses -
    // which the engine posts while the learner is still talking - are never judged as
    // attempts. Everything is rechecked after the delay: the word, the phase, and that the
    // result is still the latest one.
    val heard = recognition as? RecognitionState.Heard
    val wordHanzi = showing?.choice?.word?.word?.hanzi
    LaunchedEffect(heard, sessionWordId, attempts, wordHanzi, phase, feedback, listenWordId) {
        val result = heard ?: return@LaunchedEffect
        if (phase != ReviewPhase.Word || feedback != AttemptFeedback.None) return@LaunchedEffect
        val targetId = sessionWordId ?: return@LaunchedEffect
        val target = wordHanzi ?: return@LaunchedEffect
        val key = ConsumedHeard(result.hypothesis, result.alternatives, attempts)
        if (lastConsumed == key) return@LaunchedEffect
        delay(SETTLE_MS)
        if (speech.state.value != result) return@LaunchedEffect
        val current = (viewModel.randomReviewState.value as? RandomReviewUiState.Showing)
            ?.choice?.word?.word
        if (current?.id != targetId) return@LaunchedEffect
        if (!countsAsAttempt(result.hypothesis, listenWordId ?: -1L, targetId)) {
            return@LaunchedEffect
        }
        lastConsumed = key
        when (verdictFor(target = target, hypothesis = result.hypothesis)) {
            RecognitionVerdict.Recognised -> {
                feedback = AttemptFeedback.Correct
                haptics.confirm()
                viewModel.playSound(UiSound.Success)
                speech.reset()
            }

            RecognitionVerdict.Different -> {
                viewModel.noteRandomAttempt()
                if (attempts + 1 >= RANDOM_REVIEW_MAX_ATTEMPTS) {
                    viewModel.revealRandomHelp()
                }
                feedback = AttemptFeedback.Wrong
                haptics.selection()
                speech.reset()
            }

            RecognitionVerdict.NothingHeard -> Unit
        }
    }

    // The wrong-answer flash: red long enough to read, then back to neutral for the retry.
    // Skipped once help is open - the help card has its own colours.
    LaunchedEffect(feedback, sessionWordId, helpOpen) {
        if (feedback == AttemptFeedback.Wrong && !helpOpen) {
            delay(WRONG_FLASH_MS)
            if (feedback == AttemptFeedback.Wrong) feedback = AttemptFeedback.None
        }
    }

    // The green hold, then the wheel again. Guarded on the word id so an exit or a rapid
    // navigation during the half second cannot advance a sitting nobody is looking at.
    LaunchedEffect(feedback, sessionWordId) {
        if (feedback == AttemptFeedback.Correct && sessionWordId != null) {
            delay(RANDOM_REVIEW_SUCCESS_HOLD_MS)
            val current = (viewModel.randomReviewState.value as? RandomReviewUiState.Showing)
                ?.choice?.word?.word?.id
            if (current == sessionWordId && feedback == AttemptFeedback.Correct) {
                feedback = AttemptFeedback.None
                phase = ReviewPhase.Selecting
                selectionFull = false
                viewModel.requestSelection()
            }
        }
    }

    BackHandler(enabled = phase != ReviewPhase.Entry && !showExitDialog) {
        showExitDialog = true
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
                            color = TextLight,
                        )
                        if (showing != null && phase == ReviewPhase.Word) {
                            Text(
                                text = "Word ${showing.wordsShown} · practice only, nothing scheduled",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (phase == ReviewPhase.Entry) onNavigateBack() else showExitDialog = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (phase != ReviewPhase.Entry) {
                        IconButton(
                            onClick = { showExitDialog = true },
                            modifier = Modifier.testTag("random_review_exit"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Exit Random Review",
                                tint = TextMuted,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg),
            )
        },
        containerColor = DarkBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .testTag("random_review_screen")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenH, vertical = Dimens.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                libraryError != null -> {
                    ErrorState(message = libraryError ?: "")
                    Spacer(Modifier.height(Dimens.lg))
                    SecondaryButton(text = "Go back", onClick = onNavigateBack)
                }

                phase == ReviewPhase.Entry -> RandomReviewEntry(
                    words = words,
                    wordsLoaded = wordsLoaded,
                    onStart = {
                        // Rechecked at press time, never trusted from composition: the library
                        // may have changed while this screen sat open.
                        if (!meetsReviewMinimum(words)) {
                            haptics.lightTick()
                            return@RandomReviewEntry
                        }
                        phase = ReviewPhase.Selecting
                        selectionFull = true
                        viewModel.requestSelection()
                        viewModel.playSound(UiSound.Start)
                    },
                    onAddWords = onNavigateToAddWord,
                )

                phase == ReviewPhase.Selecting -> {
                    val draw = pending
                    if (draw == null) {
                        LoadingState(label = "Choosing…")
                    } else {
                        // Frozen the moment the draw lands: deletions mid-spin cannot move the
                        // target or empty the preview under the animation.
                        val wheelPool = remember(draw) {
                            selectionPool(words).map { it.word.hanzi }.ifEmpty {
                                listOf(draw.word.word.hanzi)
                            }
                        }
                        val (totalMs, steps) = if (selectionFull) {
                            SELECTION_FULL_MS to SELECTION_FULL_STEPS
                        } else {
                            SELECTION_SHORT_MS to SELECTION_SHORT_STEPS
                        }
                        SelectionAnimation(
                            poolHanzis = wheelPool,
                            totalMs = totalMs,
                            steps = steps,
                            onTick = { viewModel.playSound(UiSound.Tick) },
                            onFinish = {
                                viewModel.confirmSelection()
                                viewModel.playSound(UiSound.NewWord)
                                phase = ReviewPhase.Word
                            },
                        )
                    }
                }

                else -> {
                    val current = showing
                    if (current == null) {
                        // The session went away under us (signed out elsewhere, process
                        // restored): the doorway is the only honest place to be.
                        phase = ReviewPhase.Entry
                    } else {
                        WordPhase(
                            showing = current,
                            helpOpen = helpOpen,
                            feedback = feedback,
                            recognition = recognition,
                            permissionNotice = permissionNotice,
                            showMicRationale = showMicRationale,
                            viewModel = viewModel,
                            onListen = ::tryListening,
                            onConfirmRationale = {
                                showMicRationale = false
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onDismissRationale = { showMicRationale = false },
                            onNext = {
                                haptics.lightTick()
                                feedback = AttemptFeedback.None
                                phase = ReviewPhase.Selecting
                                selectionFull = false
                                viewModel.requestSelection()
                            },
                        )
                    }
                }
            }
        }
    }

    if (showExitDialog) {
        RandomReviewExitDialog(
            onConfirmExit = {
                showExitDialog = false
                phase = ReviewPhase.Entry
                exitToBack()
            },
            onStay = { showExitDialog = false },
        )
    }
}

/** Which room of the arcade is on screen. Screen-local: the session outlives it, this does not. */
private enum class ReviewPhase {
    Entry,
    Selecting,
    Word,
}

/** The word's verdict colouring. Resets on every new word and every new attempt. */
private enum class AttemptFeedback {
    None,
    Correct,
    Wrong,
}

/** A consumed recogniser result, so the same hypothesis is never judged twice. */
private data class ConsumedHeard(
    val hypothesis: String,
    val alternatives: List<String>,
    val attempts: Int,
)

/** How long a settled hypothesis waits for a successor before being judged, in milliseconds. */
private const val SETTLE_MS = 1100L

/** How long the red wrong-answer flash holds before the retry state, in milliseconds. */
private const val WRONG_FLASH_MS = 600L

/**
 * The word, the attempt, and the controls - everything that changes when the word does.
 *
 * Two rooms, never mixed: the duel (hanzi only, mic, status) and the help state (answer open,
 * pronunciation playback, learner-paced next). The help state never auto-advances - the
 * learner chooses when they have heard enough and when to move on.
 */
@Composable
private fun WordPhase(
    showing: RandomReviewUiState.Showing,
    helpOpen: Boolean,
    feedback: AttemptFeedback,
    recognition: RecognitionState,
    permissionNotice: String?,
    showMicRationale: Boolean,
    viewModel: MainViewModel,
    onListen: () -> Unit,
    onConfirmRationale: () -> Unit,
    onDismissRationale: () -> Unit,
    onNext: () -> Unit,
) {
    val entry = showing.choice
    val word = entry.word.word
    val attempts = showing.attempts
    val listening = recognition is RecognitionState.Listening
    val processing = recognition is RecognitionState.Processing
    val hypothesis = (recognition as? RecognitionState.Heard)?.hypothesis
    val verdict = verdictFor(target = word.hanzi, hypothesis = hypothesis)

    if (entry.pool == com.example.data.review.RandomWordPool.AllEnrolled) {
        InlineNotice(
            text = "Nothing has been graded yet, so these come from your whole library rather " +
                "than from words you've already studied.",
            icon = Icons.Default.Info,
        )
        Spacer(Modifier.height(Dimens.md))
    }

    permissionNotice?.let { message ->
        InlineNotice(text = message, tone = AccentAmber, icon = Icons.Default.MicOff)
        Spacer(Modifier.height(Dimens.md))
    }

    if (showMicRationale) {
        MicRationaleCard(
            onConfirm = onConfirmRationale,
            onDismiss = onDismissRationale,
        )
        Spacer(Modifier.height(Dimens.md))
    }

    val hanziColor by animateColorAsState(
        targetValue = when {
            helpOpen -> TextLight
            feedback == AttemptFeedback.Correct -> AccentMint
            feedback == AttemptFeedback.Wrong -> AccentRed
            else -> TextLight
        },
        animationSpec = tween(180),
        label = "attemptTint",
    )

    // The card is the only thing keyed on the word, so the app bar, the notices and the controls
    // below stay put while it changes - a screen that slides entirely when a word does reads as
    // having navigated somewhere rather than as having turned over.
    AnimatedContent(
        targetState = entry,
        transitionSpec = {
            if (initialState.word.word.id == targetState.word.word.id) {
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
        modifier = Modifier.fillMaxWidth(),
    ) { current ->
        WordCard(
            entry = current,
            revealed = helpOpen,
            hanziColor = hanziColor,
        )
    }

    Spacer(Modifier.height(Dimens.xl))

    if (!helpOpen) {
        DuelControls(
            recognition = recognition,
            hypothesis = hypothesis,
            verdict = verdict,
            attempts = attempts,
            listening = listening,
            processing = processing,
            micEnabled = feedback == AttemptFeedback.None && !listening && !processing,
            onListen = onListen,
        )
    } else {
        HelpControls(
            wordId = word.id,
            hanzi = word.hanzi,
            pinyin = word.pinyin,
            toneNumber = word.toneNumber,
            exampleCn = word.exampleCn,
            viewModel = viewModel,
            onNext = onNext,
        )
    }
}

/** The rationale card for the microphone permission. */
@Composable
private fun MicRationaleCard(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppCard(
        containerColor = AccentAmberContainer,
        borderColor = AccentAmber.copy(alpha = 0.4f),
    ) {
        Text(
            text = "To check what you say, we need to use your microphone. The words are " +
                "compared on this device - nothing is kept, scored or uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSubtle,
        )
        Spacer(Modifier.height(Dimens.md))
        Row(modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = "Not now",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            HSpace(Dimens.md)
            PrimaryButton(
                text = "Continue",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The duel: microphone, honest status, attempt counter.
 *
 * The counter is the only number on this screen, and it counts *valid tries* - never
 * permission denials, silence, or the recogniser's bad day.
 */
@Composable
private fun DuelControls(
    recognition: RecognitionState,
    hypothesis: String?,
    verdict: RecognitionVerdict,
    attempts: Int,
    listening: Boolean,
    processing: Boolean,
    micEnabled: Boolean,
    onListen: () -> Unit,
) {
    val status = duelStatusText(recognition, hypothesis, verdict, attempts)
    if (status.isNotBlank()) {
        if (verdict == RecognitionVerdict.Recognised) {
            RecognisedBadge(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Dimens.md))
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = duelStatusColor(verdict, recognition),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Dimens.lg))
    }

    AnimatedMicButton(
        onClick = onListen,
        modifier = Modifier.testTag("random_review_listen"),
        listening = listening,
        enabled = micEnabled,
        icon = Icons.Default.Mic,
        contentDescription = "Say the word out loud",
    )
    Spacer(Modifier.height(Dimens.md))
    Text(
        text = when {
            attempts <= 0 -> "Three tries open the answer."
            else -> "Attempt $attempts of $RANDOM_REVIEW_MAX_ATTEMPTS used"
        },
        style = MaterialTheme.typography.labelSmall,
        color = TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The help state: the answer stays open, pronunciation is one tap away, and nothing moves
 * until the learner says so.
 */
@Composable
private fun HelpControls(
    wordId: Long,
    hanzi: String,
    pinyin: String,
    toneNumber: Int,
    exampleCn: String,
    viewModel: MainViewModel,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PronunciationButton(
            service = viewModel.pronunciationService,
            request = PronunciationRequest.forWord(
                sourceId = wordId,
                hanzi = hanzi,
                pinyin = pinyin,
                toneNumber = toneNumber,
            ),
            contentDescription = "Play the correct pronunciation of $hanzi",
        )
        if (exampleCn.isNotBlank()) {
            Spacer(Modifier.width(Dimens.md))
            PronunciationButton(
                service = viewModel.pronunciationService,
                request = PronunciationRequest.forSentenceOfWord(
                    sourceId = wordId,
                    sentence = exampleCn,
                ),
                contentDescription = "Play the example sentence",
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "Listen, then continue when ready.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
    }
    Spacer(Modifier.height(Dimens.lg))
    PrimaryButton(
        text = "Next word",
        onClick = onNext,
        modifier = Modifier.testTag("random_review_next"),
    )
}

/**
 * The word itself: character first, answer second, the answer withheld until help opens.
 *
 * The character is set in [HanziHero] - the design system's own focal size - rather than at a
 * literal, so it sits on the same scale as the review deck and keeps whatever font scaling the
 * learner has set on their phone. Everything under the rule is inside a [RevealItem], so the
 * pinyin lands, then the meaning, then the example, rather than all four arriving at once.
 */
@Composable
private fun WordCard(
    entry: com.example.data.review.RandomWordChoice,
    revealed: Boolean,
    hanziColor: Color,
) {
    val word = entry.word.word

    AppCard(
        modifier = Modifier.fillMaxWidth().testTag("random_review_card"),
        containerColor = DarkSurfaceCard,
        borderColor = OutlineBorder,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Dimens.xl,
            vertical = Dimens.xxl,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (revealed) "ANSWER" else "SAY IT OUT LOUD",
                style = MaterialTheme.typography.labelSmall,
                color = if (revealed) AccentMint else AccentCyan,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (word.hskLevel > 0) "HSK ${word.hskLevel}" else word.radical,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        Spacer(Modifier.height(Dimens.xl))

        Text(
            text = word.hanzi,
            style = HanziHero,
            color = hanziColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.sm),
        )

        Spacer(Modifier.height(Dimens.lg))

        RevealItem(visible = revealed, order = 0) {
            AnswerLine(
                label = "PINYIN",
                value = word.pinyin.ifBlank { "—" },
                valueStyle = PinyinLarge,
                valueColor = AccentCyan,
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
                    valueColor = TextSubtle,
                )
            } else {
                Text(
                    text = "No example sentence for this word yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }

        if (!revealed) {
            Spacer(Modifier.height(Dimens.md))
            Text(
                text = "Say it out loud - three tries open the answer.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The one line under the card, and what colour it is.
 *
 * Three honest answers rather than a score: the recogniser produced this word, it produced
 * something else, or it produced nothing. The caveat about what recognition does *not*
 * establish travels with the success case, because a green word and no qualification is how a
 * learner ends up believing their tones were graded.
 */
private fun duelStatusText(
    recognition: RecognitionState,
    hypothesis: String?,
    verdict: RecognitionVerdict,
    attempts: Int,
): String = when {
    recognition is RecognitionState.Listening -> "Listening…"
    recognition is RecognitionState.Processing -> "Finishing…"
    verdict == RecognitionVerdict.Recognised ->
        "We heard “$hypothesis” — that's this word. Recognition confirms the words, not your " +
            "tones."
    verdict == RecognitionVerdict.Different -> {
        val left = RANDOM_REVIEW_MAX_ATTEMPTS - attempts
        if (left > 0) {
            "We heard “$hypothesis”, not this word. Try again " +
                "($left ${if (left == 1) "try" else "tries"} left)."
        } else {
            "We heard “$hypothesis”, not this word."
        }
    }
    recognition is RecognitionState.Failed -> failureText(recognition.failure)
    else -> "Tap the microphone and say the word."
}

private fun duelStatusColor(
    verdict: RecognitionVerdict,
    recognition: RecognitionState,
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
        "Speech recognition needs a connection right now. Keep trying instead."
    RecognitionFailure.MicrophoneBusy ->
        "The microphone is busy. Try again in a moment."
    RecognitionFailure.NoSpeech ->
        "We didn't catch that. Try again, a little louder."
    RecognitionFailure.Cancelled -> ""
    RecognitionFailure.Unknown ->
        "We couldn't listen just now. Try again, or wait for the help state."
}
