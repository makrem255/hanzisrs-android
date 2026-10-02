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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.WordWithSrs
import com.example.audio.PronunciationButton
import com.example.audio.PronunciationRequest
import com.example.data.progress.SessionSummary
import com.example.data.progress.UnlockedAward
import com.example.data.srs.SrsRating
import com.example.ui.components.IconTarget
import com.example.ui.components.InteractiveStrokeSection
import com.example.ui.components.MinTouchTarget
import com.example.ui.components.SegmentedOption
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
import com.example.ui.theme.SrsGoodContainer
import com.example.ui.theme.SrsGoodDark
import com.example.ui.theme.SrsHardDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * How far a card has to travel sideways before the gesture counts as a grade.
 *
 * Roughly a fifth of a phone's width. Below it, a drag that turns out to be a scroll or a
 * mis-tap returns to centre, and the learner has to say what they meant with the four buttons -
 * which is the point, because the swipe is a shortcut to a choice the buttons make explicit.
 */
private const val SWIPE_COMMIT_THRESHOLD = 250f

/** How far past the threshold a card is thrown once the gesture has committed. */
private const val FLY_OUT = 900f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeDeckReviewScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val dueWords by viewModel.dueWords.collectAsStateWithLifecycle()
    val allWords by viewModel.userWords.collectAsStateWithLifecycle()
    val wordsLoaded by viewModel.wordsLoaded.collectAsStateWithLifecycle()
    val reviewSessionWordIds by viewModel.reviewSessionWordIds.collectAsStateWithLifecycle()

    // Notifications can enter this screen directly, without the dashboard's Start action.
    //
    // The rule for which cards a sitting covers is not restated here. It used to be, as
    // `if (dueWords.isNotEmpty()) dueWords else allWords`, in a second spelling of the copy in
    // the navigation layer — two places to change and no test covering either. The keys stay as
    // they are: this re-runs when a query lands, so a screen opened before `userWords` resolved
    // still settles once it has.
    LaunchedEffect(reviewSessionWordIds, dueWords, allWords) {
        if (reviewSessionWordIds == null) {
            viewModel.ensureReviewSession()
        }
    }

    // The deck, as the view model snapshotted it when the sitting began.
    //
    // This used to be re-derived here from the id list and the live `userWords` flow. Every rating
    // moves a `srs_state` due date, which invalidates that flow, so the deck was recomputed
    // mid-sitting - and the card at the index the learner was looking at could stop being the
    // card they had read. The tap then recorded a review for a word that was never shown.
    // Reading the view model's snapshot also means the words cannot vanish underneath the deck
    // the way a re-lookup from a collection flow lets them.
    val reviewDeck by viewModel.reviewDeck.collectAsStateWithLifecycle()

    // The deck machine, as one value. The index and the reveal used to be read from two further
    // `StateFlow`s that the view model had to write by hand at every transition, so the card on
    // screen and the state tracking it could disagree; both of the bugs fixed in the last audit
    // lived in that gap. Everything below is now read off this one object.
    val deckState by viewModel.reviewDeckState.collectAsStateWithLifecycle()
    val currentDeckIndex = deckState.index
    val isFlipped = deckState.revealed
    val isSlowTts by viewModel.isSlowTts.collectAsStateWithLifecycle()
    val reviewError by viewModel.reviewError.collectAsStateWithLifecycle()
    val sessionSummary by viewModel.sessionSummary.collectAsStateWithLifecycle()
    val sessionAwards by viewModel.sessionAwards.collectAsStateWithLifecycle()
    val nothingLeftToReview by viewModel.nothingLeftToReview.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(reviewError) {
        reviewError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearReviewError()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    // A plain float, not an `Animatable`. Drag used to write the offset through
    // `coroutineScope.launch { snapTo(...) }`, which is one coroutine per touch event for the whole
    // length of a swipe; those launches are queued, not ordered, so a fast drag applied its deltas
    // out of order and the card could lag behind or jump. Reading and writing the value directly
    // is both cheaper and impossible to reorder.
    // Saveable, so a rotation mid-sitting does not snap the card back to centre or drop the
    // pillar the learner had opened. These are `remember`, not `rememberSaveable`, which
    // meant that turning the phone sideways in the middle of a drag threw the drag away and
    // reset the card — the learner lost their place because the OS changed a dimension.
    // `isFlipped` and the card index already live in the view model and survive correctly;
    // these two are the gap.
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }

    // Recentre for each new card. Flinging the card off-screen and waiting for it to come back
    // meant the learner watched a blank rectangle for however long the database write took; the
    // fling is now independent of the write, so the reset is keyed on the card changing.
    LaunchedEffect(currentDeckIndex) { offsetX = 0f }
    // Meaning first. The question a learner brings to a review card is what the character means,
    // and it was the answer - behind a second tap on a tab that opened on stroke order.
    var selectedPillarTab by rememberSaveable { mutableIntStateOf(1) } // 0 = Writing & Strokes, 1 = Meaning & Radical, 2 = Context Sentence

    // `isFinished` was `currentDeckIndex >= reviewDeck.size`, which compared an index owned by
    // the deck state against a size owned by a *different* list - two sources for one question.
    // The state machine's own answer uses the same id list the index is relative to, and it also
    // answers for an empty deck, where the hand-written comparison only got `0 >= 0` right by
    // coincidence.
    val isFinished = deckState.isFinished

    // Closing the session is a side effect of reaching the end of the deck, keyed on the deck
    // having actually been worked through. Guarded so it runs once: `finishSession` is a
    // suspending write, and firing it on every recomposition of this branch would run an award
    // pass per frame.
    LaunchedEffect(isFinished, reviewDeck.size) {
        if (isFinished && reviewDeck.isNotEmpty()) {
            viewModel.finishSession()
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
                    // One title, not two. The "Card 3 of 12" subtitle here was the same
                    // sentence the counter row prints directly beneath it, so it was
                    // duplicated on screen for the whole sitting — and in a short viewport
                    // that second line is height the card needs.
                    Text(
                        text = "Daily review",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AccentPrimary)
                    }
                },
                actions = {
                    // Slow TTS Toggle
                    IconButton(onClick = { viewModel.toggleSlowTts() }) {
                        Icon(
                            Icons.Default.Speed,
                            // Says which state it is in, not what it does. A toggle that
                            // only announces its label leaves a screen reader user unable to
                            // tell a slow pronunciation session from a normal one.
                            contentDescription = if (isSlowTts) {
                                "Slow pronunciation on"
                            } else {
                                "Slow pronunciation off"
                            },
                            tint = if (isSlowTts) AccentPrimary else TextMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBg
    ) { padding ->
        // Three states, not two. The guard used to be `reviewSessionWordIds == null &&
        // allWords.isNotEmpty()`, which read an empty collection that had not been loaded yet as a
        // collection with nothing in it - so a learner with words, or a learner with none, both
        // got told they had nothing to review for as long as the query took to come back.
        if (!wordsLoaded || reviewSessionWordIds == null) {
            ReviewSessionLoadingView(modifier = Modifier.padding(padding))
        } else if (isFinished || reviewDeck.isEmpty()) {
            ReviewSessionCompletedView(
                summary = sessionSummary,
                awards = sessionAwards,
                // Distinguishes "you finished a sitting" from "you asked to review and there
                // was nothing left". The second one is not a failure and not a completion,
                // and it must not be dressed up as either — the summary card leads with a
                // result the learner did not achieve.
                nothingLeft = nothingLeftToReview,
                onBack = {
                    // Cleared on the way out, not on the way in, so returning to this screen for
                    // a second sitting does not show the first sitting's result again.
                    viewModel.clearSessionSummary()
                    onNavigateBack()
                },
                // Over what is due *now*, queried inside the view model rather than read from the
                // `dueWords` snapshot this screen was holding. That snapshot is a flow emission
                // from before the reviews just written reached the database, so it still listed
                // the deck the learner had just finished — the opposite of what the button says.
                onRestart = viewModel::restartReviewSession,
                modifier = Modifier.padding(padding)
            )
        } else {
            val currentWordWithSrs = reviewDeck[currentDeckIndex]
            val nextIntervals = remember(currentWordWithSrs.srs) {
                SrsRating.entries.associateWith { rating ->
                    // Through the repository, so the interval this button promises is the interval
                    // the write will actually produce. The screen does not know which scheduling
                    // rule is in force, and should not have to.
                    viewModel.previewInterval(currentWordWithSrs.srs, rating)
                }
            }

            // How much height the card actually has to work with.
            //
            // This is the whole difference between a usable deck and an unusable one in
            // landscape, so it is measured rather than assumed. On a Pixel-class phone in
            // landscape the content area is ~330dp tall. The old fixed costs took ~62dp of
            // that before the card was drawn, and the rating block took another ~97dp once
            // the card was revealed — leaving roughly 170dp of viewport for a card whose
            // content is about 420dp, headed by a 56sp character. The learner saw the glyph
            // and had to scroll for the answer they were being asked to rate.
            //
            // Below the threshold the deck sheds what is duplicated elsewhere and shrinks the
            // one thing that is not text: the progress bar goes (the counter row directly
            // beneath already says the same thing), the rating label goes (each button
            // already prints the interval it will apply), and the character comes down from
            // 56sp to 36sp, which is still larger than any other glyph in the app.
            //
            // The scaffold's own `padding` is applied *here* rather than on the `Column`
            // below, so `maxHeight` is the height the content genuinely has. Applying it
            // inside would have `maxHeight` include the inset that had not been subtracted
            // yet, which on this screen is the app bar plus the status bar — enough to
            // read ~12% high, i.e. to report "plenty of room" in landscape.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val shortViewport = maxHeight < 520.dp
                val glyphSize = if (shortViewport) 36.sp else 56.sp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = if (shortViewport) 4.dp else 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                if (!shortViewport) {
                    // Progress Bar
                    LinearProgressIndicator(
                        // Was `(currentDeckIndex + 1) / reviewDeck.size`, unclamped. When the last
                        // card is answered the index lands on the exhausted sentinel
                        // `wordIds.size`, so the ratio becomes `(size + 1) / size` and the bar is
                        // asked to draw past full - on the one screen where the learner is most
                        // likely to be looking at it. `ReviewDeckState.progress` clamps at both
                        // ends, guards the empty deck, and is tested; this was a hand-rolled
                        // duplicate of it that used the version with the bug.
                        progress = { deckState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentPrimary,
                        trackColor = DarkSurfaceContainer
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Step through the deck without grading it.
                //
                // Both arrows stay visible and one is disabled, rather than each appearing only
                // when it has somewhere to go: a control that materialises on hover reads as a
                // reward, and a disabled one explains the deck's edges for free. Stepping back
                // re-reveals a card already answered, so its rating is shown instead of a
                // prediction - re-grading it would write a second review for one sitting.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { viewModel.goToPreviousCard() },
                        enabled = currentDeckIndex > 0
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = if (currentDeckIndex > 0) TextLight else TextSubtle
                        )
                        Text("Back", color = if (currentDeckIndex > 0) TextLight else TextSubtle)
                    }

                    Text(
                        text = "Card ${deckState.positionLabel}",
                        fontSize = 12.sp,
                        color = TextMuted
                    )

                    TextButton(
                        onClick = { viewModel.goToNextCard() },
                        enabled = currentDeckIndex < reviewDeck.size - 1
                    ) {
                        Text("Skip", color = if (currentDeckIndex < reviewDeck.size - 1) TextLight else TextSubtle)
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = if (currentDeckIndex < reviewDeck.size - 1) TextLight else TextSubtle
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // The Swipable Flashcard Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .rotate(offsetX / 40f)
                        .pointerInput(currentDeckIndex, isFlipped, currentWordWithSrs.word.id) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    // Only a revealed card can be rated. A swipe used to record a
                                    // grade whether the learner had read the answer or not, so a
                                    // deck could be cleared without a single recall - writing a
                                    // real review, moving a real due date and paying real XP for
                                    // a question that was never asked of anyone.
                                    val rating = when {
                                        !isFlipped -> null
                                        offsetX > SWIPE_COMMIT_THRESHOLD -> SrsRating.GOOD
                                        offsetX < -SWIPE_COMMIT_THRESHOLD -> SrsRating.HARD
                                        else -> null
                                    }
                                    val target = when {
                                        offsetX > SWIPE_COMMIT_THRESHOLD -> FLY_OUT
                                        offsetX < -SWIPE_COMMIT_THRESHOLD -> -FLY_OUT
                                        else -> 0f
                                    }
                                    // The write is not awaited. It used to be, so the card stayed
                                    // parked off-screen for the length of the database round trip
                                    // with nothing on it; the fling runs on its own clock and the
                                    // deck advances when the write lands.
                                    rating?.let { viewModel.submitRating(currentWordWithSrs, it) }
                                    coroutineScope.launch {
                                        val proxy = Animatable(offsetX)
                                        proxy.animateTo(target, spring()) { offsetX = value }
                                    }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    offsetX += dragAmount
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
                                        color = AccentPrimary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    color = if (currentWordWithSrs.isDue) SrsAgainDark.copy(alpha = 0.25f) else SrsGoodDark.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (currentWordWithSrs.isDue) SrsAgainDark else SrsGoodDark)
                                ) {
                                    Text(
                                        // The learner's word for the state, not the storage
                                        // token. `text = currentWordWithSrs.state` printed
                                        // `REVIEW` / `LEARNING` / `NEW` on the one badge the
                                        // learner reads while deciding how hard the word was -
                                        // and `REVIEW` is also a noun in this app, because it
                                        // is the tab they tapped to get here. The badge and
                                        // the tab were the same string meaning two different
                                        // things.
                                        text = srsStateLabelOrNull(currentWordWithSrs.state)
                                            ?: "Unknown state",
                                        // 11sp, not 10. This badge is one of two pieces of
                                        // information on the card the learner must read before
                                        // deciding how hard it was, and 10sp is marginal at
                                        // arm's length on a 6.1" screen.
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (currentWordWithSrs.isDue) SrsAgainDark else SrsGoodDark,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                // Audio. The control, its loading spinner, its replay and its
                                // failure caption all come from PronunciationButton, because all
                                // four were previously absent: this used to be a bare
                                // `speak(hanzi)` that reported success by not failing, so the
                                // first tap after launch was discarded in silence and a device
                                // with no Mandarin voice produced nothing at all.
                                //
                                // The request is remembered against the *word*, not the tap. The
                                // card is swipable, and a learner who swipes mid-utterance must
                                // not be left with a spinner on a card they have already left.
                                val audioRequest = remember(currentWordWithSrs.word.id) {
                                    PronunciationRequest.forWord(
                                        sourceId = currentWordWithSrs.word.id,
                                        hanzi = currentWordWithSrs.word.hanzi,
                                        pinyin = currentWordWithSrs.word.pinyin,
                                        toneNumber = currentWordWithSrs.word.toneNumber
                                    )
                                }
                                PronunciationButton(
                                    service = viewModel.pronunciationService,
                                    request = audioRequest,
                                    contentDescription =
                                    "Hear ${currentWordWithSrs.word.hanzi} pronounced",
                                    testTag = "deck_audio_button"
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Large Hanzi Display
                            Text(
                                text = currentWordWithSrs.word.hanzi,
                                fontSize = glyphSize,
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
                                        containerColor = AccentPrimary,
                                        contentColor = AccentPrimaryInk
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
                                        color = AccentPrimary,
                                        textAlign = TextAlign.Center
                                    )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 4-Pillar Pill Tabs (Writing, Meaning, Context Sentence)
                            //
                            // 48dp and `selectable`, where these were 34dp `clickable` Boxes.
                            // Two defects, one fix: 34dp is a third under the touch floor, and
                            // a bare `clickable` gave a screen reader three unrelated buttons
                            // with no way to tell which pillar was open.
                            //
                            // The emoji are gone. Every other icon in this app is a Material
                            // icon, and an emoji's rendered width is whichever font the OEM
                            // ships, so the one thing that was guaranteed about the label was
                            // that its width was unpredictable — inside a `weight(1f)` cell
                            // that is a layout that can truncate differently on two phones.
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
                                        .padding(horizontal = 3.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    PillarTab(
                                        label = "Writing",
                                        icon = Icons.Default.Edit,
                                        selected = selectedPillarTab == 0,
                                        onClick = { selectedPillarTab = 0 },
                                        modifier = Modifier.testTag("deck_tab_writing")
                                    )
                                    PillarTab(
                                        label = "Meaning",
                                        icon = Icons.Default.Psychology,
                                        selected = selectedPillarTab == 1,
                                        onClick = { selectedPillarTab = 1 },
                                        modifier = Modifier.testTag("deck_tab_meaning")
                                    )
                                    PillarTab(
                                        label = "Context",
                                        icon = Icons.Default.RecordVoiceOver,
                                        selected = selectedPillarTab == 2,
                                        onClick = { selectedPillarTab = 2 },
                                        modifier = Modifier.testTag("deck_tab_context")
                                    )
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
                                            color = AccentPrimary
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
                                        Text(
                                            "Example sentence",
                                            fontSize = 12.sp,
                                            color = AccentPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        // `forSentenceOfWord` rather than `forSentence`: the
                                        // card's other audio button is this word's own
                                        // pronunciation, and a shared sourceId would make both
                                        // controls light up for one tap.
                                        PronunciationButton(
                                            service = viewModel.pronunciationService,
                                            request = remember(currentWordWithSrs.word.id) {
                                                PronunciationRequest.forSentenceOfWord(
                                                    sourceId = currentWordWithSrs.word.id,
                                                    sentence = currentWordWithSrs.word.exampleCn
                                                )
                                            },
                                            contentDescription = "Hear the example sentence",
                                            testTag = "deck_sentence_audio"
                                        )
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
                                            color = AccentPrimary,
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

                // Four buttons that grey out while the answer they are recording is being written.
            val canRate = deckState.canRate

            if (isFlipped) {
                    Spacer(modifier = Modifier.height(if (shortViewport) 6.dp else 12.dp))

                // PILLAR 4: SRS SM-2 RATING BUTTONS (Again, Hard, Good, Easy)
                //
                // The label is dropped in a short viewport. It is ~20dp that says what each
                // button's own second line already says, and in landscape those 20dp are
                // 5% of the card's viewport.
                if (!shortViewport) {
                    Text(
                        text = "Rate recall difficulty",
                        fontSize = 12.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Again (<1d)
                    SrsRatingButton(
                        rating = SrsRating.AGAIN,
                        interval = nextIntervals.getValue(SrsRating.AGAIN),
                        color = SrsAgainDark,
                        enabled = canRate,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.AGAIN) }
                    )

                    // 2. Hard (~1.2x)
                    SrsRatingButton(
                        rating = SrsRating.HARD,
                        interval = nextIntervals.getValue(SrsRating.HARD),
                        color = SrsHardDark,
                        enabled = canRate,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.HARD) }
                    )

                    // 3. Good (~2.5x)
                    SrsRatingButton(
                        rating = SrsRating.GOOD,
                        interval = nextIntervals.getValue(SrsRating.GOOD),
                        color = SrsGoodDark,
                        enabled = canRate,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.GOOD) }
                    )

                    // 4. Easy (>3.5x)
                    SrsRatingButton(
                        rating = SrsRating.EASY,
                        interval = nextIntervals.getValue(SrsRating.EASY),
                        color = SrsEasyDark,
                        enabled = canRate,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.submitRating(currentWordWithSrs, SrsRating.EASY) }
                    )
                }   // the four-button Row
            }       // `if (isFlipped)`: no rating buttons before the answer is revealed
        }           // the deck Column
    }               // BoxWithConstraints
}                   // `else` branch of the three-state guard
}                   // the Scaffold content lambda
}                   // SwipeDeckReviewScreen

