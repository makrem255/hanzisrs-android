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
import com.example.data.review.RandomWordChoice
import com.example.data.review.pickRandomWord
import com.example.data.review.updateRecentHistory
import com.example.data.settings.UiPreferencesStore
import com.example.data.srs.ReviewDeckState
import com.example.data.srs.SrsRating
import com.example.util.NotificationHelper
import com.example.audio.AndroidTtsPronunciationProvider
import com.example.audio.PronunciationService
import com.example.audio.UiSound
import com.example.audio.UiSoundPlayer
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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


/**
 * What Random Review can be showing.
 *
 * The same sealed shape as [DashboardUiState] and [ProgressUiState], for the same reason: a
 * screen that is handed a nullable word and a separate `isLoading` flag has to invent the
 * difference between "nothing yet" and "nothing at all", and the two failure modes look
 * identical to a learner who is waiting - one of which tells them they have no vocabulary when
 * in fact the query has not answered.
 *
 * There is deliberately no "no learned vocabulary" member. When nothing has been graded yet the
 * selection falls back to the learner's whole collection and [Showing] arrives carrying
 * [com.example.data.review.RandomWordPool.AllEnrolled], which the screen reports as a note on a
 * working feature. Sending a learner with twenty enrolled words to an empty state about studying
 * first would be accurate, unhelpful, and the answer to a question they did not ask.
 */
sealed class RandomReviewUiState {
    /** The vocabulary collection has not resolved yet, or a pick has not been made from it. */
    object Loading : RandomReviewUiState()

    /** The learner is not enrolled in any word, so there is nothing to draw from. */
    object NoVocabulary : RandomReviewUiState()

    /** The word on screen. */
    data class Showing(
        val choice: RandomWordChoice,
        val revealed: Boolean,
        val wordsShown: Int,
        /** Valid pronunciation attempts against this word. Technical failures never count. */
        val attempts: Int,
    ) : RandomReviewUiState()

    /** The read failed. The message is fixed and safe to show; it is never a thrown message. */
    data class Failed(val message: String) : RandomReviewUiState()
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
    // Private, and deliberately so. These were `val`s with default (public) visibility, which
    // published seven repositories as the ViewModel's API. Grepping the whole source tree found
    // no reader of any of them outside this class - every test constructs its own repository
    // against its own in-memory database, and no screen reaches past the ViewModel at all. So
    // the visibility was not buying a seam; it was an invitation to skip the ViewModel, and
    // `viewModel.srsRepository.processReview(...)` from a composable would have bypassed the
    // deck's rating-claim guard without anything making that fail to compile.
    private val userRepository = UserRepository(
        userDao = database.userDao(),
        database = database,
        sessionStore = SessionStore(application)
    )
    private val wordRepository = WordRepository(database)
    private val srsRepository = SrsRepository(database)
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

    /**
     * Set when the learner's collection could not be read, cleared as soon as it can.
     *
     * `null` means the read is fine — including the perfectly ordinary case of a learner who has
     * no words. A message means the database threw: a full disk, a corrupt file, a revoked
     * permission. Those are very different situations and [userWords] cannot express the
     * difference, because the only honest degradation available on a `List` flow is to emit an
     * empty one.
     *
     * Which is exactly the bug this fixes. Without it, a corrupt database told a learner whose
     * data was perfectly intact that their vocabulary was empty — "ready for its first word",
     * alongside an invitation to go and re-type it. An empty list is rendered as a starting point;
     * it is not evidence that the learner has no words, and on the failure path it was claiming
     * so. The screen now has a third state to draw instead.
     */
    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError.asStateFlow()

