package com.example.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.StorageValues

/**
 * Interface preferences that are neither relational nor part of a session.
 *
 * ## Why SharedPreferences and not the database
 *
 * The settings that live in `user_preferences` are there because they are read together with a
 * learner's rows and changed through the same repository path as their progress. A sound-effect
 * toggle is not any of that: it is a scalar about how this device should behave, read once when
 * the audio player is built, and it has no relationship to a word, a schedule or an account -
 * which is the same reasoning [com.example.data.auth.SessionStore] documents for the session
 * pointer.
 *
 * Putting it in `user_preferences` would have meant a schema migration, a `Migration_3_4`, a
 * change to the hand-written `UPDATE` in `UserPreferenceDao`, and a new case in the migration
 * tests - all to move one boolean between two stores that are equally durable. The cost is real
 * and the benefit is tidiness in a place where the existing tidy solution already exists for
 * exactly this reason.
 *
 * ## What is stored
 *
 * Interface sound preference only. Deliberately separate from the pronunciation and voice
 * settings: a learner who wants to hear words but not clicks - or clicks but not words - is
 * describing two different things, and one toggle would have to guess which.
 */
class UiPreferencesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * Whether interface sounds should play.
     *
     * Defaults to on. A feature that ships disabled and has to be discovered is a feature most
     * learners will never see, and these sounds are quiet enough by design that the default
     * costs nothing; anyone who dislikes them can turn them off once, permanently.
     */
    val soundEffectsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_EFFECTS, true)

    /** Records the learner's choice. Applied asynchronously, as preference writes should be. */
    fun setSoundEffectsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND_EFFECTS, enabled).apply()
    }

    /**
     * The interface theme: system, light or dark.
     *
     * Device-global like the sound toggle, for the same reason: it must apply before anyone
     * signs in and survive an account switch, and it has no relationship to a word, a
     * schedule or a learner. Stored as the enum's storage value; an unrecognised string -
     * from a downgrade or a hand-edited file - falls back to system rather than crashing the
     * theme resolution.
     */
    val themeMode: StorageValues.ThemeMode
        get() = StorageValues.ThemeMode.fromStorage(
            prefs.getString(KEY_THEME_MODE, null)
        ) ?: StorageValues.ThemeMode.SYSTEM

    /** Records the learner's choice. Applied asynchronously, as preference writes should be. */
    fun setThemeMode(mode: StorageValues.ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
    }

    private companion object {
        const val FILE_NAME = "hanzisrs_ui_prefs"
        const val KEY_SOUND_EFFECTS = "sound_effects_enabled"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