@Composable
private fun SrsRatingButton(
    rating: SrsRating,
    interval: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // The label comes from the enum, not from a `label` argument.
    //
    // This took both: a `rating: SrsRating` that was never read in the body, and a `label: String`
    // passed alongside it. So the call site named the rating twice, the two could disagree with
    // nothing to notice, and `SrsRating.label` — the domain's own copy of the button text — was
    // never rendered anywhere in the app. A fix to the enum would have shipped as a no-op.
    val label = rating.label
    ElevatedButton(
        onClick = onClick,
        // While the write for this card is in flight the buttons go disabled.
        //
        // `ReviewDeckState.beginRating()` already refuses the second tap, so this is not a
        // double-write fix - the state machine holds that. It is the missing feedback: four
        // buttons that accept a tap, do nothing, and stay lit for as long as the database round
        // trip takes. `canRate` is the same predicate that guard uses, so the greying-out and
        // the refusal cannot disagree about when a rating is allowed.
        enabled = enabled,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = color.copy(alpha = 0.2f),
            contentColor = color
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = modifier
            .height(56.dp)
            .testTag("srs_rate_${label.lowercase()}"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
            // 11sp, not 10: this is the interval the learner is being shown to justify the
            // tap, and it is the smallest text in the app's primary action.
            Text(interval, fontSize = 11.sp, color = color.copy(alpha = 0.85f))
        }
    }
}

