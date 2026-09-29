package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiAiService
import com.example.data.ai.GeneratedWordData
import com.example.data.auth.SessionStore
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
import com.example.audio.AndroidTtsPronunciationProvider
import com.example.audio.PronunciationService
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
    // The session store is what lets the learner stay signed in across launches. It is passed
    // in here rather than built inside the repository because only the ViewModel has a Context,
    // and a repository that reached for one could not be unit-tested against an in-memory
    // database.
    val userRepository = UserRepository(
        userDao = database.userDao(),
        database = database,
        sessionStore = SessionStore(application)
    )
    val wordRepository = WordRepository(database)
    val srsRepository = SrsRepository(database)
    private val dashboardRepository = DashboardRepository(database)
    private val gamificationRepository = GamificationRepository(database)
    private val sessionRepository = StudySessionRepository(database)
    private val geminiService = GeminiAiService()

    /**
     * The app's one audio engine.
     *
     * Held here because the ViewModel is the only object with a lifecycle that spans every
     * screen, and a TTS engine is expensive to create and is wanted by the deck, the library,
     * the home screen and the add-word preview at the same time. Two engines would also mean
     * two voices talking over each other, and the [PronunciationService] state machine — which
     * is what associates a playing indicator with a specific word — assumes a single speaker.
     *
     * Public because a [com.example.audio.PronunciationButton] binds to it. Exposed as the
     * service rather than as a `speak(text)` method so the state, the replay and the failure
     * reasons reach the screen; the old `playWordAudio(hanzi)` signature could carry none of
     * them, which is why it reported success by not failing.
     */
    val pronunciationService = PronunciationService(AndroidTtsPronunciationProvider(application))

    val currentUser: StateFlow<UserEntity?> = userRepository.currentUser

    // Observe all words for current user
    //
    // The `.catch` on all three collection flows below is not defensive noise. Room throws out of
    // a flow when the database becomes unreadable - a full disk, a corrupt file, a revoked
    // permission - and an exception escaping a `stateIn` sharing coroutine is uncaught, because
    // `viewModelScope` is not supervised for it. The app dies. There is no error variant on a
    // `List` flow to render into, so the honest degradation is an empty collection; the log
    // carries the reason, since a learner cannot act on a SQLITE_ code.
    val userWords: StateFlow<List<WordWithSrs>> = currentUser.flatMapLatest { user ->
        if (user != null) {
            wordRepository.getWordsForUser(user.id)
                .catch { throwable ->
                    Log.w("MainViewModel", "collection read failed", throwable)
                    emit(emptyList())
                }
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
                .catch { throwable ->
                    Log.w("MainViewModel", "due-words read failed", throwable)
                    emit(emptyList())
                }
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Observe due count for badge and notification payload
    //
    // Deliberately NOT `dueWords.map { it.size }`. That asked the most expensive query in the
    // codebase to answer the only question the badge draws, and the badge is subscribed at the
    // app container, so the cost was being paid on every screen and on every review write. The
    // count is an index range count whose cost is independent of library size, and it still
    // re-evaluates on the minute clock so a card falling due reaches the badge.
    val dueCount: StateFlow<Int> = currentUser.flatMapLatest { user ->
        if (user != null) {
            wordRepository.observeDueCount(user.id)
                .catch { throwable ->
                    Log.w("MainViewModel", "due-count read failed", throwable)
                    emit(0)
                }
        } else {
            flowOf(0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
                    //
                    // The detail is logged, never displayed. `throwable.message` for a Room
                    // failure is either a bare SQLITE_ code or a full file path, and the UI
                    // prints this string verbatim under its "Try again" button. The progress
                    // surface on the same screen already did the right thing here; the two
                    // were inconsistent.
                    .catch { throwable ->
                        Log.w("MainViewModel", "dashboard read failed", throwable)
                        emit(DashboardUiState.Failed("Your dashboard could not be read."))
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState.Loading)

    // Settings
    // `isSlowTts` is declared with the rest of the audio API near the bottom, because it is
    // the service's state rather than this class's.

    private val _sessionRestored = MutableStateFlow(false)

    /**
     * Whether the launch-time session check has finished, either way.
     *
     * False means "not known yet", which is different from both "signed in" and "signed out" and
     * must not be rendered as either. True means [currentUser] is now a trustworthy answer.
     */
    val sessionRestored: StateFlow<Boolean> = _sessionRestored.asStateFlow()

    init {
        viewModelScope.launch {
            // Restore the session this device held at the last sign-in.
            //
            // `sessionRestored` is what makes doing this asynchronously safe. The UI decides
            // between the sign-in screen and the app by whether `currentUser` is null, and
            // `currentUser` starts null - so without the flag the gate sees "signed out" during
            // the window before the query returns, wipes the back stack, and shows the sign-in
            // form to a learner who is in fact signed in. And because only `onAuthSuccess` ever
            // navigates away from that screen, nothing would take them back out.
            //
            // Set in a `finally` so a database failure here lands on the sign-in screen, which
            // is recoverable, rather than on a spinner that never resolves.
            try {
                userRepository.autoLogin()
            } catch (failure: Exception) {
                Log.w("MainViewModel", "session restore failed; falling back to sign-in", failure)
                userRepository.logout()
            } finally {
                _sessionRestored.value = true
            }
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

    /**
     * Gives a brand new account its three starter characters, exactly once.
     *
     * ### Why the check is "has this account any words at all"
     *
     * It used to be `findWordByHanzi(userId, "学")` — that a *single* character is present. That
     * asks the wrong question: it treats "this learner still owns 学" as proof that seeding
     * already happened, so a learner who removed 学 got the whole starter pack handed back on
     * their next sign-in, with no way to keep it out. The account's enrolment count answers the
     * question actually being asked, needs no schema change, and has the defensible edge
     * behaviour that someone who deliberately empties their library is a beginner again.
     */
    private suspend fun seedUserData(userId: Long) {
        // The first-run seed writes the `learning_levels` catalogue asynchronously, and
        // `vocabulary.levelId` is a RESTRICT foreign key onto it. Writing a starter word before
        // that row exists is refused, not stored, so a learner who registered in that window
        // silently got an empty collection. Waiting is bounded - see
        // `AppDatabase.awaitReferenceCatalogue` -
        // so a seed that never signals costs a delay rather than a hang.
        AppDatabase.awaitReferenceCatalogue()

        if (database.userVocabularyDao().countForUser(userId) > 0) return

        // Real stroke orders, in the `name (reading)` form StrokeNameParser reads, in standard
        // writing order: 学 has eight strokes, 好 six, 你 seven. The previous value here was the
        // literal "横, 竖, 撇, 捺" for all three, which asserted a four-stroke character for
        // every one of them and, being in neither the name nor the `name (reading)` form, parsed
        // to nothing anyway. A wrong stroke count is worse than an absent one: the app would
        // animate someone else's writing as though it were correct.
        val starterWords = listOf(
            StarterWord(
                hanzi = "学",
                pinyin = "xué",
                meaning = "to study; to learn",
                strokes = "点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横钩 (Héng Gōu), " +
                    "横撇 (Héng Piě), 弯钩 (Wān Gōu), 横 (Héng)"
            ),
            StarterWord(
                hanzi = "好",
                pinyin = "hǎo",
                meaning = "good; fine",
                strokes = "撇点 (Piě Diǎn), 撇 (Piě), 横 (Héng), 横撇 (Héng Piě), " +
                    "弯钩 (Wān Gōu), 横 (Héng)"
            ),
            StarterWord(
                hanzi = "你",
                pinyin = "nǐ",
                meaning = "you",
                strokes = "撇 (Piě), 竖 (Shù), 撇 (Piě), 横钩 (Héng Gōu), " +
                    "竖钩 (Shù Gōu), 撇 (Piě), 点 (Diǎn)"
            )
        )

        // One real sentence, shared: 你好，我在学习中文。 is a natural sentence that happens to
        // contain all three starter characters, which is why it was chosen, and reusing it keeps
        // the example honest rather than inventing three.
        starterWords.forEach { starter ->
            val result = wordRepository.saveNewWordWithInitialSrs(
                NewWordDraft(
                    userId = userId,
                    hanzi = starter.hanzi,
                    pinyin = starter.pinyin,
                    meaning = starter.meaning,
                    hskLevel = 1,
                    // Left empty rather than filled with a placeholder. It used to hold the
                    // literal string "部首" - the Chinese *word* for "radical" - which the
                    // library screen rendered as if it were this character's radical. An absent
                    // radical is a blank cell; a fabricated one is a false statement.
                    radical = "",
                    exampleCn = "你好，我在学习中文。",
                    examplePy = "Nǐ hǎo, wǒ zài xuéxí zhōngwén.",
                    exampleEn = "Hello, I am learning Chinese.",
                    strokeJson = starter.strokes,
                    source = StorageValues.VocabularySource.STARTER.storageValue,
                    provenance = StorageValues.ContentProvenance.CURATED.storageValue
                ),
                initialDueImmediate = true
            )
            // A refused save is silent otherwise, and the learner sees a missing starter
            // character with nothing to explain it.
            if (result is SaveWordResult.Invalid) {
                Log.w("MainViewModel", "starter word ${starter.hanzi} was refused: ${result.error}")
            }
        }
    }

    private data class StarterWord(
        val hanzi: String,
        val pinyin: String,
        val meaning: String,
        val strokes: String
    )

    // AI Generation Operations

    /**
     * Identifies the most recent generation request, so a slow reply cannot overwrite a newer one.
     *
     * The service has a 30 second connect, read and write timeout, so the window in which two
     * requests overlap is wide. Press Search for 学, edit the field to 猫, press Search again: both
     * are in flight, and whichever returns *last* used to win regardless of which was asked for
     * second. The learner would then be looking at 学 while believing they had asked for 猫, and
     * would approve and save the wrong word.
     */
    private var latestAiRequest: Long = 0

    fun generateWord(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _aiState.value = AiGenerationState.Error("Enter a Chinese character or pinyin first.")
            return
        }
        // Claimed before the launch, on the calling thread, so two taps in the same frame cannot
        // both believe they are the newest.
        val requestId = ++latestAiRequest
        viewModelScope.launch {
            _wordSaveError.value = null
            _aiState.value = AiGenerationState.Loading
            val result = geminiService.generateChineseWordData(trimmed)
            // A superseded request is dropped silently. Reporting its outcome would replace the
            // learner's newer question with an error about a character they are no longer asking
            // about.
            if (requestId != latestAiRequest) {
                Log.i("MainViewModel", "dropping superseded AI request $requestId for '$trimmed'")
                return@launch
            }
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
     *
     * A miss is reported, not filled in. This used to return a plausible `GeneratedWordData` for
     * any query at all - a fixed reading, a fixed radical, a fixed five-stroke breakdown that
     * described no character in particular - and the caller saved it as model output.
     */
    fun useOfflineSampleFor(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _aiState.value = AiGenerationState.Error("Enter a Chinese character or pinyin first.")
            return
        }
        // Any generation in flight is now moot; this is the answer the learner asked for.
        latestAiRequest++
        val sample = geminiService.offlineSampleFor(trimmed)
        _aiState.value = if (sample != null) {
            AiGenerationState.ReadyForReview(sample)
        } else {
            AiGenerationState.Error(
                "No offline sample for \"$trimmed\". Try another character, or add it by hand."
            )
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
        //
        // One atomic claim, and the refusal is decided by comparing before and after rather than
        // by reading `isRating` once. `beginRating` returns null for three different reasons -
        // not revealed, no card, and a write already in flight - and only the first two leave
        // `isRating` false. The third returns the state unchanged with `isRating` *still true*,
        // so the old "fall back to the unchanged state and check `isRating`" guard read `true`
        // and let the double tap straight through: two `processReview` calls, two `review_log`
        // rows, two index advances, and the next card skipped unasked. The state machine's own
        // test proves it refuses; the caller was throwing that answer away.
        val before = _reviewDeckState.value
        val claimed = _reviewDeckState.updateAndGet { it.beginRating() ?: it }
        if (!claimed.isRating || before.isRating) return

        val currentId = deck.currentWordId
        if (currentId != null && wordWithSrs.word.id != currentId) {
            // A stale card from a deck that has since been replaced. Recorded as a failure rather
            // than rated, because rating it would write a review for a card nobody is looking at.
            _reviewDeckState.update { it.failRating() }
            _reviewError.value = "That card is no longer in this session."
            return
        }

        viewModelScope.launch {
            // `processReview` runs inside `withTransaction`, which is exactly where a
            // `SQLiteConstraintException` or a `SQLiteDiskIOException` surfaces. Unhandled, that
            // exception both crashes the app and leaves the in-flight claim set, and with it
            // `canRate` false for good - `reveal`, `next` and `previous` all no-op while
            // `isRating` - so the deck is permanently unusable and a restart is the only escape.
            // The comment below used to promise the first half of that was handled; it was not.
            try {
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
                                    // The enrolment is what `srs_state` and the answer above are
                                    // keyed on, and the two are different rows with different keys.
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
                        Log.w(
                            "MainViewModel",
                            "Review rejected for ${wordWithSrs.word.id}: ${outcome.failure}"
                        )
                        // The claim is released and the index is left alone, so the learner stays
                        // on the card and the answer they gave is still theirs to submit.
                        _reviewDeckState.update { it.failRating() }
                        _reviewError.value = when (val failure = outcome.failure) {
                            is ReviewFailure.NotEnrolled -> failure.message
                            is ReviewFailure.InvalidResult -> failure.error.message
                        }
                    }
                }
            } catch (failure: Exception) {
                // Release the claim so the deck is still usable, and leave the index alone so the
                // learner's answer is still theirs to submit. Deliberately not the exception's
                // own message: this is a Room failure and its text is either a raw SQLITE_ code
                // or a file path, neither of which means anything to the person reading it.
                Log.w("MainViewModel", "review write failed for ${wordWithSrs.word.id}", failure)
                _reviewDeckState.update { it.failRating() }
                _reviewError.value = "That answer could not be saved. Please try again."
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
        // A new sitting has no results yet. Without this, the previous one's summary and awards
        // survive: leave the deck from the top bar, tap Start Learning again, and if the new
        // deck turns out to be empty the completion screen renders the *last* sitting's badges
        // and its hard-coded "Session complete" above "No cards were answered in this session" -
        // congratulating a learner for a session that did not happen. `restartReviewSession`
        // already cleared it; this is the other entry point and it did not.
        clearSessionSummary()
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

    // Audio
    //
    // The rate lives in PronunciationService and is applied to each request at the moment it is
    // issued. It used to be a field here *and* an argument to every `speak(...)` call, with the
    // two disagreeing: `toggleSlowTts` set 0.65/0.90 and the very next `playWordAudio` overwrote
    // it with a value chosen at that call site. The slow toggle worked only when one happened
    // to run last, which is not a property anyone can rely on.
    //
    // `isSlowTts` reads through to the service rather than holding a second copy, so the
    // settings switch and the engine cannot disagree about the current rate.

    val isSlowTts: StateFlow<Boolean> get() = pronunciationService.isSlowTtsFlow

    fun toggleSlowTts() {
        pronunciationService.setSlow(!pronunciationService.isSlowTts)
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
        pronunciationService.shutdown()
    }
}
