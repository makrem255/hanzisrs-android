package com.example

import com.example.audio.PlaybackListener
import com.example.audio.PronunciationFailure
import com.example.audio.PronunciationProvider
import com.example.audio.PronunciationRequest
import com.example.audio.PronunciationService
import com.example.audio.PronunciationState
import com.example.audio.ProviderAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A provider whose every callback is fired by the test, on demand.
 *
 * Deliberately not a simulation: it records exactly what it was asked to do and does nothing
 * until told. A fake that invented timings would let these tests pass while the real engine
 * misbehaved, which is the failure mode this whole system was rebuilt to eliminate.
 */
private class ControllableProvider(
    initial: ProviderAvailability = ProviderAvailability.Ready
) : PronunciationProvider {

    override val id: String = "test-provider"

    private val _availability = MutableStateFlow(initial)
    override val availability: StateFlow<ProviderAvailability> = _availability.asStateFlow()

    val spoken = mutableListOf<PronunciationRequest>()
    private var listener: PlaybackListener? = null
    var isClosed = false
        private set
    var stopCount = 0
        private set

    fun setAvailability(value: ProviderAvailability) {
        _availability.value = value
    }

    /** The listener for the utterance currently in flight. */
    fun currentListener(): PlaybackListener =
        requireNotNull(listener) { "nothing has been spoken" }

    fun start() = currentListener().onStarted()
    fun complete() = currentListener().onCompleted()
    fun fail(failure: PronunciationFailure = PronunciationFailure.Playback("test")) =
        currentListener().onFailed(failure)

    override fun speak(request: PronunciationRequest, listener: PlaybackListener) {
        spoken += request
        this.listener = listener
    }

    override fun stop() {
        stopCount++
    }

    override fun close() {
        isClosed = true
    }
}

@RunWith(RobolectricTestRunner::class)
class PronunciationServiceTest {

    private lateinit var provider: ControllableProvider
    private lateinit var service: PronunciationService

    private val tea = 101L
    private val water = 102L

    private fun word(id: Long, hanzi: String = "茶", pinyin: String = "chá") =
        PronunciationRequest.forWord(sourceId = id, hanzi = hanzi, pinyin = pinyin)

    @Before
    fun setUp() {
        provider = ControllableProvider()
        service = PronunciationService(provider)
    }

    // ---- playback ------------------------------------------------------------------------------------

    @Test
    fun `a request is played and the state reports it`() {
        service.play(word(tea))

        assertEquals("the request reached the provider", 1, provider.spoken.size)
        assertEquals(tea, provider.spoken.single().sourceId)

        provider.start()
        assertEquals(PronunciationState.Playing(tea), service.state.value)

        provider.complete()
        assertEquals(PronunciationState.Idle, service.state.value)
    }

    @Test
    fun `the pinyin the learner sees travels with the request`() {
        service.play(word(tea, hanzi = "长", pinyin = "cháng"))

        val request = provider.spoken.single()
        assertEquals("长", request.text)
        assertEquals("chang", request.reading)
        assertEquals("tone 2 is the rising one on chang", 2, request.toneNumber)
    }

    @Test
    fun `the neutral tone is reported as zero rather than guessed`() {
        service.play(PronunciationRequest.forWord(tea, hanzi = "的", pinyin = "de"))

        assertEquals("de", provider.spoken.single().reading)
        assertEquals(0, provider.spoken.single().toneNumber)
    }

    // ---- loading -------------------------------------------------------------------------------------

    @Test
    fun `a request is loading until the engine says it started`() {
        service.play(word(tea))

        assertEquals(
            "there is a real gap between asking and hearing, and the UI must be able to see it",
            PronunciationState.Loading(tea),
            service.state.value
        )
    }

    @Test
    fun `a tap while the engine is still warming up is queued rather than lost`() {
        provider.setAvailability(ProviderAvailability.Preparing)
        service.play(word(tea))

        assertEquals("the request must not be discarded", 1, provider.spoken.size)
        assertEquals(PronunciationState.Loading(tea), service.state.value)
    }

    @Test
    fun `availability is published before anything is tapped`() {
        provider.setAvailability(
            ProviderAvailability.Failed(PronunciationFailure.MandarinUnavailable)
        )
        val fresh = PronunciationService(provider)

        assertEquals(
            "a screen must be able to explain the button before it is pressed",
            ProviderAvailability.Failed(PronunciationFailure.MandarinUnavailable),
            fresh.availability.value
        )
    }

    // ---- failure -------------------------------------------------------------------------------------

    @Test
    fun `a playback error is reported instead of being swallowed`() {
        service.play(word(tea))
        provider.fail(PronunciationFailure.Playback("engine said no"))

        val state = service.state.value
        assertTrue("a failure must reach the learner, not vanish", state is PronunciationState.Failed)
        assertEquals(tea, (state as PronunciationState.Failed).sourceId)
    }

    @Test
    fun `a device with no Mandarin voice never reaches the provider`() {
        provider.setAvailability(
            ProviderAvailability.Failed(PronunciationFailure.MandarinUnavailable)
        )
        service.play(word(tea))

        assertTrue("there is no point asking an engine that cannot speak", provider.spoken.isEmpty())
        val state = service.state.value as PronunciationState.Failed
        assertEquals(
            "the learner is told the thing they can actually fix",
            PronunciationFailure.MandarinUnavailable,
            state.failure
        )
    }