/**
 * One of the three pillars on a revealed card.
 *
 * A `RowScope` extension so the `weight(1f)` is stated here, next to the layout that needs
 * it, rather than repeated by each caller. The label is the only content, so this is
 * deliberately plain: the row's job is to say which of three things is open, and this
 * composable's job is to be big enough to hit and to say so out loud to a screen reader.
 */
@Composable
private fun RowScope.PillarTab(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SegmentedOption(
        selected = selected,
        onClick = onClick,
        modifier = modifier.weight(1f)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (selected) AccentPrimaryInk else TextSubtle
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = if (selected) AccentPrimaryInk else TextSubtle
            )
        }
    }
}

/**
 * The end of a sitting, reported from the review log.
 *
 * [summary] is null when the deck was opened and closed without an answer, and null is rendered
 * as "no cards were answered" rather than as a row of zeroes or as praise. The old text here
 * claimed a routine was practised and that intervals had been recalculated regardless of what
 * happened, which is the failure this whole layer exists to remove: a fixed congratulatory
 * sentence is a statement the app makes whether or not it is true.
 *
 * [nothingLeft] is the answer to "the learner pressed *Review what is still due* and the
 * database said there was nothing". It is kept separate from [summary] because the two answers
 * are about different things: the summary is a fact about a sitting that happened, the
 * restart is a fact about work that does not exist. A learner who finished 20 cards and then
 * found nothing left has still finished 20 cards, and the screen must not overwrite that.
 */
