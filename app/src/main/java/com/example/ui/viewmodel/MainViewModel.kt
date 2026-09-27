package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiAiService
import com.example.data.ai.GeneratedWordData
import com.example.data.dashboard.DashboardSnapshot
import com.example.data.db.AppDatabase
import com.example.data.model.NewWordDraft
import com.example.data.model.SrsStateEntity
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import com.example.data.model.WordWithSrs
import com.example.data.repository.AuthResult
import com.example.data.repository.DashboardRepository
import com.example.data.repository.GamificationRepository
import com.example.data.repository.ReviewFailure
import com.example.data.repository.ReviewOutcome
import com.example.data.repository.SaveWordResult
import com.example.data.repository.SrsRepository
import com.example.data.repository.StudySessionRepository
import com.example.data.repository.UserRepository
import com.example.data.repository.WordRepository
import com.example.data.progress.LearnerProgress
import com.example.data.progress.SessionSummary
import com.example.data.progress.UnlockedAward
import com.example.data.srs.ReviewDeckState
import com.example.data.srs.SrsRating
import com.example.util.NotificationHelper
import com.example.util.TextToSpeechHelper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * What the dashboard can be showing.
 *
 * A sealed set rather than a nullable snapshot plus a boolean, so the screen cannot be handed
 * `null` with `isLoading = true` and decide for itself what to draw. [Failed] exists because a
 * dashboard that silently shows the last value it managed to read is worse than one that admits
 * it could not read: an error drawn as a row of zeroes is a number the learner never earned.
 */
sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Ready(val snapshot: DashboardSnapshot) : DashboardUiState()
    data class Failed(val message: String) : DashboardUiState()
}


/**
 * What the progress surface can be showing.
 *
 * The same sealed shape as [DashboardUiState], and for the same reason: a level bar drawn from a
 * defaulted value while the read is still in flight is a number the learner never earned, so
 * "still reading" has to be a state the screen can see rather than a null it has to interpret.
 */
sealed class ProgressUiState {
    object Loading : ProgressUiState()
    data class Ready(val progress: LearnerProgress) : ProgressUiState()
    data class Failed(val message: String) : ProgressUiState()
}


sealed class AiGenerationState {
    object Idle : AiGenerationState()
    object Loading : AiGenerationState()
    data class ReadyForReview(val wordData: GeneratedWordData) : AiGenerationState()
    data class Error(val message: String) : AiGenerationState()
}