    @Test
    fun `a missing Mandarin voice and a missing engine are reported differently`() {
        // They need different remedies - install a language pack versus none - so collapsing them
        // into one "audio failed" would send the learner to reinstall the app for a system setting.
        val noVoice = PronunciationFailure.MandarinUnavailable
        val noEngine = PronunciationFailure.EngineUnavailable

        assertFalse(noVoice.message == noEngine.message)
        assertTrue(noVoice.message.contains("system settings"))
    }

    @Test
    fun `blank text is refused without troubling the engine`() {
        service.play(PronunciationRequest.forWord(tea, hanzi = "", pinyin = ""))

        assertTrue(provider.spoken.isEmpty())
        val state = service.state.value as PronunciationState.Failed
        assertEquals(PronunciationFailure.NothingToSay, state.failure)
    }

    // ---- replay --------------------------------------------------------------------------------------

    @Test
    fun `replay repeats the last request`() {
        service.play(word(tea))
        provider.start()
        provider.complete()

        assertTrue(service.replay())

        assertEquals("the same word, twice", 2, provider.spoken.size)
        assertEquals(provider.spoken[0], provider.spoken[1])
    }

    @Test
    fun `replay is refused when there is nothing to replay`() {
        assertFalse(
            "a replay control with nothing to replay should be disabled, not inert",
            service.replay()
        )
        assertTrue(provider.spoken.isEmpty())
    }

    @Test
    fun `replay uses the updated rate preference`() {
        service.play(word(tea))
        service.setSlow(true)
        service.replay()

        assertEquals(PronunciationRequest.Rate.SLOW, provider.spoken.last().rate)
    }

    @Test
    fun `the rate preference travels with the request instead of a separate setter`() {
        service.setSlow(true)
        service.play(word(tea))

        assertEquals(
            "one value applied once, so the toggle cannot be silently overwritten",
            PronunciationRequest.Rate.SLOW,
            provider.spoken.single().rate
        )
        assertTrue(service.isSlowTts)
    }

    @Test
    fun `sentences are requested as sentences`() {
        service.play(PronunciationRequest.forSentence(tea, "我学中文。"))

        assertEquals(PronunciationRequest.Kind.SENTENCE, provider.spoken.single().kind)
    }

    // ---- multiple cards -------------------------------------------------------------------------------

    @Test
    fun `starting a second card supersedes the first`() {
        service.play(word(tea))
        provider.start()
        service.play(word(water, hanzi = "水", pinyin = "shuǐ"))

        assertEquals(2, provider.spoken.size)
        assertEquals(PronunciationState.Loading(water), service.state.value)
    }

    @Test
    fun `a late completion from the previous card cannot clear the new one`() {
        service.play(word(tea))
        val firstListener = provider.currentListener()

        service.play(word(water, hanzi = "水", pinyin = "shuǐ"))
        provider.start()
        assertEquals(PronunciationState.Playing(water), service.state.value)

        // The card that was swiped away finishes speaking after the learner moved on.
        firstListener.onCompleted()

        assertEquals(
            "a stale callback must not blank the indicator belonging to the word on screen",
            PronunciationState.Playing(water),
            service.state.value
        )
    }

    @Test
    fun `a late failure from the previous card cannot replace the new one`() {
        service.play(word(tea))
        val firstListener = provider.currentListener()

        service.play(word(water, hanzi = "水", pinyin = "shuǐ"))
        provider.start()

        firstListener.onFailed(PronunciationFailure.Playback("engine gave up"))

        assertEquals(
            "an error about a word that is no longer shown is not this learner's problem",
            PronunciationState.Playing(water),
            service.state.value
        )
    }

    @Test
    fun `every state is keyed to the word it belongs to`() {
        service.play(word(tea))
        assertEquals(tea, service.state.value.sourceId)

        provider.start()
        assertEquals(tea, service.state.value.sourceId)

        service.play(word(water, hanzi = "水", pinyin = "shuǐ"))
        assertEquals(water, service.state.value.sourceId)
    }

    @Test
    fun `an idle state has no word attached`() {
        assertNull(
            "a button must be able to tell that nothing is playing for its word",
            PronunciationState.Idle.sourceId
        )
    }

    // ---- stopping and shutdown -------------------------------------------------------------------------

    @Test
    fun `stopping clears the state and silences a completion still in flight`() {
        service.play(word(tea))
        val listener = provider.currentListener()
        provider.start()

        service.stop()

        assertEquals(1, provider.stopCount)
        assertEquals(PronunciationState.Idle, service.state.value)

        listener.onCompleted()
        assertEquals(PronunciationState.Idle, service.state.value)
    }

    @Test
    fun `shutdown releases the engine`() {
        service.shutdown()

        assertTrue(provider.isClosed)
        assertEquals(PronunciationState.Idle, service.state.value)
    }

    @Test
    fun `replaying after a stop still works, because stop is not shutdown`() {
        service.play(word(tea))
        service.stop()

        assertTrue(service.replay())
        assertFalse(provider.isClosed)
    }
}
