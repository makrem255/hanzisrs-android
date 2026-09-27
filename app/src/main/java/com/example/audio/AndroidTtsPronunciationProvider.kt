package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Mandarin pronunciation from the device's own text-to-speech engine.
 *
 * The replacement for the old `TextToSpeechHelper`, and the reason it was replaced: that class
 * could only report success by *not* failing, so a device with no Mandarin voice produced silence
 * with no explanation, and a tap in the moment before the engine finished initialising was
 * discarded without a trace. Everything here is arranged so that every outcome - including "the
 * engine is not ready yet" and "this device has no Chinese voice" - reaches the listener.
 *
 * Two behaviours are worth knowing about:
 *
 *  - **A tap during warm-up is queued, not dropped.** `TextToSpeech` initialises
 *    asynchronously, so the first tap after launch would otherwise always be lost.
 *  - **The reading a polyphonic character is given is the engine's choice.** For 长 or 了 the
 *    engine picks a reading from context, and a bare character gives it very little. That is a
 *    real limitation of on-device synthesis, and it is why [PronunciationRequest] carries the
 *    expected `reading` and `toneNumber`: a provider serving recorded audio can look a file up by
 *    `(syllable, toneNumber)` - the same unique key `pinyin_syllables` uses - and be *guaranteed*
 *    to play the reading on the card. Swapping this class out is how that gets fixed, and nothing
 *    above this interface has to change.
 */
