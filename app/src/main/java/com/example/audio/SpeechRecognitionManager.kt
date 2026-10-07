package com.example.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.text.Normalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why a listening attempt could not be made, or made itself useless.
 *
 * One enum rather than a free-text message because every branch needs *different words on
 * screen*: a learner who denied the microphone has to be told where to re-enable it, one whose
 * device has no recogniser needs to know the feature is unavailable rather than that they spoke
 * badly, and one who was offline has a fixable problem. Folding them all into "couldn't hear
 * you" would be the app blaming the learner for its own limits.
 */
enum class RecognitionFailure {
    /** The learner has not granted, or has actively refused, `RECORD_AUDIO`. */
    PermissionDenied,

    /** No speech recogniser exists on this device at all. */
    NotSupported,

    /** The device has a recogniser but not for the language we asked for. */
    LanguageUnavailable,

    /** The engine needs a network connection and there is not one. */
    NetworkRequired,

    /** Another app, or this one, is already using the microphone. */
    MicrophoneBusy,

    /** The learner stayed silent for the whole window. */
    NoSpeech,

    /** The listener was stopped before it produced anything - the screen went away. */
    Cancelled,

    /** Anything else. Deliberately vague: the numeric code means nothing to a learner. */
    Unknown,
}

/**
 * What the on-device speech recogniser is doing right now.
 *
 * ## What this is not
 *
 * It is not a pronunciation grader, and nothing in this file ever produces a score, a percentage
 * or a "fluency" rating. Android's [SpeechRecognizer] performs speech-to-text: it reports the
 * words it believes it heard, and that is the whole of its output. A number derived from it
 * would be this app's invention dressed up as a measurement, which is exactly the kind of thing
 * that makes a learner trust the wrong feedback.
 *
 * The honest comparison - and the only one available - is *did the recogniser produce this
 * word*, which is what [RecognitionResult.Success] and [RecognitionResult.Heard] report. It says
 * nothing about tones, stress or rhythm, and the UI says so out loud rather than implying that a
 * match means the pronunciation was correct.
 */
sealed interface RecognitionState {

    /** Nothing is running. The first state, and the state returned to by [reset]. */
    object Idle : RecognitionState

    /** The microphone is open and the engine is gathering audio. */
    object Listening : RecognitionState

    /** Audio stopped arriving; the engine is converting it to text. */
    object Processing : RecognitionState

    /**
     * The engine heard something and produced a hypothesis.
     *
     * Not a pass. The learner is shown the raw hypothesis alongside whether it contains the
     * target word, so they can judge the difference between "the machine transcribed me" and
     * "I actually said it" themselves - which is a judgement no recogniser can make for them.
     */
    data class Heard(val hypothesis: String, val alternatives: List<String>) : RecognitionState

    /** The attempt could not be made, or produced nothing usable. See [RecognitionFailure]. */
    data class Failed(val failure: RecognitionFailure) : RecognitionState
}

/**
 * The outcome of comparing a recogniser hypothesis against the word being practised.
 *
 * Three outcomes rather than a score, because these are the three things that are actually true:
 * the recogniser produced the word, it produced something else, or it produced nothing to
 * compare.
 */
enum class RecognitionVerdict {
    /** The hypothesis contains the target word. The recogniser transcribed the learner. */
    Recognised,

    /** The recogniser heard speech, but not this word. */
    Different,

    /** There is no hypothesis to compare, because nothing was heard. */
    NothingHeard,
}

/**
 * Reduces text to the form a match should be decided on.
 *
 * Everything that is not a letter or a digit goes: whitespace, punctuation (including the full
 * width marks a Chinese recogniser emits), and the symbols an engine sometimes adds. Case is
 * folded for the latin side, and tone marks are stripped from pinyin so `xuéxí` and `xuexi`
 * compare as the same thing - the tone is information the learner is being asked about, not a
 * difference in identity, and a learner who said the right syllable with the wrong tone should
 * see that they said the right syllable rather than "nothing matched".
 */
fun normalizeForMatch(text: String): String =
    Normalizer.normalize(
        text.filter { it.isLetterOrDigit() },
        Normalizer.Form.NFD,
    ).filter { it.code !in 0x0300..0x036F } // combining diacritics left by NFD
        .lowercase()

/**
 * Whether [hypothesis] is plausibly the learner saying [target].
 *
 * Substring containment rather than equality, in one direction only. A Chinese recogniser
 * routinely returns the requested word with a particle or a neighbour attached - `学习了`,
 * `是学习`, `我们学习` - and those are recognitions of the word, not misses. The reverse
 * direction is deliberately excluded: if the learner was asked for `学习` and only `学` came
 * back, they did not say the word, and reporting a match would be telling them they had.
 *
 * Pure, so it can be tested directly. It is a comparison of transcriptions and nothing more -
 * see [RecognitionState.Heard] for why no score follows from it.
 */
fun matchesTarget(target: String, hypothesis: String): Boolean {
    val wanted = normalizeForMatch(target)
    val heard = normalizeForMatch(hypothesis)
    if (wanted.isEmpty() || heard.isEmpty()) return false
    if (wanted == heard) return true
    return heard.contains(wanted)
}

/** The verdict a hypothesis produces against [target], or [RecognitionVerdict.NothingHeard]. */
fun verdictFor(target: String, hypothesis: String?): RecognitionVerdict = when {
    hypothesis.isNullOrBlank() -> RecognitionVerdict.NothingHeard
    matchesTarget(target, hypothesis) -> RecognitionVerdict.Recognised
    else -> RecognitionVerdict.Different
}

