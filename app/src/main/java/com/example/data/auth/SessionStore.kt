package com.example.data.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the active session lives between launches, plus the record of failed sign-in attempts.
 *
 * This is the piece that was missing. `users.token` was written on every successful sign-in and
 * read by nobody, so the app opened on the sign-in screen every single launch and the learner
 * was asked to identify themselves again and again.
 *
 * ## Why SharedPreferences and not DataStore
 *
 * DataStore is the modern answer, and `build.gradle.kts` already carries a commented-out
 * dependency on it. It is still commented out, and this class holds three scalars behind a
 * synchronous API that is only ever called from a coroutine. Adding a dependency, an async API
 * and a migration of the existing preference reads to save one `String` would be cost without
 * benefit. If preference storage grows into something that needs to be observed reactively,
 * swapping this class for DataStore is a contained change precisely because nothing else knows
 * how the token is stored.
 *
 * ## What is stored
 *
 * The raw token and nothing else identifying. The digest, the expiry and the account live in the
 * database, which is the authority; this is only the pointer that survives process death.
 *
 * The failed-attempt counters are here rather than in the database on purpose. They are
 * deliberately *not* durable per-account state: a lockout that resets when the process dies is
 * still worth far more than none against an automated attacker, and keeping them out of the
 * schema means an attacker cannot use a row's presence to learn which identifiers exist.
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---- the active session ---------------------------------------------------------------

    /** The raw token to re-validate at launch, or null if this device has no session. */
    fun readToken(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Records a newly issued session, replacing any previous one. */
    fun writeToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    /**
     * Forgets the session pointer.
     *
     * Called on sign-out and whenever a stored token turns out to be unusable. The database row
     * is cleared separately by the caller; this only drops the local pointer, so the two can be
     * reached from different failure paths.
     */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    // ---- failed sign-in attempts ----------------------------------------------------------

    /**
     * How long [identifierNormalized] must wait before another attempt is accepted, or 0.
     *
     * The counter is keyed to whichever identifier was tried most recently rather than to every
     * identifier ever tried. That is a deliberate limit: a per-identifier map in preferences
     * grows without bound and leaks which accounts exist on the device, and the threat this
     * addresses — an automated attacker grinding one account — is fully covered by tracking
     * the current target.
     */
    fun lockoutMillisFor(identifierNormalized: String, now: Long): Long {
        if (identifierNormalized != prefs.getString(KEY_LAST_IDENTIFIER, null)) return 0L
        val until = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        val remaining = until - now
        return if (remaining > 0L) remaining else 0L
    }

    /** The number of consecutive failures recorded against the current identifier. */
    fun failureCount(identifierNormalized: String): Int {
        if (identifierNormalized != prefs.getString(KEY_LAST_IDENTIFIER, null)) return 0
        return prefs.getInt(KEY_FAILURES, 0)
    }

    /**
     * Records one more failure and applies the resulting backoff.
     *
     * Backoff is exponential in the failure count and capped, so a handful of typos costs the
     * learner a few seconds while a sustained guessing run is throttled to a few minutes per
     * attempt. It is not a hard lockout: there is deliberately no way to permanently lock
     * someone out of their own local account, because there is no password-reset path in this
     * app and a permanent lockout would be an unrecoverable state.
     */
    fun recordFailure(identifierNormalized: String, now: Long) {
        val count = failureCount(identifierNormalized) + 1
        val editor = prefs.edit()
            .putString(KEY_LAST_IDENTIFIER, identifierNormalized)
            .putInt(KEY_FAILURES, count)
        val lockout = lockoutForFailureCount(count)
        if (lockout > 0L) {
            editor.putLong(KEY_LOCKED_UNTIL, now + lockout)
        }
        editor.apply()
    }

    /** Clears the failure history after a successful sign-in. */
    fun clearFailures(identifierNormalized: String) {
        if (identifierNormalized != prefs.getString(KEY_LAST_IDENTIFIER, null)) return
        prefs.edit()
            .remove(KEY_FAILURES)
            .remove(KEY_LOCKED_UNTIL)
            .remove(KEY_LAST_IDENTIFIER)
            .apply()
    }

    companion object {
        private const val FILE_NAME = "hanzisrs_session"
        private const val KEY_TOKEN = "session_token"
        private const val KEY_FAILURES = "auth_failures"
        private const val KEY_LOCKED_UNTIL = "auth_locked_until"
        private const val KEY_LAST_IDENTIFIER = "auth_last_identifier"

        /** Attempts allowed before any delay applies. */
        const val FAILURES_BEFORE_BACKOFF = 5

        private const val BASE_BACKOFF_MILLIS = 30_000L
        private const val MAX_BACKOFF_MILLIS = 15 * 60_000L

        /**
         * The delay imposed after [failures] consecutive failures: 30s, doubling, capped at 15
         * minutes. Zero below [FAILURES_BEFORE_BACKOFF].
         *
         * The shift is guarded because a persisted count large enough to overflow `1L shl n`
         * would wrap negative and silently *reduce* the penalty, which is the opposite of the
         * intent.
         */
        fun lockoutForFailureCount(failures: Int): Long {
            if (failures < FAILURES_BEFORE_BACKOFF) return 0L
            val doublings = (failures - FAILURES_BEFORE_BACKOFF).coerceAtMost(20)
            val delay = BASE_BACKOFF_MILLIS shl doublings
            return if (delay <= 0L || delay > MAX_BACKOFF_MILLIS) MAX_BACKOFF_MILLIS else delay
        }
    }
}