class AndroidTtsPronunciationProvider(context: Context) :
    PronunciationProvider,
    TextToSpeech.OnInitListener {

    override val id: String = ID

    private val _availability = MutableStateFlow<ProviderAvailability>(ProviderAvailability.Unknown)
    override val availability: StateFlow<ProviderAvailability> = _availability.asStateFlow()

    private var engine: TextToSpeech? = null
    private var isReady = false

    /** A request that arrived before the engine finished starting. */
    private var pending: Pair<PronunciationRequest, PlaybackListener>? = null

    private val utteranceIds = AtomicLong(0L)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            // An engine that started but failed is different from no engine at all, and the
            // learner-facing text differs: this one is worth retrying, the other is not.
            val failure = PronunciationFailure.EngineUnavailable
            _availability.value = ProviderAvailability.Failed(failure)
            pending?.second?.onFailed(failure)
            pending = null
            return
        }

        val tts = engine
        if (tts == null) {
            val failure = PronunciationFailure.EngineUnavailable
            _availability.value = ProviderAvailability.Failed(failure)
            return
        }

        configureAudioAttributes(tts)

        val result = tts.setLanguage(Locale.CHINESE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            // `Locale.CHINESE` is traditional; fall back before giving up, because a device with
            // only the simplified voice can still pronounce every character this app teaches.
            val fallback = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
                val failure = PronunciationFailure.MandarinUnavailable
                _availability.value = ProviderAvailability.Failed(failure)
                pending?.second?.onFailed(failure)
                pending = null
                Log.w(TAG, "No Mandarin voice is installed on this device")
                return
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val entry = pendingFor(utteranceId) ?: return
                entry.second.onStarted()
            }

            override fun onDone(utteranceId: String?) {
                val entry = pendingFor(utteranceId) ?: return
                forget(utteranceId)
                entry.second.onCompleted()
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                val entry = pendingFor(utteranceId) ?: return
                forget(utteranceId)
                entry.second.onFailed(PronunciationFailure.Playback("utterance error"))
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?, errorCode: Int) {
                onError(utteranceId)
            }
        })

        isReady = true
        _availability.value = ProviderAvailability.Ready
        Log.d(TAG, "Mandarin TTS ready")

        // Anything tapped during warm-up is honoured now, rather than having been lost.
        pending?.let { (request, listener) ->
            pending = null
            issue(request, listener)
        }
    }

    override fun speak(request: PronunciationRequest, listener: PlaybackListener) {
        if (!request.isSpeakable) {
            listener.onFailed(PronunciationFailure.NothingToSay)
            return
        }

        if (!isReady) {
            when (val readiness = _availability.value) {
                is ProviderAvailability.Failed -> {
                    // The engine already failed. Repeating the failure is better than queueing
                    // a request that can never be spoken.
                    listener.onFailed(readiness.failure)
                }
                else -> {
                    _availability.value = ProviderAvailability.Preparing
                    // Held, not dropped. The first tap after launch lands here.
                    pending = request to listener
                }
            }
            return
        }

        issue(request, listener)
    }

    private fun issue(request: PronunciationRequest, listener: PlaybackListener) {
        val tts = engine ?: run {
            listener.onFailed(PronunciationFailure.EngineUnavailable)
            return
        }

        val utteranceId = "hzb-${utteranceIds.incrementAndGet()}"
        inFlight[utteranceId] = request to listener

        tts.setSpeechRate(rateFor(request))
        // QUEUE_FLUSH, not QUEUE_ADD: the learner asked to hear *this* word, and queueing behind
        // the previous one would mean tapping a card and hearing the card before it.
        val result = tts.speak(
            request.text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result != TextToSpeech.SUCCESS) {
            forget(utteranceId)
            listener.onFailed(PronunciationFailure.Playback("speak() rejected the request"))
        }
    }

    override fun stop() {
        runCatching { engine?.stop() }
            .onFailure { Log.w(TAG, "Stopping TTS failed", it) }
        inFlight.clear()
    }

    override fun close() {
        isReady = false
        pending = null
        inFlight.clear()
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }.onFailure { Log.w(TAG, "Shutting down TTS failed", it) }
        engine = null
    }

    private fun pendingFor(utteranceId: String?): Pair<PronunciationRequest, PlaybackListener>? =
        utteranceId?.let { inFlight[it] }

    private fun forget(utteranceId: String?) {
        utteranceId?.let { inFlight.remove(it) }
    }

    /**
     * Delivery rate for a request.
     *
     * One table, in one place. The old code passed 0.65/0.90 for a word and 0.70/0.95 for a
     * sentence from the call site *and* had a `setSpeed` that set 0.65/0.90, so the slow toggle
     * depended on which of the two ran last.
     */
    private fun rateFor(request: PronunciationRequest): Float = when (request.kind) {
        PronunciationRequest.Kind.WORD ->
            if (request.rate == PronunciationRequest.Rate.SLOW) SLOW_WORD_RATE else NORMAL_WORD_RATE
        PronunciationRequest.Kind.SENTENCE ->
            if (request.rate == PronunciationRequest.Rate.SLOW) SLOW_SENTENCE_RATE else NORMAL_SENTENCE_RATE
    }

    /**
     * Declares the audio as speech assistance.
     *
     * This is how the system decides it should duck music rather than stop it, which is the
     * appropriate behaviour for a pronunciation cue. An explicit `AudioManager` focus request
     * would be the alternative, but it introduces a second lifecycle to keep in step with
     * playback - focus is abandoned on a screen change, a phone call or a process death, and a
     * pronunciation prompt is short enough not to need it.
     */
    private fun configureAudioAttributes(tts: TextToSpeech) {
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }.onFailure { Log.w(TAG, "Could not set TTS audio attributes", it) }
    }

    /** Utterances the engine has accepted and not yet finished, keyed by utterance id. */
    private val inFlight = mutableMapOf<String, Pair<PronunciationRequest, PlaybackListener>>()

    /**
     * Started last, once every field this class touches from [onInit] has been initialised.
     *
     * Android delivers `onInit` asynchronously, but ordering the field declarations ahead of the
     * engine means the code is correct whether or not that ever changes, rather than correct only
     * because of a timing detail three platforms deep.
     */
    init {
        engine = TextToSpeech(context.applicationContext, this)
    }

    companion object {
        /**
         * Stable identifier, addressable by name so a settings screen can offer a choice of
         * provider without the rest of the app caring which one is live.
         */
        const val ID = "android-tts"
        private const val TAG = "TtsPronunciation"

        private const val NORMAL_WORD_RATE = 0.90f
        private const val SLOW_WORD_RATE = 0.65f
        private const val NORMAL_SENTENCE_RATE = 0.95f
        private const val SLOW_SENTENCE_RATE = 0.70f
    }
}
