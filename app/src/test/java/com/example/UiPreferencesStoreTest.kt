package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.StorageValues
import com.example.data.settings.UiPreferencesStore
import org.junit.Assert.assertEquals
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

    // ---- theme mode --------------------------------------------------------------------------

    @Test
    fun `a learner who has never chosen otherwise follows the system`() {
        assertEquals(
            "the only default that respects a phone the app has never seen",
            StorageValues.ThemeMode.SYSTEM,
            UiPreferencesStore(context).themeMode,
        )
    }

    @Test
    fun `choosing a theme is recorded`() {
        val store = UiPreferencesStore(context)
        try {
            store.setThemeMode(StorageValues.ThemeMode.DARK)

            assertEquals(StorageValues.ThemeMode.DARK, store.themeMode)
        } finally {
            // The file is shared with every other test in this sandbox; leaving DARK
            // behind would make the default test below order-dependent.
            store.setThemeMode(StorageValues.ThemeMode.SYSTEM)
        }
    }

    @Test
    fun `the theme choice survives a fresh handle on the same file`() {
        try {
            UiPreferencesStore(context).setThemeMode(StorageValues.ThemeMode.LIGHT)

            assertEquals(
                "a theme that only lives in one instance resets on every restart",
                StorageValues.ThemeMode.LIGHT,
                UiPreferencesStore(context).themeMode,
            )
        } finally {
            UiPreferencesStore(context).setThemeMode(StorageValues.ThemeMode.SYSTEM)
        }
    }
}
