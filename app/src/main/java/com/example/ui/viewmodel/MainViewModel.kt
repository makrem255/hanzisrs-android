package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiAiService
import com.example.data.ai.GeneratedWordData
import com.example.data.db.AppDatabase
import com.example.data.model.UserEntity
import com.example.data.model.WordEntity
import com.example.data.model.WordWithSrs
import com.example.data.repository.AuthResult
import com.example.data.repository.SaveWordResult
import com.example.data.repository.SrsRepository
import com.example.data.repository.UserRepository
import com.example.data.repository.WordRepository
import com.example.data.srs.SrsRating
import com.example.util.NotificationHelper
import com.example.util.TextToSpeechHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class AiGenerationState {
    object Idle : AiGenerationState()
    object Loading : AiGenerationState()
    data class ReadyForReview(val wordData: GeneratedWordData) : AiGenerationState()
    data class Error(val message: String) : AiGenerationState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val userRepository = UserRepository(database.userDao())
    val wordRepository = WordRepository(database)
    val srsRepository = SrsRepository(database.srsReviewDao())
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

    // Review Deck Active State
    private val _currentDeckIndex = MutableStateFlow(0)
    val currentDeckIndex: StateFlow<Int> = _currentDeckIndex.asStateFlow()

    private val _isCardFlipped = MutableStateFlow(false)
    val isCardFlipped: StateFlow<Boolean> = _isCardFlipped.asStateFlow()

    private val _reviewedSessionCount = MutableStateFlow(0)
    val reviewedSessionCount: StateFlow<Int> = _reviewedSessionCount.asStateFlow()

    // Capture a deck once per session. Observing the live due list here would shift
    // card indexes as ratings update due dates and can cause cards to be skipped.
    private val _reviewSessionWordIds = MutableStateFlow<List<Long>?>(null)
    val reviewSessionWordIds: StateFlow<List<Long>?> = _reviewSessionWordIds.asStateFlow()

    private val _wordSaveError = MutableStateFlow<String?>(null)
    val wordSaveError: StateFlow<String?> = _wordSaveError.asStateFlow()

    private val _isSavingWord = MutableStateFlow(false)
    val isSavingWord: StateFlow<Boolean> = _isSavingWord.asStateFlow()

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
        val existing = database.wordDao().findWordByHanzi(userId, "学")
        if (existing == null) {
            val starterWords = listOf(
                Pair("学", "xué" to "to study; to learn"),
                Pair("好", "hǎo" to "good; fine"),
                Pair("你", "nǐ" to "you")
            )
            starterWords.forEach { (hanzi, data) ->
                wordRepository.saveNewWordWithInitialSrs(
                    WordEntity(
                        userId = userId,
                        hanzi = hanzi,
                        pinyin = data.first,
                        meaning = data.second,
                        hskLevel = 1,
                        radical = "部首",
                        exampleCn = "你好，我在学习中文。",
                        examplePy = "Nǐ hǎo, wǒ zài xuéxí zhōngwén.",
                        exampleEn = "Hello, I am learning Chinese.",
                        strokeJson = "横, 竖, 撇, 捺"
                    ),
                    initialDueImmediate = true
                )
            }
        }
    }

    // AI Generation Operations
    fun generateWord(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _wordSaveError.value = null
            _aiState.value = AiGenerationState.Loading
            val result = geminiService.generateChineseWordData(query)
            result.fold(
                onSuccess = { data ->
                    _aiState.value = AiGenerationState.ReadyForReview(data)
                },
                onFailure = { error ->
                    _aiState.value = AiGenerationState.Error(error.message ?: "Failed to generate word data")
                }
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
            val word = WordEntity(
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
                tags = "HSK$hskLevel,Custom"
            )
            when (wordRepository.saveNewWordWithInitialSrs(word, initialDueImmediate = true)) {
                is SaveWordResult.Saved -> {
                    _aiState.value = AiGenerationState.Idle
                    onComplete()
                }
                SaveWordResult.Duplicate -> {
                    _wordSaveError.value = "$cleanHanzi is already in your deck. Open it in the Library to review it."
                }
            }
            _isSavingWord.value = false
        }
    }

    // SRS Review Flow
    fun flipCard() {
        _isCardFlipped.value = !_isCardFlipped.value
    }

    fun submitRating(wordWithSrs: WordWithSrs, rating: SrsRating) {
        val user = currentUser.value ?: return
        viewModelScope.launch {
            srsRepository.processReview(wordWithSrs.word.id, user.id, rating)
            _reviewedSessionCount.value = _reviewedSessionCount.value + 1
            _isCardFlipped.value = false
            _currentDeckIndex.value = _currentDeckIndex.value + 1
        }
    }

    fun resetDeckSession() {
        _currentDeckIndex.value = 0
        _isCardFlipped.value = false
        _reviewedSessionCount.value = 0
    }

    fun startReviewSession(words: List<WordWithSrs>) {
        _reviewSessionWordIds.value = words.map { it.word.id }
        resetDeckSession()
    }

    fun ensureReviewSession(words: List<WordWithSrs>) {
        if (_reviewSessionWordIds.value == null && words.isNotEmpty()) {
            startReviewSession(words)
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
    fun deleteWord(wordId: Long) {
        viewModelScope.launch {
            wordRepository.deleteWord(wordId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsHelper.shutdown()
    }
}