// flatMapLatest is used below to re-scope the word queries to the signed-in learner.
// It is still marked experimental in coroutines, so opt in explicitly rather than
// leaving the compiler warning in place.
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val userRepository = UserRepository(database.userDao(), database)
    val wordRepository = WordRepository(database)
    val srsRepository = SrsRepository(database)
    private val dashboardRepository = DashboardRepository(database)
    private val gamificationRepository = GamificationRepository(database)
    private val sessionRepository = StudySessionRepository(database)
    private val geminiService = GeminiAiService()
    val ttsHelper = TextToSpeechHelper(application)

    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser

    // Observe all words for current user
    val userWords: StateFlow<List<WordWithSrs>> = currentUser.flatMapLatest { user ->
        if (user != null) {
            wordRepository.getWordsForUser(user.id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Whether [userWords] has produced its first value.
     *
     * An empty list from that flow means two quite different things - "the learner has no words",
     * which is worth a screen, and "the query has not come back yet", which is worth a spinner -
     * and the flow itself cannot tell them apart, because `stateIn` starts it off empty. A screen
     * that treats the second as the first tells a brand new learner they have nothing to study,
     * and does it every time they open the app, before the database has been asked.
     */
    val wordsLoaded: StateFlow<Boolean> = userWords
        .map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Observe due words for swipe review deck
    val dueWords: StateFlow<List<WordWithSrs>> = currentUser.flatMapLatest { user ->
        if (user != null) {
            wordRepository.getDueWordsForUser(user.id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Observe due count for badge and notification payload
    val dueCount: StateFlow<Int> = dueWords
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Add Word / AI State
    private val _aiState = MutableStateFlow<AiGenerationState>(AiGenerationState.Idle)
    val aiState: StateFlow<AiGenerationState> = _aiState.asStateFlow()

    // Auth State
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _authLoading = MutableStateFlow(false)
    val authLoading: StateFlow<Boolean> = _authLoading.asStateFlow()

    // ---- the review deck ------------------------------------------------------------------------

    /**
     * The deck, as one state.
     *
     * Four separate `MutableStateFlow`s used to model one thing, which is how a reveal, an index
     * and an in-flight write could be made to disagree with each other: nothing stopped a second
     * tap from firing a second write for a card whose first write had not come back yet, and
     * nothing stopped a rating being recorded for a card the learner had not revealed.
     * [ReviewDeckState] is a pure state machine, so both of those are now a single transition
     * that either is allowed or is not - `beginRating()` returns null for the second tap, and
     * `canRate` is false until the card has been revealed.
     *
     * The words themselves are snapshotted into [reviewDeck], not re-derived from the live
     * collection flow. A rating updates `srs_state.dueDateMillis`, which invalidates that flow, and
     * re-resolving the deck from it mid-session let the card under the learner's finger change
     * identity between being shown and being rated.
     */
    private val _reviewDeckState = MutableStateFlow(ReviewDeckState(emptyList()))

    /** The words in this sitting, in the order they will be shown. Fixed when the deck starts. */
    private val _reviewDeck = MutableStateFlow<List<WordWithSrs>>(emptyList())
    val reviewDeck: StateFlow<List<WordWithSrs>> = _reviewDeck.asStateFlow()

    private val _currentDeckIndex = MutableStateFlow(0)
    val currentDeckIndex: StateFlow<Int> = _currentDeckIndex.asStateFlow()

    private val _isCardFlipped = MutableStateFlow(false)
    val isCardFlipped: StateFlow<Boolean> = _isCardFlipped.asStateFlow()

    private val _reviewedSessionCount = MutableStateFlow(0)
    val reviewedSessionCount: StateFlow<Int> = _reviewedSessionCount.asStateFlow()

    /** Null until a deck has been started, which is what tells the screen a session is pending. */
    private val _reviewSessionWordIds = MutableStateFlow<List<Long>?>(null)
    val reviewSessionWordIds: StateFlow<List<Long>?> = _reviewSessionWordIds.asStateFlow()

    private val _wordSaveError = MutableStateFlow<String?>(null)
    val wordSaveError: StateFlow<String?> = _wordSaveError.asStateFlow()

    private val _isSavingWord = MutableStateFlow(false)
    val isSavingWord: StateFlow<Boolean> = _isSavingWord.asStateFlow()

    // Review write failures, surfaced by the swipe deck.
    private val _reviewError = MutableStateFlow<String?>(null)
    val reviewError: StateFlow<String?> = _reviewError.asStateFlow()

    // ---- progress, sessions and badges ---------------------------------------------------------

    private val _progressRefresh = MutableStateFlow(0)

    /** Re-reads progress. A new value restarts the inner flow, so this is a real retry. */
    fun refreshProgress() {
        _progressRefresh.value += 1
    }

    /** Level, milestones and badges for the signed-in learner. */
    val progressState: StateFlow<ProgressUiState> =
        combine(currentUser, _progressRefresh) { user, _ -> user }
            .flatMapLatest { user ->
                if (user == null) {
                    flowOf(ProgressUiState.Loading)
                } else {
                    gamificationRepository.observeProgress(user.id)
                        .map<LearnerProgress, ProgressUiState>(ProgressUiState::Ready)
                        .catch { emit(ProgressUiState.Failed("Your progress could not be read.")) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProgressUiState.Loading)

    // The session the learner is currently in, and what it produced once it ends. Held here
    // because the deck knows when a session is over and the summary has to outlive that screen.
    private val _sessionSummary = MutableStateFlow<SessionSummary?>(null)
    val sessionSummary: StateFlow<SessionSummary?> = _sessionSummary.asStateFlow()

    private val _sessionAwards = MutableStateFlow<List<UnlockedAward>>(emptyList())
    val sessionAwards: StateFlow<List<UnlockedAward>> = _sessionAwards.asStateFlow()

    /**
     * True when a restart was asked for and the database said there was nothing due.
     *
     * This is deliberately a different state from "the deck is empty". An empty deck means
     * "there is genuinely no work right now", and a learner who asked to review and found
     * nothing deserves to be told so plainly — not shown the loading spinner, and not shown
     * a card. It is reset by [startReviewSession] and by [restartReviewSession] finding work,
     * so it cannot latch once set.
     */
    private val _nothingLeftToReview = MutableStateFlow(false)
    val nothingLeftToReview: StateFlow<Boolean> = _nothingLeftToReview.asStateFlow()

    /**
     * The session currently being written, or null when there is none.
     *
     * A [Deferred] rather than a plain `Long?` because opening a session is a suspending write and
     * a learner can tap a rating before it has finished. A plain field would be null in that
     * window, the answer would be logged with no session, and it would silently never be counted
     * towards `SESSIONS_COMPLETED`. Awaiting is the difference between a fast tap being recorded
     * and a fast tap being lost.
     */
    private var activeSession: Deferred<Long?>? = null

    /**
     * Closes the session and reports what it produced.
     *
     * Safe to call when no session is open: the completion screen is also what a learner sees
     * when they arrive with nothing due, and that must not be an error.
     */
    fun finishSession() {
        val user = currentUser.value ?: return
        val session = activeSession
        val now = System.currentTimeMillis()

        if (session == null) {
            // Nothing was studied, so there is nothing to summarise. Null rather than a
            // zero-card summary, because a session of zero cards is not a session.
            _sessionSummary.value = null
            _sessionAwards.value = emptyList()
            return
        }
        activeSession = null

        viewModelScope.launch {
            // Awaited for the same reason `submitRating` awaits it: a session that was opened
            // milliseconds ago has no id yet, and closing a null id would leave the real one
            // open for the next sitting to abandon.
            val sessionId = session.await() ?: run {
                _sessionSummary.value = null
                _sessionAwards.value = emptyList()
                return@launch
            }

            val closed = sessionRepository.end(
                sessionId = sessionId,
                userId = user.id,
                status = StorageValues.SessionStatus.COMPLETED,
                now = now
            )
            // Only report a session that actually recorded answers. A deck the learner opened and
            // closed has a row and no history, and a summary claiming "0 answered, 100% correct"
            // is the kind of number that makes the rest of the app untrustworthy.
            if (closed != null && closed.reviewedCount > 0) {
                _sessionSummary.value = gamificationRepository.summariseSession(
                    userId = user.id,
                    sessionId = sessionId,
                    durationMillis = closed.durationMillis
                )
                val earned = gamificationRepository.evaluateAwards(user.id, now)
                _sessionAwards.value = earned
                earned.forEach { award ->
                    gamificationRepository.markSeen(user.id, award.userAchievementId, now)
                }
            } else {
                _sessionSummary.value = null
                _sessionAwards.value = emptyList()
            }
            refreshProgress()
        }
    }

    /** Clears the session result, so leaving the screen does not replay it on the way back in. */
    fun clearSessionSummary() {
        _sessionSummary.value = null
        _sessionAwards.value = emptyList()
    }

    // ---- dashboard ---------------------------------------------------------------------------------
    // The dashboard is one value rather than a set of independent flows. Separate flows would
    // let the screen draw a streak from yesterday beside a queue count from this instant, and
    // the learner would be looking at two truths at once.
    private val _dashboardRefresh = MutableStateFlow(0)

    /** Re-reads the dashboard. Emitting a new value restarts the inner flow, so this is a real retry. */
    fun refreshDashboard() {
        _dashboardRefresh.value += 1
    }

    val dashboardState: StateFlow<DashboardUiState> =
        combine(currentUser, _dashboardRefresh) { user, _ -> user }
        .flatMapLatest { user ->
            if (user == null) {
                // No learner signed in. Reported as loading rather than as an empty dashboard,
                // because "signed out" is not the same as "no progress to show".
                flowOf(DashboardUiState.Loading)
            } else {
                dashboardRepository.observeDashboard(user.id)
                    .map<DashboardSnapshot, DashboardUiState> { DashboardUiState.Ready(it) }
                    // A Room flow throws if the database is unreadable. Left unhandled it
                    // cancels the collector and the screen keeps showing the last good value
                    // forever, which reads as "your progress is 0" rather than as a failure.
                    .catch { throwable ->
                        emit(DashboardUiState.Failed(throwable.message ?: "Unknown error"))
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState.Loading)

    // Settings
    private val _isSlowTts = MutableStateFlow(false)
    val isSlowTts: StateFlow<Boolean> = _isSlowTts.asStateFlow()

    init {
        viewModelScope.launch {
            // Auto login or seed database
            userRepository.autoLogin()
        }
    }

    // Auth Operations
    /**
     * Drops any standing auth failure.
     *
     * Called when the learner switches between sign-in and register, or changes the
     * identifier type: a message about the attempt they just made is not a verdict on the
     * form they are now looking at, and leaving it up reads as "you got that wrong" about
     * fields they have not touched yet.
     */
    fun clearAuthError() {
        _authError.value = null
    }

    fun login(identifier: String, passwordPlain: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            when (val result = userRepository.login(identifier, passwordPlain)) {
                is AuthResult.Success -> {
                    _authLoading.value = false
                    onSuccess()
                }
                is AuthResult.Error -> {
                    _authLoading.value = false
                    _authError.value = result.message
                }
            }
        }
    }

    fun register(
        identifier: String,
        passwordPlain: String,
        displayName: String,
        isPhone: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            when (val result = userRepository.register(identifier, passwordPlain, displayName, isPhone)) {
                is AuthResult.Success -> {
                    // Seed initial starter pack for new registered user
                    seedUserData(result.user.id)
                    _authLoading.value = false
                    onSuccess()
                }
                is AuthResult.Error -> {
                    _authLoading.value = false
                    _authError.value = result.message
                }
            }
        }
    }

    fun loginAsGuest(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _authLoading.value = true
            val guest = userRepository.loginAsGuest()
            seedUserData(guest.id)
            _authLoading.value = false
            onSuccess()
        }
    }

    fun logout() {
        userRepository.logout()
    }

    private suspend fun seedUserData(userId: Long) {
        val existing = wordRepository.findWordByHanzi(userId, "学")
        if (existing == null) {
            val starterWords = listOf(
                Triple("学", "xué", "to study; to learn"),
                Triple("好", "hǎo", "good; fine"),
                Triple("你", "nǐ", "you")
            )
            starterWords.forEach { (hanzi, pinyin, meaning) ->
                wordRepository.saveNewWordWithInitialSrs(
                    NewWordDraft(
                        userId = userId,
                        hanzi = hanzi,
                        pinyin = pinyin,
                        meaning = meaning,
                        hskLevel = 1,
                        radical = "部首",
                        exampleCn = "你好，我在学习中文。",
                        examplePy = "Nǐ hǎo, wǒ zài xuéxí zhōngwén.",
                        exampleEn = "Hello, I am learning Chinese.",
                        strokeJson = "横, 竖, 撇, 捺",
                        source = StorageValues.VocabularySource.STARTER.storageValue,
                        provenance = StorageValues.ContentProvenance.CURATED.storageValue
                    ),
                    initialDueImmediate = true
                )
            }
        }
    }

    // AI Generation Operations
    fun generateWord(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _aiState.value = AiGenerationState.Error("Enter a Chinese character or pinyin first.")
            return
        }
        viewModelScope.launch {
            _wordSaveError.value = null
            _aiState.value = AiGenerationState.Loading
            val result = geminiService.generateChineseWordData(trimmed)
            result.fold(
                onSuccess = { data ->
                    _aiState.value = AiGenerationState.ReadyForReview(data)
                },
                onFailure = { error ->
                    // Real, actionable failures now reach the UI instead of being
                    // silently replaced with placeholder data.
                    _aiState.value = AiGenerationState.Error(
                        error.message ?: "Failed to generate word data"
                    )
                }
            )
        }
    }

    /**
     * Opt-in path for the built-in offline sample dictionary. Only ever called from an
     * explicit user action, and the result is tagged LOCAL_FALLBACK so the UI labels it.
     */
    fun useOfflineSampleFor(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _aiState.value = AiGenerationState.Error("Enter a Chinese character or pinyin first.")
            return
        }
        viewModelScope.launch {
            _aiState.value = AiGenerationState.ReadyForReview(geminiService.offlineSampleFor(trimmed))
        }
    }

    fun resetAiState() {
        _aiState.value = AiGenerationState.Idle
        _wordSaveError.value = null
    }

    // Approve & Save Word into SRS Database
    fun approveAndSaveWord(
        hanzi: String,
        pinyin: String,
        meaning: String,
        hskLevel: Int,
        radical: String,
        exampleCn: String,
        examplePy: String,
        exampleEn: String,
        strokeBreakdown: String,
        onComplete: () -> Unit
    ) {
        val user = currentUser.value ?: return
        val cleanHanzi = hanzi.trim()
        val cleanPinyin = pinyin.trim()
        val cleanMeaning = meaning.trim()
        if (cleanHanzi.isBlank() || cleanPinyin.isBlank() || cleanMeaning.isBlank()) {
            _wordSaveError.value = "Hanzi, pinyin, and meaning are required before saving."
            return
        }
        viewModelScope.launch {
            _isSavingWord.value = true
            _wordSaveError.value = null
            val word = NewWordDraft(
                userId = user.id,
                hanzi = cleanHanzi,
                pinyin = cleanPinyin,
                meaning = cleanMeaning,
                hskLevel = hskLevel,
                radical = radical,
                exampleCn = exampleCn,
                examplePy = examplePy,
                exampleEn = exampleEn,
                strokeJson = strokeBreakdown,
                tags = "HSK$hskLevel,Custom",
                source = StorageValues.VocabularySource.AI_GENERATED.storageValue,
                provenance = StorageValues.ContentProvenance.AI_GENERATED.storageValue
            )
            when (val result = wordRepository.saveNewWordWithInitialSrs(word, initialDueImmediate = true)) {
                is SaveWordResult.Saved -> {
                    _aiState.value = AiGenerationState.Idle
                    onComplete()
                }
                SaveWordResult.Duplicate -> {
                    _wordSaveError.value = "$cleanHanzi is already in your deck. Open it in the Library to review it."
                }
                is SaveWordResult.Invalid -> {
                    _wordSaveError.value = result.error.message
                }
            }
            _isSavingWord.value = false
        }
    }

    // SRS Review Flow
    /**
     * Grades the card the learner is looking at.
     *
     * The word is taken from the snapshot rather than from the argument's own identity, so what
     * gets written is unambiguously the card that was on screen. Two things are refused outright,
     * and both refusals used to be silent failures:
     *
     * - A card that has not been revealed cannot be rated. Swiping or tapping through a deck
     *   without ever attempting recall used to record a `GOOD` for every card, which wrote a real
     *   review, moved a real due date and paid real XP for a recall that never happened.
     * - A card whose write is still in flight cannot be rated again. Two taps used to write two
     *   reviews and advance the index twice, so the next card was skipped unasked.
     */
    /**
     * The interval [rating] would produce for [state], as the label the buttons show.
     *
     * A pure read with no write behind it, and it takes the current time once so all four
     * previews on a card are computed against the same instant - computed a few milliseconds
     * apart they could otherwise straddle a day boundary and disagree with each other.
     */
    fun previewInterval(state: SrsStateEntity?, rating: SrsRating): String {
        val now = System.currentTimeMillis()
        return srsRepository.previewNext(state, rating, now = now).nextReviewLabel
    }

    fun submitRating(wordWithSrs: WordWithSrs, rating: SrsRating) {
        val user = currentUser.value ?: return
        val deck = _reviewDeckState.value

        // The refused taps are no-ops, not errors: a learner double-tapping should not be told
        // they did something wrong, they should just see the deck behave as if the second tap
        // had not happened.
        // One atomic claim. `beginRating` returns null for a card that is not eligible - not
        // revealed, exhausted, or already being written - and falling back to the unchanged state
        // in that case leaves `isRating` false, so the check below refuses it. A separate
        // `canRate` test before the write would be a second, racy answer to the same question.
        val claimed = _reviewDeckState.updateAndGet { it.beginRating() ?: it }
        if (!claimed.isRating) return

        val currentId = deck.currentWordId
        if (currentId != null && wordWithSrs.word.id != currentId) {
            // A stale card from a deck that has since been replaced. Recorded as a failure rather
            // than rated, because rating it would write a review for a card nobody is looking at.
            _reviewDeckState.update { it.failRating() }
            _reviewError.value = "That card is no longer in this session."
            return
        }

        viewModelScope.launch {
            // Awaited, not read: a learner can rate the first card before the opening write has
            // returned, and an answer logged with no session id belongs to no sitting and can
            // never be counted towards `SESSIONS_COMPLETED`.
            val sessionId = activeSession?.await()

            // A failed write must not advance the deck or crash the app: surface it
            // and leave the card in place so the learner can retry.
            // The session id goes into the log entry itself, not only onto the session card.
            when (
                val outcome = srsRepository.processReview(
                    userVocabularyId = wordWithSrs.word.id,
                    userId = user.id,
                    rating = rating,
                    sessionId = sessionId
                )
            ) {
                is ReviewOutcome.Recorded -> {
                    _reviewError.value = null
                    // The session card is written from the schedule the write actually produced,
                    // not from a prediction made before it, so the summary at the end of the
                    // sitting reports the real next interval.
                    if (sessionId != null) {
                        val schedule = srsRepository.getReviewForWord(wordWithSrs.word.id)
                        if (schedule != null) {
                            sessionRepository.recordAnswer(
                                sessionId = sessionId,
                                userId = user.id,
                                // The shared content id, which is what `session_cards` keys on.
                                // The enrolment is what `srs_state` and the answer above are keyed
                                // on, and the two are different rows with different keys.
                                vocabularyId = wordWithSrs.word.vocabularyId,
                                rating = rating,
                                answeredAt = System.currentTimeMillis(),
                                responseTimeMillis = 0,
                                schedule = schedule
                            )
                        }
                    }
                    // One transition moves the index, records the answer and releases the
                    // in-flight claim together. Incrementing the index by hand alongside a
                    // separate `isRating` flag is how the two came to disagree.
                    val advanced = _reviewDeckState.updateAndGet { it.completeRating(rating) }
                    _currentDeckIndex.value = advanced.index
                    _isCardFlipped.value = advanced.revealed
                    _reviewedSessionCount.value = advanced.answersGiven
                }
                is ReviewOutcome.Rejected -> {
                    Log.w("MainViewModel", "Review rejected for ${wordWithSrs.word.id}: ${outcome.failure}")
                    // The claim is released and the index is left alone, so the learner stays on
                    // the card and the answer they gave is still theirs to submit.
                    _reviewDeckState.update { it.failRating() }
                    _reviewError.value = when (val failure = outcome.failure) {
                        is ReviewFailure.NotEnrolled -> failure.message
                        is ReviewFailure.InvalidResult -> failure.error.message
                    }
                }
            }
        }
    }

    /**
     * Returns the deck to its first card without discarding it.
     *
     * Clears the reveal and the counts but keeps the snapshot, so "start again" and "the deck is
     * still being opened" both land in a state the screen can render rather than on a null.
     */
    fun resetDeckSession() {
        // `start` with the ids it already holds, which is a reset of everything derived from
        // them: index, reveal, per-card answers and the in-flight claim. Deliberately keeps the
        // snapshot, so "start again" and "the deck is still opening" both land on card one of the
        // same sitting rather than on an empty deck.
        _reviewDeckState.update { it.start(it.wordIds) }
        _currentDeckIndex.value = 0
        _isCardFlipped.value = false
        _reviewedSessionCount.value = 0
    }

    /** Moves to the next card, releasing the reveal on the way. */
    fun goToNextCard() {
        val next = _reviewDeckState.updateAndGet { it.next() }
        _currentDeckIndex.value = next.index
        _isCardFlipped.value = next.revealed
    }

    /**
     * Moves back to the previous card.
     *
     * A previous card that was already answered shows its answer rather than its prompt, because
     * re-rating it would write a second review for one sitting, and the scheduler's answer to
     * that is to skip the card entirely.
     */
    fun goToPreviousCard() {
        _reviewDeckState.update { it.previous() }
        _currentDeckIndex.value = _reviewDeckState.value.index
        val state = _reviewDeckState.value
        _isCardFlipped.value = state.revealed || state.currentAnswer != null
    }

    /**
     * Flips the current card.
     *
     * A card that has already been answered opens on its answer, so stepping backwards and
     * forwards shows a consistent thing for the same card.
     */
    fun flipCard() {
        val next = _reviewDeckState.updateAndGet { it.toggleReveal() }
        _isCardFlipped.value = next.revealed
    }

    /** Clears a surfaced review failure, so a snackbar does not reappear on the next frame. */
    fun clearReviewError() {
        _reviewError.value = null
    }

    /**
     * Restarts a sitting over what is due *now*, queried fresh.
     *
     * The end-of-session button used to hand [startReviewSession] the `dueWords` value the
     * screen happened to be holding. That value is a snapshot of a flow emission: the
     * reviews the learner just wrote have to round-trip through Room and back into the
     * flow before it reflects them, so depending on timing the button either presented the
     * deck that had just been reviewed — which is not "what is still due" — or, once the
     * flow had caught up and the list was legitimately empty, started an empty sitting and
     * showed the screen's nothing-to-review state as though something had gone wrong.
     *
     * Querying here, rather than reading a flow the screen was already collecting, is what
     * makes the label true. If nothing is due the deck is left closed and
     * [nothingLeftToReview] is set, so the screen can say so; nothing is written.
     */
    fun restartReviewSession() {
        val user = currentUser.value ?: return
        viewModelScope.launch {
            val due = wordRepository.getDueWordsForUserOnce(user.id)
            if (due.isEmpty()) {
                _reviewDeck.value = emptyList()
                _reviewSessionWordIds.value = emptyList()
                _reviewDeckState.value = ReviewDeckState(emptyList())
                _sessionSummary.value = null
                _sessionAwards.value = emptyList()
                _nothingLeftToReview.value = true
                return@launch
            }
            _nothingLeftToReview.value = false
            clearSessionSummary()
            startReviewSession(due)
        }
    }

    /**
     * Starts a sitting over [words].
     *
     * The words are snapshotted here and not looked up again, which is the whole reason the deck
     * does not move under the learner. A rating changes `srs_state`, which changes the collection
     * flow, and a deck re-derived from that flow can present a different word at the index the
     * learner is looking at - so the tap they make records a review for a card they never saw.
     */
    fun startReviewSession(words: List<WordWithSrs>) {
        val snapshot = words.toList()
        _reviewDeck.value = snapshot
        _reviewSessionWordIds.value = snapshot.map { it.word.id }
        _reviewDeckState.value = ReviewDeckState(snapshot.map { it.word.id })
        // A real sitting is under way, so the "nothing left" answer no longer applies.
        _nothingLeftToReview.value = false
        resetDeckSession()
        openSession(snapshot)
    }

    /**
     * Opens a persisted session for the deck, replacing any the learner left open.
     *
     * Recorded rather than held in memory because two things outlive the screen: a session has to
     * be a countable fact for a badge to hang on, and a learner who leaves the app mid-deck and
     * comes back should reattach to the sitting they started rather than begin a second one. The
     * answers are linked to this id, so it is also what makes the session summary possible at all.
     */
    private fun openSession(words: List<WordWithSrs>) {
        val user = currentUser.value ?: return
        val vocabularyIds = words.map { it.word.vocabularyId }.distinct()
        // Started eagerly and stored as a Deferred so a rating tapped immediately after can await
        // the id rather than race past it. `async` rather than `launch` because the value is the
        // point; the coroutine here is a write to run, not a fire-and-forget side effect.
        activeSession = viewModelScope.async {
            sessionRepository.begin(
                userId = user.id,
                type = StorageValues.SessionType.REVIEW,
                vocabularyIds = vocabularyIds,
                now = System.currentTimeMillis()
            )
        }
    }

    /**
     * Settles the deck for a screen that opened without one.
     *
     * Notifications can open the review screen directly, so it has to be able to start its
     * own sitting. The important half of this is the *empty* case.
     *
     * The old guard was `if (ids == null && words.isNotEmpty())`, which meant a learner with
     * no words never resolved `reviewSessionWordIds` at all. The screen's first branch is
     * `!wordsLoaded || reviewSessionWordIds == null` — the loading state — so that learner
     * watched a spinner for as long as they cared to, and so did anyone whose `currentUser`
     * had not resolved yet. The guard that was waiting for the answer was the guard showing
     * the spinner.
     *
     * An empty list here means "we have looked and there is nothing", which is a different
     * statement from the `null` that means "we have not looked yet", and the screen needs
     * to be able to tell them apart.
     */
    fun ensureReviewSession(words: List<WordWithSrs>) {
        if (_reviewSessionWordIds.value != null) return
        if (words.isNotEmpty()) {
            startReviewSession(words)
        } else {
            _reviewSessionWordIds.value = emptyList()
            _nothingLeftToReview.value = true
        }
    }

    // Audio TTS
    fun playWordAudio(hanzi: String) {
        ttsHelper.speak(hanzi, if (_isSlowTts.value) 0.65f else 0.90f)
    }

    fun playSentenceAudio(sentence: String) {
        ttsHelper.speak(sentence, if (_isSlowTts.value) 0.70f else 0.95f)
    }

    fun toggleSlowTts() {
        _isSlowTts.value = !_isSlowTts.value
        ttsHelper.setSpeed(_isSlowTts.value)
    }

    // Notification Trigger
    fun sendDueReminderNotification() {
        val count = dueCount.value
        NotificationHelper.showDueWordsNotification(getApplication(), count)
    }

    // Delete Word
    fun deleteWord(userVocabularyId: Long) {
        val user = currentUser.value ?: return
        viewModelScope.launch {
            wordRepository.deleteWord(userVocabularyId, user.id)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsHelper.shutdown()
    }
}