/**
 * Owns an Android [SpeechRecognizer] for the lifetime of one screen.
 *
 * ## Why it is screen-scoped and not in the view model
 *
 * [SpeechRecognizer] has to be created on a thread with a looper, holds a microphone and a
 * bound service, and must be torn down when nobody is listening for its callbacks. A view model
 * outlives its screen, so keeping one there means either leaking the service past the composable
 * that owns it or destroying it from a lifecycle callback that has to guess when the screen is
 * really gone. This class is remembered in the composition and destroyed in a
 * `DisposableEffect`, which is the one place where "the screen went away" is unambiguous.
 *
 * ## Threading
 *
 * Every public entry point posts to the main looper before touching the recognizer, so callers
 * never have to care which thread they are on and the recognizer is always created and used on
 * the thread its callbacks will arrive on. State itself is a [StateFlow] and is safe to read
 * from anywhere.
 *
 * ## Permission
 *
 * This class never requests a permission - requesting is an activity-level decision with its own
 * UI, and a library class that silently launched a permission dialog would take the learner out
 * of whatever they were doing. It checks, reports [RecognitionFailure.PermissionDenied], and the
 * screen decides whether to ask.
 */
class SpeechRecognitionManager(
    private val context: Context,
    /** Language asked for. Mandarin, matching the content this app teaches. */
    private val languageTag: String = "zh-CN",
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    /** Lazily created on the main looper; never touched from any other thread. */
    private var recognizer: SpeechRecognizer? = null

    private var listening = false

    /**
     * The word currently being practised, held so [RecognitionState.Heard] can be compared
     * against it by the screen without the screen having to thread it through callbacks.
     */
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = RecognitionState.Listening
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
            if (_state.value is RecognitionState.Listening) {
                _state.value = RecognitionState.Processing
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstHypothesis() ?: return
            if (_state.value is RecognitionState.Listening ||
                _state.value is RecognitionState.Processing
            ) {
                _state.value = RecognitionState.Heard(text, emptyList())
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val primary = results.firstHypothesis()
            if (primary == null) {
                // Speech arrived but produced nothing transcribable. That is "we heard you and
                // could not make out the word", which is not the same as silence.
                _state.value = RecognitionState.Failed(RecognitionFailure.NoSpeech)
                return
            }
            val alternatives = results.hypotheses().drop(1)
            _state.value = RecognitionState.Heard(primary, alternatives)
        }

        override fun onError(error: Int) {
            listening = false
            _state.value = RecognitionState.Failed(error.toFailure())
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * Starts listening, if the device and the permissions allow it.
     *
     * Safe to call repeatedly: an attempt while already listening is ignored rather than
     * stacking a second recognizer on top of the first.
     */
    fun startListening() {
        mainHandler.post {
            if (listening) return@post

            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Checked here as well as by the screen, because the permission can be revoked
                // from settings while the app is open - and a revoked permission handed to
                // `SpeechRecognizer` surfaces as an unexplained ERROR_CLIENT, i.e. as a bug.
                _state.value = RecognitionState.Failed(RecognitionFailure.PermissionDenied)
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _state.value = RecognitionState.Failed(RecognitionFailure.NotSupported)
                return@post
            }

            try {
                val active = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context)
                    .also { recognizer = it }
                active.setRecognitionListener(listener)
                active.startListening(buildIntent())
                listening = true
                _state.value = RecognitionState.Listening
            } catch (t: Throwable) {
                // A missing recogniser service can surface as a RuntimeException from the bind.
                // It is a device capability problem, never something the learner did.
                Log.w("SpeechRecognition", "could not start recognition", t)
                listening = false
                _state.value = RecognitionState.Failed(RecognitionFailure.Unknown)
            }
        }
    }

    /** Stops listening without producing a result, and returns to [RecognitionState.Idle]. */
    fun cancel() {
        mainHandler.post {
            listening = false
            runCatching { recognizer?.cancel() }
                .onFailure { Log.w("SpeechRecognition", "cancel failed", it) }
            _state.value = RecognitionState.Idle
        }
    }

    /** Returns to [RecognitionState.Idle] without disturbing a running attempt. */
    fun reset() {
        _state.value = RecognitionState.Idle
    }

    /**
     * Releases the recognizer and its service.
     *
     * Idempotent, and called from the composition's disposal - leaving the screen mid-listen
     * must not leave a service bound and a microphone held by a screen nobody can see.
     */
    fun destroy() {
        mainHandler.post {
            listening = false
            val active = recognizer
            recognizer = null
            if (active != null) {
                runCatching {
                    active.setRecognitionListener(null)
                    active.cancel()
                    active.destroy()
                }.onFailure { Log.w("SpeechRecognition", "destroy failed", it) }
            }
            _state.value = RecognitionState.Idle
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            // Bounded so a device that ignores the language cannot run away with a minute of
            // hypotheses the screen would never surface.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    private fun Bundle?.firstHypothesis(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun Bundle?.hypotheses(): List<String> =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.drop(1)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /**
     * Maps the recogniser's integer error codes to the branches a learner can actually act on.
     *
     * The numeric constants are stable public API, but they are not treated as exhaustive: an
     * unknown code falls through to [RecognitionFailure.Unknown] rather than being surfaced
     * verbatim, because `ERROR 12` on a screen is not information.
     */
    private fun Int.toFailure(): RecognitionFailure = when (this) {
        SpeechRecognizer.ERROR_AUDIO -> RecognitionFailure.MicrophoneBusy
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionFailure.PermissionDenied
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> RecognitionFailure.NetworkRequired

        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> RecognitionFailure.NoSpeech

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> RecognitionFailure.LanguageUnavailable

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionFailure.MicrophoneBusy
        SpeechRecognizer.ERROR_SERVER -> RecognitionFailure.NetworkRequired
        else -> RecognitionFailure.Unknown
    }
}