@Composable
private fun ReviewSessionCompletedView(
    summary: SessionSummary?,
    awards: List<UnlockedAward>,
    nothingLeft: Boolean,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studied = summary != null && summary.answers > 0
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
                        // A green tick is reserved for a sitting that actually happened.
                        // Showing one for an untouched deck would be congratulating a
                        // learner for nothing — and `nothingLeft` is deliberately *not*
                        // enough on its own to earn one. "Nothing is due" is a fact about
                        // the schedule, not an achievement, and a learner who has never
                        // opened the app has not "finished" anything.
                        .background(if (studied) SrsGoodContainer else DarkSurfaceElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (studied) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (studied) SrsGoodDark else TextMuted,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (studied) "Session complete" else "Nothing to review",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextLight,
                    modifier = Modifier.testTag("session_complete_title")
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = when {
                        // Finished the deck, then asked for what was left and there was
                        // nothing. Both facts are true and both are worth saying.
                        studied && nothingLeft ->
                            "Nothing is due right now. Your intervals have been recalculated, " +
                                "and the next card will come back when it is time."
                        studied ->
                            "Your intervals have been recalculated. Anything still due will come " +
                                "back when it is time."
                        // Reached the empty answer, whether by pressing "Review what is
                        // still due" or by opening a deck that was never populated. Both
                        // deserve the same sentence, because both mean the same thing.
                        nothingLeft ->
                            "Nothing is scheduled for review right now. Add a word, or come " +
                                "back when something is due."
                        else ->
                            "No cards were due right now. Add a word, or come back when something " +
                                "is scheduled."
                    },
                    fontSize = 13.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )

                if (studied && summary != null) {
                    Spacer(modifier = Modifier.height(18.dp))
                    SessionSummaryCard(
                        summary = summary,
                        awards = awards,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (awards.isNotEmpty()) {
                    // A badge can be earned without the session summary being shown, so it still
                    // has somewhere to go rather than being silently dropped.
                    Spacer(modifier = Modifier.height(18.dp))
                    SessionSummaryCard(
                        summary = SessionSummary.EMPTY,
                        awards = awards,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary, contentColor = AccentPrimaryInk),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget)
                        .testTag("session_return_button")
                ) {
                    Text("Return to dashboard", fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // The label was "Review Entire Deck Again", which described behaviour this
                // button does not have. It starts a session over what is *due now* — queried
                // fresh — because restarting over every word the learner owns would write real
                // reviews, move real due dates and pay real XP for cards that were not due.
                // The behaviour is right; the label was still promising the old, wrong thing.
                //
                // Hidden entirely once the answer is known to be "nothing". Leaving a button
                // there that can only re-report nothing is a control the learner will press
                // again, wonder about, and learn to distrust.
                if (!nothingLeft) {
                    OutlinedButton(
                        onClick = onRestart,
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouchTarget)
                            .testTag("session_review_again_button")
                    ) {
                        Text(
                            text = if (studied) "Review what is still due" else "Start a review",
                            color = TextLight,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewSessionLoadingView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(color = AccentPrimary)
    }
}
