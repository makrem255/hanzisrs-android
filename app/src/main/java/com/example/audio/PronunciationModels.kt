package com.example.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * What to pronounce, and which reading of it the learner is supposed to be hearing.
 *
 * The [sourceId] is not decoration. Every state the service publishes and every icon a button
 * draws is keyed by it, which is what makes it impossible to show a playing indicator for word A
 * while word B's audio is actually running - the situation you get when a learner swipes to the
 * next card mid-utterance and the previous completion callback arrives late.
 *
 * The [reading] and [toneNumber] pair is the same identity `pinyin_syllables` is keyed by
 * (`syllable`, `toneNumber`, unique). That is deliberate: a provider that serves recorded audio
 * can look a file up by exactly that key and therefore be *guaranteed* to play the reading the
 * card displays, which on-device synthesis cannot promise for a polyphonic character.
 */
data class PronunciationRequest(
    /** The enrolment or content id this audio belongs to. Used to associate state with a word. */
    val sourceId: Long,
    /** The Chinese text to speak: a single character, or a whole example sentence. */
    val text: String,
    /** Toneless pinyin for [text], e.g. `chang2` -> `chang`. Empty for sentences. */
    val reading: String = "",
    /** 0-4, where 0 is the neutral tone. Meaningless for a sentence. */
    val toneNumber: Int = 0,
    /**
     * Whether this is a single word or a sentence.
     *
     * Not cosmetic. A sentence is a different task with a different failure mode and a different
     * acceptable speaking rate, and conflating them is what produced the previous code's
     * habit of passing a different rate per call site.
     */
    val kind: Kind = Kind.WORD,
    /**
     * How fast to speak.
     *
     * Carried on the request rather than set separately on the provider, because that is what
     * makes it observable: the previous code called `setSpeed(...)` and then passed a *different*
     * rate to the very next `speak(...)`, so the two mechanisms silently overwrote each other and
     * the slow toggle only ever worked by accident. One value, applied once, at the point of use.
     */
    val rate: Rate = Rate.NORMAL
) {
    enum class Kind { WORD, SENTENCE }

    enum class Rate {
        NORMAL,
        SLOW;

        fun toggled(): Rate = if (this == NORMAL) SLOW else NORMAL
    }

    /** True when there is something to say. A blank request is refused, not silently dropped. */
    val isSpeakable: Boolean get() = text.isNotBlank()

    companion object {
        /**
         * Builds a word request from a pinyin spelling that may or may not carry a tone mark.
         *
         * The pinyin is analysed rather than trusted so the tone number is derived rather than
         * parsed twice in two places; `PinyinAnalyzer` is already the single place that knows
         * where a tone mark goes.
         */
        fun forWord(
            sourceId: Long,
            hanzi: String,
            pinyin: String = "",
            toneNumber: Int? = null
        ): PronunciationRequest {
            val analysis = com.example.data.srs.PinyinAnalyzer.analyze(pinyin)
            return PronunciationRequest(
                sourceId = sourceId,
                text = hanzi,
                reading = analysis.syllable,
                // An explicit tone from the caller wins: it came from `pinyin_syllables`, which
                // is the stored authority, whereas analysing a display string can only guess.
                toneNumber = toneNumber ?: analysis.toneNumber
            )
        }

        fun forSentence(sourceId: Long, sentence: String): PronunciationRequest =
            PronunciationRequest(
                sourceId = sourceId,
                text = sentence,
                kind = Kind.SENTENCE
            )
    }
}

/** Why audio could not be produced. Every one of these is shown to the learner. */
sealed class PronunciationFailure(val message: String) {
    /** No synthesis or playback engine exists on the device at all. */
    object EngineUnavailable : PronunciationFailure(
        "Speech is not available on this device."
    )

    /**
     * An engine exists but has no Mandarin voice.
     *
     * Distinct from [EngineUnavailable] because the fix is different: the user can install a
     * language pack, whereas a missing engine cannot be repaired by them. Reporting both as
     * "audio failed" would send the learner to reinstall the app for something that is a
     * system setting.
     */
    object MandarinUnavailable : PronunciationFailure(
        "This device has no Mandarin voice installed. Add Chinese speech in system settings to hear pronunciation."
    )

    /** Nothing to say - a blank field, or a sentence the learner never filled in. */
    object NothingToSay : PronunciationFailure("There is no text to pronounce yet.")

    /** The engine accepted the request and then reported a playback error. */
    data class Playback(val detail: String) : PronunciationFailure(
        "Pronunciation failed to play. Try again."
    )

    /** The request was superseded or the screen went away. Not shown to the learner. */
    object Cancelled : PronunciationFailure("Playback was cancelled.")
}

/** What a provider did with a request. */
sealed class PronunciationOutcome {
    /** The engine accepted the request; completion arrives on the listener, not here. */
    data class Accepted(val reading: String) : PronunciationOutcome()

    /** The engine refused up front. No callback will follow. */
    data class Refused(val failure: PronunciationFailure) : PronunciationOutcome()
}

/** Readiness of a provider, published so the UI can explain a silent button before it is tapped. */
sealed class ProviderAvailability {
    object Unknown : ProviderAvailability()
    object Preparing : ProviderAvailability()
    object Ready : ProviderAvailability()
    data class Failed(val failure: PronunciationFailure) : ProviderAvailability()
}

/** Callbacks a provider raises over the life of one utterance. */
interface PlaybackListener {
    fun onStarted()
    fun onCompleted()

    /** No [onCompleted] will follow. */
    fun onFailed(failure: PronunciationFailure)
}

/**
 * A source of Mandarin audio.
 *
 * The seam the brief asks for. Everything above this interface - state, association with a word,
 * the button, error messaging - is written against the contract and not against Android's
 * `TextToSpeech`, so swapping synthesis for recorded audio (or a hosted voice) is a new
 * implementation of six methods rather than a change to the screens.
 *
 * Implementations must be safe to call from any thread, must never throw out of [speak] (a
 * failure is reported through the listener), and must be idempotent under [stop].
 */
interface PronunciationProvider {
    /** Stable identifier, surfaced in logs and available to settings. */
    val id: String

    /**
     * Whether this provider can currently produce audio.
     *
     * Published rather than queried so a UI can bind to it: an engine that is still warming up is
     * exactly the case that used to fail silently on the first tap after launch.
     */
    val availability: StateFlow<ProviderAvailability>

    /**
     * Starts speaking [request], reporting progress through [listener].
     *
     * Supersedes any utterance already in progress - the learner asked for this word, not the
     * previous one. Returns without calling the listener at all if the request is refused with
     * no useful error to report; every other refusal arrives as `onFailed`.
     */
    fun speak(request: PronunciationRequest, listener: PlaybackListener)

    /** Stops the current utterance. Must not raise the listener unless it is reporting a cancel. */
    fun stop()

    /** Releases the engine. The provider is unusable afterwards. */
    fun close()
}
