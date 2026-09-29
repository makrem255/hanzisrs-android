package com.example.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the audio system is doing right now, and to which word.
 *
 * Every variant carries the `sourceId` of the request it belongs to, including [Idle]'s absence of
 * one. That is the whole mechanism behind "pronunciation is clearly associated with the correct
 * word": a button renders from the state *and* checks that the state is about the word it was
 * asked to play, so a card that has already been swiped past cannot show a playing indicator for
 * a word that is no longer on screen.
 */
sealed class PronunciationState {

    /** Nothing is loaded and nothing is playing. */
    object Idle : PronunciationState()

    /** Accepted by the service, not yet audible. */
    data class Loading(override val sourceId: Long) : PronunciationState()

    /** Audible right now. */
    data class Playing(override val sourceId: Long) : PronunciationState()

    /**
     * Audio could not be produced, and the learner is being told why.
     *
     * There is deliberately no silent path. The previous implementation returned `Unit` from
     * `speak()` and did nothing at all when the engine was not ready, so a learner on a device
     * without a Mandarin voice tapped the speaker, heard nothing, and had no way to tell a broken
     * app from a mis-tap.
     */
    data class Failed(override val sourceId: Long, val failure: PronunciationFailure) : PronunciationState()

    /**
     * The word this state is about, or null when nothing is loaded.
     *
     * Open so each variant can store its own value rather than this getter re-deriving one, and
     * so the branches below resolve to each variant's property - with a single implementation
     * the reference would be to this getter itself, and reading [Loading.sourceId] would recurse
     * until the stack ran out.
     */
    open val sourceId: Long?
        get() = when (this) {
            is Idle -> null
            is Loading -> sourceId
            is Playing -> sourceId
            is Failed -> sourceId
        }
}

/**
 * The one object a screen talks to about audio.
 *
 * Owns the state machine, the association between an utterance and the word it belongs to, and
 * the rule that a superseded utterance is not allowed to report anything. It knows nothing about
 * Android's `TextToSpeech` - audio focus and engine lifecycle live in the provider - so the whole
 * of the interesting behaviour is testable on the JVM with a fake provider.
 *
 * The instance is shared across ViewModels because a TTS engine is expensive to create and is
 * needed by the deck, the library, the home screen and the add-word screen at once. Two engines
 * would also mean two voices talking over each other.
 */
class PronunciationService(private val provider: PronunciationProvider) {

    private val _state = MutableStateFlow<PronunciationState>(PronunciationState.Idle)
    val state: StateFlow<PronunciationState> = _state.asStateFlow()

    /**
     * Whether audio can be produced at all.
     *
     * Exposed separately from [state] because "no engine" is a property of the device, not of the
     * current word: a screen wants to disable or explain the button *before* it is tapped, rather
     * than only after something has already gone wrong.
     */
    val availability: StateFlow<ProviderAvailability> = provider.availability

    /**
     * The most recent request, kept so [replay] can re-issue it.
     *
     * Null until something has been asked for. A replay button with nothing to replay must be
     * disabled, which is a different thing from a replay button that silently does nothing.
     */
    @Volatile
    private var lastRequest: PronunciationRequest? = null

    private val isSlow = MutableStateFlow(false)

    /**
     * Whether the learner has asked for slow delivery.
     *
     * A flow rather than the [isSlowTts] snapshot so a settings switch binds to the engine's
     * actual state. Two copies of one preference is how the old `setSpeed`/per-call-rate
     * disagreement happened in the first place: nothing in the type system said the two were
     * supposed to agree, and they did not.
     */
    val isSlowTtsFlow: StateFlow<Boolean> = isSlow.asStateFlow()

    /**
     * Speaks [request], superseding anything already playing.
     *
     * Returns immediately; the state flow reports what happened. Synchronous by design so a tap
     * handler can fire and forget without a coroutine, and so a refused request is reported
     * through the same channel as a failed one.
     */
    fun play(request: PronunciationRequest) {
        // A stale completion callback for a previous word must never be able to write state.
        // The token is the identity of this specific attempt; only the newest token is honoured.
        val token = ++latestToken

        if (!request.isSpeakable) {
            // Refused before the provider is touched: there is no audio to wait for, and
            // reporting it as a failure is the honest outcome rather than an inert button.
            publish(token, PronunciationState.Failed(request.sourceId, PronunciationFailure.NothingToSay))
            return
        }

        when (val readiness = provider.availability.value) {
            is ProviderAvailability.Failed -> {
                // The engine cannot produce audio at all, so the request is never handed over.
                // Reporting the engine's own reason is what tells the learner whether this is
                // something they can fix.
                publish(token, PronunciationState.Failed(request.sourceId, readiness.failure))
                return
            }
            // Not an error: the engine is warming up. Loading is the truthful state and the
            // request is still handed over, because a provider is free to queue it.
            ProviderAvailability.Unknown,
            ProviderAvailability.Preparing,
            ProviderAvailability.Ready -> Unit
        }

        // The learner's rate preference is applied here, at the one point a request is issued, so
        // it cannot be contradicted by a separate `setSpeed` call.
        val effective = request.copy(rate = isSlow.value.let {
            if (it) PronunciationRequest.Rate.SLOW else PronunciationRequest.Rate.NORMAL
        })
        lastRequest = effective
        publish(token, PronunciationState.Loading(effective.sourceId))

        provider.speak(effective, object : PlaybackListener {
            override fun onStarted() {
                publish(token, PronunciationState.Playing(effective.sourceId))
            }

            override fun onCompleted() {
                publish(token, PronunciationState.Idle)
            }

            override fun onFailed(failure: PronunciationFailure) {
                publish(token, PronunciationState.Failed(effective.sourceId, failure))
            }
        })
    }

    /**
     * Speaks the most recent request again.
     *
     * The replay control. Returns false when there is nothing to replay, so a caller can keep the
     * button disabled rather than presenting a control that does nothing when pressed.
     */
    fun replay(): Boolean {
        val previous = lastRequest ?: return false
        play(previous)
        return true
    }

    /** Stops playback and returns to [PronunciationState.Idle]. */
    fun stop() {
        // Bump the token so an in-flight completion for the stopped utterance is ignored.
        ++latestToken
        provider.stop()
        _state.value = PronunciationState.Idle
    }

    /**
     * Slows or restores the delivery rate.
     *
     * The rate itself belongs to the provider - it is an engine-specific value - but the choice
     * between slow and normal is a learner preference, so the service holds it and re-applies it
     * on the next request.
     */
    fun setSlow(slow: Boolean) {
        isSlow.value = slow
    }

    val isSlowTts: Boolean get() = isSlow.value

    /** Releases the engine. The service is unusable afterwards. */
    fun shutdown() {
        ++latestToken
        runCatching { provider.close() }
            .onFailure { Log.w(TAG, "Closing the pronunciation provider failed", it) }
        _state.value = PronunciationState.Idle
    }

    private var latestToken: Long = 0

    /**
     * Writes [next] only if [token] is still the newest attempt.
     *
     * This is the guard that makes the deck safe to swipe quickly. A one-character utterance can
     * finish after the learner has already moved on, and an un-guarded `onCompleted` would blank
     * the state belonging to the new word, or worse, re-enable a button for a card that is gone.
     */
    private fun publish(token: Long, next: PronunciationState) {
        if (token != latestToken) {
            Log.d(TAG, "Dropping a stale audio callback for $next")
            return
        }
        _state.value = next
    }

    companion object {
        private const val TAG = "Pronunciation"
    }
}