    // Observe all words for current user
    //
    // The `.catch` on all three collection flows below is not defensive noise. Room throws out of
    // a flow when the database becomes unreadable - a full disk, a corrupt file, a revoked
    // permission - and an exception escaping a `stateIn` sharing coroutine is uncaught, because
    // `viewModelScope` is not supervised for it. The app dies. There is no error variant on a
    // `List` flow to render into, so the honest degradation is an empty collection; the log
    // carries the reason, since a learner cannot act on a SQLITE_ code.
    //
    // `onEach` ahead of `catch` so a recovery clears the flag. Without it the message would be a
    // latch set by the worst thing that ever happened and never unset, which is worse than not
    // having it: the screen would keep reporting a failure for a database that now reads fine.
    val userWords: StateFlow<List<WordWithSrs>> = currentUser.flatMapLatest { user ->
        if (user != null) {
            wordRepository.getWordsForUser(user.id)
                .onEach { _libraryError.value = null }
                .catch { throwable ->
                    Log.w("MainViewModel", "collection read failed", throwable)
                    // Fixed sentence, never `throwable.message`. A Room or SQLite message is a
                    // `SQLITE_IOERR` code or an absolute file path; neither means anything to
                    // the learner reading it and the second discloses where their data lives.
                    _libraryError.value = "Your vocabulary could not be read. Your words are " +
                        "still saved — try again in a moment."
                    emit(emptyList())
                }
        } else {
            // Signing out clears it. A read failure belonged to the account that has now gone,
            // and carrying it across a sign-out would greet the next learner with a message about
            // a database they have never touched — including a guest who signed in on the same
            // device. The same applies on the way back in: `onEach` clears it on the first
            // successful read either way.
            _libraryError.value = null
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
     *
     * Read [libraryError] alongside it: a third cause, "the query failed", was rendered as the
     * empty state, which told a learner whose words were intact that they had none.
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

    /**
     * The deck, as one value.
     *
     * The index, the reveal and the answer count used to be published as three more
     * `MutableStateFlow`s that had to be written by hand at every single transition — five sync
     * sites, each of which could be forgotten. The result was a class of bug where the card on
     * screen and the machine tracking it disagreed, and the two bugs fixed in the last audit
     * (§2.10 and §2.11) both lived in the gap between them. The screen now reads `index`,
     * `revealed`, `isFinished`, `progress` and `positionLabel` off this one object, so a
     * transition cannot half-apply.
     *
     * That list is the screen's readers and nothing more, checked by grep rather than recalled.
     * It previously also named `answersGiven`, which the screen does not read: the answer count
     * is held inside [ReviewDeckState] and consumed by [canRate] and `completeRating`, and a
     * learner never sees a number of answers on the deck. The state object has more members than
     * the screen needs, which is normal for a state machine — the screen is not its only
     * legitimate reader, it is simply the only external one.
     */
    val reviewDeckState: StateFlow<ReviewDeckState> = _reviewDeckState.asStateFlow()

    /** The words in this sitting, in the order they will be shown. Fixed when the deck starts. */
    private val _reviewDeck = MutableStateFlow<List<WordWithSrs>>(emptyList())
    val reviewDeck: StateFlow<List<WordWithSrs>> = _reviewDeck.asStateFlow()

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
            clearSessionSummary()
            return
        }
        activeSession = null

        viewModelScope.launch {
            // Every step below is a database call, and none of them are individually guarded.
            //
            // `launch` reports an uncaught failure to the CoroutineExceptionHandler, which for
            // this scope means the thread's default handler and the end of the process — the
            // `async` sibling stored in [activeSession] does not, which is the whole difference
            // between the two and the reason this was easy to miss. A full disk, or a revoked
            // permission, between opening a deck and finishing it took the app down at exactly
            // the moment the learner was looking for their results.
            //
            // The session row is committed by the time the failure could land, so the honest
            // outcome is to show no summary rather than a wrong one: the answers are safe in
            // `review_log` and will be counted next time. Deliberately not `failure.message`,
            // which is a `SQLITE_` code or a file path.
            try {
                val sessionId = session.await() ?: run {
                    clearSessionSummary()
                    return@launch
                }

                val closed = sessionRepository.end(
                    sessionId = sessionId,
                    userId = user.id,
                    status = StorageValues.SessionStatus.COMPLETED,
                    now = now
                )
                // Only report a session that actually recorded answers. A deck the learner
                // opened and closed has a row and no history, and a summary claiming
                // "0 answered, 100% correct" is the kind of number that makes the rest of the
                // app untrustworthy.
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
                    clearSessionSummary()
                }
                refreshProgress()
            } catch (failure: Exception) {
                Log.w("MainViewModel", "closing the session failed", failure)
                clearSessionSummary()
                _reviewError.value = "This session could not be closed properly. Your answers " +
                    "were still saved."
            }
        }
    }

    /**
     * Clears the session result, so leaving the screen does not replay it on the way back in.
     *
     * The summary and the award list are cleared together and never separately. Three other sites
     * in this class used to spell out the pair inline, and `restartReviewSession` spelled it
     * inline in one branch while calling this method two lines later in the other. That is not a
     * style objection: §2.12 was exactly this bug — one entry point that forgot, so the previous
     * sitting's badges were drawn on top of a brand new deck.
     */
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

        // Watch the collection for enrolments that vanish while a deck is open. Collected off
        // `userWords` rather than called once, because the deletion usually happens *after* the
        // deck was built — a learner cannot be on the review screen and the library at the same
        // time, so this can only be caught by reacting to the change.
        //
        // `drop(1)` because the first emission is the collection as it stood when this started,
        // which is the one the deck was already built from; acting on it would be a no-op at
        // best and, if a deck were somehow open at construction, a race against `stateIn`'s
        // initial `emptyList()` — skipping every card in it.
        viewModelScope.launch {
            userWords.drop(1).collect { skipVanishedCards() }
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

    /**
     * Runs one auth attempt, owning the loading flag, the error slot, and the exception path.
     *
     * All three entry points below used to spell this out for themselves, and all three got the
     * same thing subtly wrong: they set `_authLoading = false` on the *success* and *error*
     * branches only. Every one of them ends in a `withTransaction`, so a `SQLiteDiskIOException`
     * or a full disk escapes the repository, skips both branches, and leaves the flag set for
     * good. The sign-in form is then disabled with nothing loading and nothing to cancel — the
     * only recovery is killing the app. The flag is therefore cleared in `finally`.
     *
     * The thrown case gets a fixed sentence, never `failure.message`. A Room or SQLite message
     * is a `SQLITE_IOERR` code or an absolute file path; both are noise to a learner and, in
     * the path case, a disclosure.
     */
    private fun runAuthAttempt(attempt: suspend () -> AuthResult, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _authLoading.value = true
            _authError.value = null
            val result = try {
                attempt()
            } catch (failure: Exception) {
                Log.w("MainViewModel", "auth attempt failed", failure)
                AuthResult.Error("Something went wrong signing you in. Please try again.")
            } finally {
                _authLoading.value = false
            }
            when (result) {
                is AuthResult.Success -> onSuccess()
                is AuthResult.Error -> _authError.value = result.message
            }
        }
    }

    fun login(identifier: String, passwordPlain: String, onSuccess: () -> Unit) {
        runAuthAttempt({ userRepository.login(identifier, passwordPlain) }, onSuccess)
    }

    fun register(
        identifier: String,
        passwordPlain: String,
        displayName: String,
        isPhone: Boolean,
        onSuccess: () -> Unit
    ) {
        runAuthAttempt(
            attempt = {
                userRepository.register(identifier, passwordPlain, displayName, isPhone)
                    .also { result ->
                        // Seeded inside the attempt, so a failure to seed is reported as one
                        // rather than handing the learner a signed-in account with no words and
                        // no error on screen. Seeding itself is already total — it reports
                        // refusal by returning, and says so in the log.
                        if (result is AuthResult.Success) seedUserData(result.user.id)
                    }
            },
            onSuccess = onSuccess,
        )
    }

    fun loginAsGuest(onSuccess: () -> Unit) {
        runAuthAttempt(
            attempt = {
                userRepository.loginAsGuest().also { result ->
                    if (result is AuthResult.Success) seedUserData(result.user.id)
                }
            },
            onSuccess = onSuccess,
        )
    }

    fun logout() {
        userRepository.logout()
        // Snapshot flows do not re-derive from currentUser the way the collection queries do
        // (flatMapLatest), so without this the next account to open the deck would see the
        // previous account's cards, summary and awards.
        _reviewDeck.value = emptyList()
        _reviewDeckState.value = ReviewDeckState(emptyList())
        _reviewSessionWordIds.value = null
        _sessionSummary.value = null
        _sessionAwards.value = emptyList()
        _nothingLeftToReview.value = false
        _reviewError.value = null
        _randomReviewSession.value = null
        _pendingSelection.value = null
        recentRandomIds = emptyList()
        pronunciationService.stop()
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

    /**
     * Generates word data for [query]: online first, offline sample as fallback.
     *
     * The order matters and was the "老师" bug: the offline dictionary used to be reachable
     * only through a separate button, so when the AI backend was unconfigured the learner got
     * an instant failure and then, via the fallback button, a second failure about a missing
     * sample - for a word the online attempt never ran for. Now one press tries the AI flow
     * when configured, falls back to a matching sample, and only then reports an error that
     * names both halves truthfully.
     */
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
            when (
                val outcome = com.example.data.ai.resolveGeneration(trimmed, result) {
                    geminiService.offlineSampleFor(it)
                }
            ) {
                is com.example.data.ai.GenerationOutcome.Ready ->
                    _aiState.value = AiGenerationState.ReadyForReview(outcome.data)
                is com.example.data.ai.GenerationOutcome.Failed ->
                    _aiState.value = AiGenerationState.Error(outcome.message)
            }
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

        val currentId = claimed.currentWordId
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
                        // in-flight claim together. The index, the reveal and the answer count
                        // used to be three further writes here, which is how they came to
                        // disagree with the machine tracking them.
                        _reviewDeckState.update { it.completeRating(rating) }
                        // An AGAIN rating re-queues the card at the end of the session inside
                        // ReviewDeckState.wordIds. The word snapshot must grow with it, otherwise
                        // the screen indexes reviewDeck[position] past its end or shows the wrong
                        // card after the first re-queue.
                        if (rating == SrsRating.AGAIN) {
                            _reviewDeck.update { deck -> deck + wordWithSrs }
                        }
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
     * Steps the deck past any card whose enrolment no longer exists.
     *
     * [ReviewDeckState.skipMissing] was written for exactly this and was never called by
     * anything but its own tests. The path it guards is reachable: start a sitting, go back,
     * delete a word in the library, return — [reviewSessionWordIds] is still set, so the deck is
     * not rebuilt and still holds the deleted enrolment. The learner is then shown a card whose
     * answer can never be recorded.
     *
     * That is not data loss — [SrsRepository.processReview] rejects it as
     * [ReviewFailure.NotEnrolled], and Skip still works — but it is a card that costs the
     * learner a tap, an error message and a decision about what to do next, in exchange for
     * a word they no longer have.
     *
     * Driven off [userWords] rather than a re-query, and applied through `skipMissing`, so only
     * the *index* moves. The snapshot in [reviewDeck] is left alone on purpose: that is the
     * whole reason a card cannot change identity between being shown and being rated, and
     * rebuilding it from the live collection is the bug that snapshot was introduced to prevent.
     * A card deleted further ahead is still skipped when the learner reaches it, which is what
     * `skipMissing`'s leading-run rule is for.
     */
    private fun skipVanishedCards() {
        val presentIds = userWords.value.mapTo(mutableSetOf()) { it.word.id }
        _reviewDeckState.update { it.skipMissing(presentIds) }
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
    }

    /** Moves to the next card, releasing the reveal on the way. */
    fun goToNextCard() {
        _reviewDeckState.update { it.next() }
    }

    /**
     * Moves back to the previous card.
     *
     * A previous card that was already answered shows its answer rather than its prompt, because
     * re-rating it would write a second review for one sitting, and the scheduler's answer to
     * that is to skip the card entirely.
     *
     * That rule needs no code here. `ReviewDeckState.moveTo` already sets `revealed` to whether
     * the card it lands on is in `answers`, which is the same question — this used to add
     * `|| currentAnswer != null` on top, reading the state twice without `updateAndGet` where
     * its two siblings used it, and re-deriving a value the transition had just computed.
     */
    fun goToPreviousCard() {
        _reviewDeckState.update { it.previous() }
    }

    /**
     * Flips the current card.
     *
     * A card that has already been answered opens on its answer, so stepping backwards and
     * forwards shows a consistent thing for the same card.
     */
    fun flipCard() {
        _reviewDeckState.update { it.toggleReveal() }
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
                clearSessionSummary()
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
     *
     * Takes no arguments, and takes them from [wordsForReview] rather than from the screen, so
     * that a notification-opened sitting covers the same cards a tap on "Start Learning" would.
     */
    fun ensureReviewSession() {
        if (_reviewSessionWordIds.value != null) return
        // The collection flows start as empty before the first database emission. Resolving
        // "nothing" into an empty session here would latch `reviewSessionWordIds = []` and the
        // screen would report "nothing left" to a learner whose words had simply not loaded yet,
        // with no retry because ids are no longer null. Wait for the load instead.
        if (!wordsLoaded.value) return
        val words = wordsForReview()
        if (words.isNotEmpty()) {
            startReviewSession(words)
        } else {
            _reviewSessionWordIds.value = emptyList()
            _nothingLeftToReview.value = true
        }
    }

    /**
     * What a sitting should cover: whatever is due, or the whole collection if nothing is.
     *
     * This was written out at two call sites, in two spellings that meant the same thing —
     * `dueWords.ifEmpty { userWords }` in the navigation layer and
     * `if (dueWords.isNotEmpty()) dueWords else allWords` in the review screen. Both read
     * `StateFlow.value` off this class from a composable-scope lambda to do it, so the decision
     * about which cards a learner is shown lived outside the thing that owns the deck, and a
     * change to it — capping the sitting, or preferring learning cards to due ones — had two
     * places to be made and one of them was a navigation callback that no test reaches.
     *
     * Reading `.value` there was also quietly wrong twice over: a `stateIn` flow's `value` is
     * whatever the last emission held, which on a cold start is the initial `emptyList()` and so
     * resolves to "the whole collection" for a learner whose due words had not been queried yet.
     */
    private fun wordsForReview(): List<WordWithSrs> {
        val due = dueWords.value
        return if (due.isNotEmpty()) due else userWords.value
    }

    /**
     * Starts a sitting over whatever is due, or the whole collection if nothing is.
     *
     * The no-argument form, for the navigation layer: pressing "Start Learning" is a request for
     * a sitting, not for a particular set of cards, and deciding which cards that is is this
     * class's job. [startReviewSession] keeps the explicit-list overload for the two callers that
     * have already queried — [restartReviewSession], which must not fall back, and
     * [ensureReviewSession], which shares [wordsForReview].
     */
    fun startReviewSession() {
        startReviewSession(wordsForReview())
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

    // ---- interface sounds -------------------------------------------------------------------------

    /**
     * The app's short interaction sounds.
     *
     * Held beside [pronunciationService] rather than created by the screens that play them, for
     * the same reason that service is: two screens playing a chime at the same time would be two
     * [android.media.SoundPool]s decoding six files each, and a setting that had to be re-read by
     * every call site would be a setting that some call sites forgot.
     *
     * A screen should reach this through [playSound], which applies the learner's preference.
     */
    val uiSounds = UiSoundPlayer(application)

    private val uiPreferences = UiPreferencesStore(application)

    /**
     * Whether interface sounds may play.
     *
     * Backed by [UiPreferencesStore] rather than by the `user_preferences` table, and held as a
     * flow so the settings switch and the player cannot disagree: the write goes to preferences,
     * then the flow, then the player's own flag, so anyone reading the flow always sees a player
     * that has already been told.
     *
     * Kept separate from [isSlowTts] on purpose. Those control different things - one is about
     * how words sound, the other about whether the interface makes noise at all - and a learner
     * who wants pronunciation audio but no clicks is describing a real preference, not a
     * contradictory one.
     */
    private val _soundEffectsEnabled = MutableStateFlow(uiPreferences.soundEffectsEnabled)
    val soundEffectsEnabled: StateFlow<Boolean> = _soundEffectsEnabled.asStateFlow()

    init {
        uiSounds.isEnabled = _soundEffectsEnabled.value
    }

    /** Persists and applies the sound-effect preference immediately. */
    fun setSoundEffectsEnabled(enabled: Boolean) {
        uiPreferences.setSoundEffectsEnabled(enabled)
        _soundEffectsEnabled.value = enabled
        uiSounds.isEnabled = enabled
    }

    // ---- interface theme --------------------------------------------------------------------------

    /**
     * The interface theme: system, light or dark.
     *
     * The same shape as [soundEffectsEnabled] for the same reason: the write goes to
     * preferences, then to the flow, so the settings control and the theme in
     * `MainActivity` cannot disagree. Read once at startup from [UiPreferencesStore], which
     * defaults to system; device-global, so it applies before sign-in and survives an
     * account switch.
     */
    private val _themeMode =
        MutableStateFlow<StorageValues.ThemeMode>(uiPreferences.themeMode)
    val themeMode: StateFlow<StorageValues.ThemeMode> = _themeMode.asStateFlow()

    /** Persists and applies the theme choice immediately. */
    fun setThemeMode(mode: StorageValues.ThemeMode) {
        uiPreferences.setThemeMode(mode)
        _themeMode.value = mode
    }

    /**
     * Plays [sound] if the learner has sounds enabled.
     *
     * The only way a screen should reach the player, so "is it enabled?" is decided in one place
     * instead of at every button.
     */
    fun playSound(sound: UiSound) {
        uiSounds.play(sound)
    }

    // ---- random review ----------------------------------------------------------------------------

    /**
     * The open Random Review sitting, if there is one.
     *
     * A snapshot rather than a derived query, for the reason [reviewDeck] is: the word under the
     * learner's finger must not change identity between being shown and being answered, and
     * re-deriving it from a live collection flow would let a background write swap the card
     * mid-pronunciation.
     *
     * This never touches the scheduler. Nothing in this section writes `srs_state`, `review_log`
     * or a due date: Random Review is practice, and recording a review for a word the learner was
     * never asked to rate would quietly change the study plan of a feature that exists to give
     * them a break from it.
     */
    private data class RandomReviewSession(
        val choice: RandomWordChoice,
        val revealed: Boolean,
        val wordsShown: Int,
        /** Valid pronunciation attempts against the word on screen. Technical failures never land here. */
        val attempts: Int = 0,
    )

    private val _randomReviewSession = MutableStateFlow<RandomReviewSession?>(null)

    /**
     * The last words the wheel drew, oldest first, capped at the history size.
     *
     * Plain state rather than a flow because nothing composes from it: it is an input to the
     * next draw, not something displayed. It lives in the view model rather than the screen so
     * leaving and re-entering keeps the memory - the spec tracks selections "regardless of
     * whether pronunciation attempts were successful", and a re-entry that forgot would repeat.
     * Written only on the main thread, where every caller here runs.
     */
    private var recentRandomIds: List<Long> = emptyList()

    /**
     * A drawn word waiting for its selection animation to finish. Null when no draw is pending.
     *
     * Split from the published session on purpose: the draw (with its history update) happens
     * up front so the animation knows where it lands, but the word only goes on screen when the
     * wheel stops. Publishing immediately would flash the answer, fire the arrival sound early,
     * and let a stale recogniser result meet the new word.
     */
    private val _pendingSelection = MutableStateFlow<RandomWordChoice?>(null)
    val pendingSelection: StateFlow<RandomWordChoice?> = _pendingSelection.asStateFlow()

    /**
     * What Random Review is showing, derived from the same [userWords] every other screen reads.
     *
     * Combined rather than held separately so this feature could not grow its own copy of the
     * vocabulary query: one collection, one error flag, one loader, and a surface that agrees
     * with the library about what the learner has, simply because it is looking at the same rows.
     */
    val randomReviewState: StateFlow<RandomReviewUiState> =
        combine(userWords, wordsLoaded, libraryError, _randomReviewSession) { words, loaded, error, session ->
            when {
                error != null -> RandomReviewUiState.Failed(error)
                !loaded -> RandomReviewUiState.Loading
                session != null -> RandomReviewUiState.Showing(
                    choice = session.choice,
                    revealed = session.revealed,
                    wordsShown = session.wordsShown,
                    attempts = session.attempts,
                )
                words.isEmpty() -> RandomReviewUiState.NoVocabulary
                // Loaded, non-empty, not yet picked: the first pick is in flight. Drawing the
                // empty state here would report "no vocabulary" on the frame before the pick
                // lands, which a learner reads as the feature being broken rather than as slow.
                else -> RandomReviewUiState.Loading
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RandomReviewUiState.Loading)

    /**
     * Draws the next word and holds it as pending until its selection animation finishes.
     *
     * The history updates at draw time, not when attempts resolve: a word counts as "recently
     * selected" the moment the wheel lands on it. A second call while a draw is pending is a
     * no-op, so double-tapped START cannot stack two draws.
     *
     * Waits for the collection instead of racing it, for the reason the old opener did: a
     * `StateFlow` reads as empty before its first database emission.
     */
    fun requestSelection() {
        if (_pendingSelection.value != null) return
        viewModelScope.launch {
            wordsLoaded.first { it }
            if (_pendingSelection.value != null) return@launch
            val draw = pickRandomWord(userWords.value, recentRandomIds.toSet()) ?: return@launch
            recentRandomIds = updateRecentHistory(recentRandomIds, draw.word.word.id)
            _pendingSelection.value = draw
        }
    }

    /**
     * Publishes the pending draw as the word on screen. A no-op with nothing pending.
     *
     * Attempts reset for the new word; the shown counter continues the sitting.
     */
    fun confirmSelection() {
        val draw = _pendingSelection.value ?: return
        _pendingSelection.value = null
        val shown = _randomReviewSession.value?.wordsShown ?: 0
        _randomReviewSession.value = RandomReviewSession(
            choice = draw,
            revealed = false,
            wordsShown = shown + 1,
            attempts = 0,
        )
    }

    /** Drops a pending draw, e.g. when the feature is exited mid-animation. */
    fun cancelSelection() {
        _pendingSelection.value = null
    }

    /**
     * Records one valid pronunciation attempt against the word on screen.
     *
     * The screen decides validity - heard, current, non-blank - so this only increments. Three
     * increments open the help state; the screen reads that off the session it already holds.
     */
    fun noteRandomAttempt() {
        _randomReviewSession.update { session ->
            session?.copy(attempts = session.attempts + 1)
        }
    }

    /** Opens the help state for the word on screen: meaning, Pinyin and playback. */
    fun revealRandomHelp() {
        _randomReviewSession.update { session ->
            if (session == null || session.revealed) session else session.copy(revealed = true)
        }
    }

    /** Ends the sitting. The next entry starts fresh rather than resuming a half-answered word. */
    fun endRandomReview() {
        _randomReviewSession.value = null
        _pendingSelection.value = null
    }

    // ---- daily study limits -----------------------------------------------------------------------------

    /**
     * The learner's own daily caps.
     *
     * These were the most consequential settings in the app with no way to reach them. The
     * dashboard computes its whole recommendation from them — `DashboardDao` reads them to cap
     * the new words and reviews offered each day, and `DashboardSnapshot` uses the same pair to
     * decide whether the learner is "caught up" — so they decide what the app tells someone to
     * study today. They were validated, stored, migrated and consumed, and nothing could change
     * them: every learner got 10 new words a day for the life of their account.
     *
     * Exposed as a nullable flow rather than two ints defaulting to 10 and 60, because the
     * settings screen has to be able to tell "the learner chose 10" from "we have not loaded
     * their row yet", and rendering the fallback as though it were stored would be the same
     * mistake as reporting a count before the query has answered.
     *
     * Flattened on the current user, so it re-reads when the learner signs in as someone else.
     */
    val dailyLimits: StateFlow<DailyLimits?> =
        currentUser
            .flatMapLatest { user ->
                if (user == null) {
                    flowOf<DailyLimits?>(null)
                } else {
                    userRepository.observePreferences(user.id).map { prefs ->
                        prefs?.let { DailyLimits(it.dailyNewWordLimit, it.dailyReviewLimit) }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The two caps as one value.
     *
     * A pair rather than two separate flows because they are always adjusted together and are
     * always displayed together; a UI that read them independently could render a new-word count
     * from one write and a review count from another.
     *
     * Declared before [dailyLimits] on purpose only because Kotlin initialisation order matters
     * for properties, and a nested class has no initialisation dependency - the reader looking
     * for the definition above its use is the only thing that cares.
     */
    data class DailyLimits(val newWords: Int, val reviews: Int)

    /**
     * Saves a new cap, ignoring a change that would not alter anything.
     *
     * A stepper emits the same value repeatedly while a finger rests on it, and each of those
     * would be a full read-modify-write transaction on a row holding every other setting.
     */
    fun setDailyNewWordLimit(limit: Int) {
        val user = currentUser.value ?: return
        if (dailyLimits.value?.newWords == limit) return
        viewModelScope.launch { userRepository.setDailyNewWordLimit(user.id, limit) }
    }

    /** As [setDailyNewWordLimit], for the daily review cap. */
    fun setDailyReviewLimit(limit: Int) {
        val user = currentUser.value ?: return
        if (dailyLimits.value?.reviews == limit) return
        viewModelScope.launch { userRepository.setDailyReviewLimit(user.id, limit) }
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
        // The SoundPool is native memory held for the life of the process otherwise. A later
        // play would simply rebuild it, so releasing here costs nothing but the first tap's
        // decode on the unlikely path where a cleared view model is asked for a sound again.
        uiSounds.release()
    }
}
