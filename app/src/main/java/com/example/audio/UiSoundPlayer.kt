package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.annotation.RawRes
import com.example.R

/**
 * The app's short interface sounds.
 *
 * Each one is synthesised by `tools/generate_ui_sounds.py` in this repository rather than taken
 * from a sound library, so the project owns them outright: there is no licence to honour and no
 * attribution to carry for a 70ms click. Re-running the script reproduces them byte for byte.
 */
enum class UiSound(@RawRes val rawRes: Int) {
    /** An ordinary tap. Soft, low body, no pitch - it acknowledges a press without claiming anything happened. */
    Tap(R.raw.ui_tap),

    /** Starting a session: two notes a fifth apart, "here we go". */
    Start(R.raw.ui_start),

    /** A word was recognised. Rising three notes, bright and short. */
    Success(R.raw.ui_success),

    /** A sitting finished. Settles rather than shouts. */
    Complete(R.raw.ui_complete),

    /** A word arriving on screen. A single bell. */
    NewWord(R.raw.ui_newword),

    /** An achievement unlocked. The only genuinely celebratory sound in the set. */
    Achievement(R.raw.ui_achievement),
}

/**
 * Plays the interface sounds, and can be turned off by the learner.
 *
 * ## Why [SoundPool] and not the text-to-speech engine or `MediaPlayer`
 *
 * These are 70 to 700 millisecond files played in response to a tap. [SoundPool] keeps them
 * decoded and resident so `play()` returns in well under a frame, which is the difference between
 * a sound that feels like part of the press and one that arrives late enough to feel like a
 * separate event. [android.media.MediaPlayer] decodes on demand and costs tens of milliseconds;
 * the speech engine synthesises, which is measured in hundreds.
 *
 * ## Why the pool is created lazily
 *
 * A learner with sounds turned off should never pay for the feature at all. The [SoundPool] and
 * its six loaded samples exist from the first [play] that is actually allowed to happen, and not
 * before.
 *
 * ## Stream choice
 *
 * `USAGE_GAME` rather than `USAGE_ASSISTANCE_SONIFICATION`. Both are defensible, but
 * sonification follows the system's "touch sounds" and "system sounds" settings, which this app
 * neither controls nor can read - and a learner who has those muted would see "Sound Effects: On"
 * and hear nothing, which reads as a broken toggle. Following the media volume puts these sounds
 * under the same slider as the app's pronunciation audio, so one control governs everything the
 * app makes audible.
 */
class UiSoundPlayer(private val context: Context) {

    /**
     * Whether sounds are allowed to play at all.
     *
     * Read on every [play] rather than checked once, so flipping the setting takes effect on the
     * next tap instead of on the next launch. Volatile because the view model writes it from
     * whatever thread the preference change arrives on.
     */
    @Volatile
    var isEnabled: Boolean = true

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<UiSound, Int>()

    /**
     * Plays [sound] if - and only if - the learner has sounds enabled.
     *
     * Never throws and never blocks. An unloadable or not-yet-loaded sample is a no-op: the
     * consequence of a missed sound is a missed sound, which is the least costly failure mode
     * available to anything triggered by a button press.
     */
    fun play(sound: UiSound) {
        if (!isEnabled) return
        val pool = pool() ?: return
        val id = soundIds[sound] ?: return
        runCatching { pool.play(id, VOLUME, VOLUME, 1, 0, 1f) }
            .onFailure { Log.w("UiSound", "play failed for $sound", it) }
    }

    /** Releases the native pool. Idempotent; a later [play] simply recreates it. */
    fun release() {
        runCatching { soundPool?.release() }
            .onFailure { Log.w("UiSound", "release failed", it) }
        soundPool = null
        soundIds.clear()
    }

    private fun pool(): SoundPool? {
        soundPool?.let { return it }
        return runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val created = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attributes)
                .build()
            UiSound.entries.forEach { sound ->
                soundIds[sound] = created.load(context, sound.rawRes, 1)
            }
            soundPool = created
            created
        }.onFailure {
            // A device that cannot build a SoundPool simply has no interface sounds. The setting
            // still works; it just has nothing to drive.
            Log.w("UiSound", "could not create SoundPool", it)
        }.getOrNull()
    }

    private companion object {
        /**
         * Peak level for the samples themselves. They were rendered with headroom already
         * (~0.68 full scale), so this lands them noticeably under the pronunciation audio - a
         * button click should never be as loud as a word the learner is trying to hear.
         */
        const val VOLUME = 0.55f
    }
}
