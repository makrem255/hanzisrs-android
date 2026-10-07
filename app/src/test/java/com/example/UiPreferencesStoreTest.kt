package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.settings.UiPreferencesStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sound-effect preference, on a real preferences file.
 *
 * Robolectric rather than a fake, because the thing under test is precisely that this setting is
 * *stored* - a fake `SharedPreferences` would prove only that the fake works. Two stores opened
 * over the same file have to agree, which is the property that makes the toggle survive leaving
 * the screen, and it cannot be demonstrated against an in-memory double.
 *
 * The pronunciation rate (`isSlowTts`) is deliberately absent here: it is an in-memory property
 * of [com.example.audio.PronunciationService] and is not persisted anywhere. The two are
 * separate settings about separate things and are kept in separate places on purpose - a
 * learner who wants words read aloud but no clicks is describing a real preference, not a
 * contradictory one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiPreferencesStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a learner who has never chosen otherwise gets interface sounds`() {
        assertTrue(
            "a feature that ships disabled has to be discovered before anyone hears it",
            UiPreferencesStore(context).soundEffectsEnabled,
        )
    }

    @Test
    fun `turning sounds off is recorded`() {
        val store = UiPreferencesStore(context)

        store.setSoundEffectsEnabled(false)

        assertFalse(store.soundEffectsEnabled)
    }

    @Test
    fun `the choice survives a fresh handle on the same file`() {
        UiPreferencesStore(context).setSoundEffectsEnabled(false)

        assertFalse(
            "a preference that only lives in one instance is not a preference",
            UiPreferencesStore(context).soundEffectsEnabled,
        )

        UiPreferencesStore(context).setSoundEffectsEnabled(true)

        assertTrue(UiPreferencesStore(context).soundEffectsEnabled)
    }

    @Test
    fun `two independent handles on the same file agree`() {
        val settings = UiPreferencesStore(context)
        val player = UiPreferencesStore(context)

        settings.setSoundEffectsEnabled(false)

        assertFalse(
            "the screen and the audio player read the same setting and cannot disagree",
            player.soundEffectsEnabled,
        )
    }
}
