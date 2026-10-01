package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.auth.SessionStore
import com.example.data.auth.SessionToken
import com.example.data.db.AppDatabase
import com.example.data.db.UserDao
import com.example.data.model.StorageValues
import com.example.data.model.StreakEntity
import com.example.data.model.UserEntity
import com.example.data.model.UserPreferenceEntity
import com.example.data.model.UserProfileEntity
import com.example.util.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * The outcome of any attempt to become a learner: sign in, register, or continue as guest.
 *
 * All three speak this one type, which is the point. [login] and [register] returned it while
 * [loginAsGuest] returned a bare [UserEntity] and had no way to report failure at all, so the
 * guest button needed a separate path in the view model and could not surface an error even in
 * principle. One type means one place decides what a failure looks like.
 */
sealed class AuthResult {
    /**
     * The learner is now signed in on this device.
     *
     * There is deliberately no `token` field. The raw token is written to [SessionStore] by the
     * method that minted it, before this is constructed, and only its SHA-256 digest ever
     * reaches the database — so a token travelling back out to a caller was a secret handed to
     * code that had no use for it, and nothing ever read it. Its absence is also what lets
     * [loginAsGuest] return this same type without inventing a value to put in that slot.
     */
    data class Success(val user: UserEntity) : AuthResult()

    /** The attempt did not succeed. [message] is written to be shown to the learner. */
    data class Error(val message: String) : AuthResult()
}

/**
 * The one sentence returned for a rejected sign-in, whether the identifier is unknown or the
 * password is wrong.
 *
 * A single constant rather than two literals, because the property being protected is that the
 * two are *equal*, and two identical strings sitting in two branches stay equal only until
 * someone edits one of them. It also reads wrong: a sentence naming the specific field that was
 * wrong is friendlier, and that friendliness is exactly the leak.
 *
 * `internal` so the parity can be asserted from a test without making the string public API.
 */
internal const val AUTH_FAILED_MESSAGE = "Incorrect email or password."

/**
 * Accounts, and the per-learner rows every account is entitled to.
 *
 * Two invariants are worth stating because the code is arranged around them:
 *
 *  - **Identifiers are unique case-insensitively, enforced by the database.** The check below
 *    exists to return a readable message; the unique index on `identifierNormalized` is what
 *    actually decides, and the `SQLiteConstraintException` catch is what turns the race
 *    between two simultaneous registrations into the same friendly error.
 *  - **Creating an account also creates its profile, preferences and streak**, in one
 *    transaction. Those tables have a unique index on `userId` precisely because they are
 *    one-per-user, so a half-created account would be a user the settings screen cannot read.
 *  - **The session survives process death.** A sign-in mints a random token; the raw token goes
 *    to [SessionStore] in app-private preferences and only its SHA-256 digest is written to
 *    `users.token`. [autoLogin] re-validates the pointer against the digest at launch. Both
 *    halves were already written ([SessionToken], [SessionStore], and `UserDao.findByToken`)
 *    but nothing called them, so `autoLogin` returned `null` unconditionally and the app opened
 *    on the sign-in screen on every single launch — a learner had to identify themselves again
 *    and again, and the "return later" half of the journey was impossible.
 */
class UserRepository(
    private val userDao: UserDao,
    private val database: AppDatabase? = null,
    /**
     * Absent only where there is no `Context` to build one from — narrow unit tests, as with
     * [database]. Authentication still works without it; the session simply does not outlive the
     * process, which a test that never launches a second process cannot observe.
     */
    private val sessionStore: SessionStore? = null
) {
    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    /**
     * Re-establishes the session from the token this device stored at the last sign-in.
     *
     * Returns `null` — and forgets the pointer — when there is nothing stored, or when the token
     * no longer resolves to an account. A stale pointer is cleared rather than retried forever,
     * because the usual cause is a sign-out or an account deleted elsewhere, and leaving it in
     * place would make every launch pay a query that can only fail.
     */
    suspend fun autoLogin(): UserEntity? {
        val stored = sessionStore?.readToken() ?: return null
        val user = userDao.findByToken(SessionToken.hash(stored))
        if (user == null) {
            sessionStore?.clear()
            return null
        }
        _currentUser.value = user
        return user
    }

    suspend fun register(
        identifier: String,
        passwordPlain: String,
        displayName: String,
        isPhone: Boolean
    ): AuthResult {
        val trimmedId = identifier.trim()
        if (trimmedId.isBlank()) {
            return AuthResult.Error("Identifier cannot be empty")
        }
        if (passwordPlain.length < 8) {
            return AuthResult.Error("Password must be at least 8 characters")
        }
        if (isPhone && !trimmedId.matches(Regex("^\\+?[0-9 ()-]{7,20}$"))) {
            return AuthResult.Error("Enter a valid phone number")
        }
        if (!isPhone && !trimmedId.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
            return AuthResult.Error("Enter a valid email address")
        }

        // The unique index is on this column, not on `identifier`, so that Learner@x.com and
        // learner@x.com cannot become two accounts.
        val normalized = trimmedId.lowercase()
        if (userDao.findByIdentifier(normalized) != null) {
            return AuthResult.Error(
                "Account already exists with this ${if (isPhone) "phone number" else "email"}"
            )
        }

        val token = SessionToken.generate()
        // PBKDF2 at 210_000 iterations is hundreds of milliseconds of pure CPU. Callers reach
        // this from `viewModelScope`, which is `Dispatchers.Main.immediate`, and `withTransaction`
        // only moves the *database* work off the main thread - a `SecretKeyFactory` call inside a
        // transaction still blocks the thread. So it is moved explicitly here, at the boundary
        // that knows this runs on Android. [PasswordHasher] stays a plain synchronous utility so
        // it stays trivially testable.
        val passwordHash = withContext(Dispatchers.Default) {
            PasswordHasher.createHash(passwordPlain)
        }
        val newUser = UserEntity(
            identifier = trimmedId,
            identifierNormalized = normalized,
            authType = if (isPhone) StorageValues.AuthType.PHONE.storageValue else StorageValues.AuthType.EMAIL.storageValue,
            passwordHash = passwordHash,
            displayName = displayName.ifBlank {
                if (isPhone) "Learner $trimmedId" else trimmedId.substringBefore("@")
            },
            token = SessionToken.hash(token)
        )

        val newId = try {
            insertWithPerUserRows(newUser)
        } catch (conflict: android.database.sqlite.SQLiteConstraintException) {
            // Lost the race against a concurrent registration for the same identifier. The
            // unique index caught what the pre-check could not.
            return AuthResult.Error(
                "Account already exists with this ${if (isPhone) "phone number" else "email"}"
            )
        }

        val savedUser = newUser.copy(id = newId)
        // Registering is itself a sign-in: the learner has just proven who they are, and
        // sending them straight to the sign-in form to type it all again would be absurd.
        sessionStore?.writeToken(token)
        _currentUser.value = savedUser
        return AuthResult.Success(savedUser)
    }

    /**
     * Inserts the account and its per-user rows atomically.
     *
     * Without a database handle (as in a narrow unit test) only the account row is written,
     * which is why [database] is nullable rather than required.
     */
    private suspend fun insertWithPerUserRows(user: UserEntity): Long {
        val db = database ?: return userDao.insertUser(user)
        return db.withTransaction {
            val id = userDao.insertUser(user)
            val now = System.currentTimeMillis()
            db.userProfileDao().insert(UserProfileEntity(userId = id, createdAt = now, updatedAt = now))
            db.userPreferenceDao().insert(UserPreferenceEntity(userId = id, updatedAt = now))
            db.streakDao().insertIfAbsent(StreakEntity(userId = id, updatedAt = now))
            id
        }
    }

    /**
     * Verifies a password and establishes a session.
     *
     * ### Backoff
     *
     * Wrong passwords are rate-limited by [SessionStore] with an exponential delay, capped at
     * fifteen minutes. This is a local account, but it is still a guessable one: a human-chosen
     * password checked against a hash, with no server-side counter to slow an automated
     * guessing run. The counter lives in preferences rather than the database so its presence
     * cannot be used to learn which identifiers exist on the device, and it is keyed to the
     * identifier currently being tried rather than to every identifier ever tried, so it cannot
     * grow without bound.
     *
     * It is a delay and never a permanent lock, because there is no password-reset path here and
     * a permanent lockout would be an account the learner can never get back into.
     */
    suspend fun login(
        identifier: String,
        passwordPlain: String
    ): AuthResult {
        val normalized = identifier.trim().lowercase()
        val now = System.currentTimeMillis()

        val lockoutRemaining = sessionStore?.lockoutMillisFor(normalized, now) ?: 0L
        if (lockoutRemaining > 0L) {
            val seconds = (lockoutRemaining + 999L) / 1000L
            return AuthResult.Error(
                "Too many failed attempts. Try again in ${seconds}s."
            )
        }

        val user = userDao.findByIdentifier(normalized)
        if (user == null) {
            // Counted as a failure even though nothing was verified. Doing otherwise would make
            // the delay a clean oracle for "does this account exist": guessing stops costing
            // anything the moment the identifier is wrong.
            sessionStore?.recordFailure(normalized, now)
            // The *same* sentence as a wrong password, below. The failure counter is symmetric
            // across the two branches, which is what stops the backoff from leaking existence —
            // and returning a distinct message here undid exactly that, because the response a
            // caller gets is the message, not the delay. Anyone with a list of candidate
            // addresses could tell which were registered, from a field meant only to reject
            // them. One sentence, two causes.
            return AuthResult.Error(AUTH_FAILED_MESSAGE)
        }

        // Passwords are verified against the stored PBKDF2 hash. The only tolerated
        // legacy format is a bare SHA-256 digest from an older build, which is
        // transparently upgraded to PBKDF2 on successful login.
        //
        // There is deliberately no hard-coded demo credential bypass here: a
        // magic-string password check is an authentication backdoor, and the seeded
        // demo profile already stores a real PBKDF2 hash.
        // Off the main thread, for the same reason as in `register`: 210_000 PBKDF2 iterations is
        // not something to run on the thread that is also drawing the sign-in form.
        val validPassword = withContext(Dispatchers.Default) {
            PasswordHasher.verify(passwordPlain, user.passwordHash) ||
            (PasswordHasher.isLegacySha256(user.passwordHash) &&
                PasswordHasher.legacySha256Matches(passwordPlain, user.passwordHash))
        }
        if (!validPassword) {
            sessionStore?.recordFailure(normalized, now)
            return AuthResult.Error(AUTH_FAILED_MESSAGE)
        }

        // A fresh token per sign-in, so a token captured earlier stops working the moment the
        // learner signs in again. Rotation is what makes one-token-per-user safe to keep.
        val newToken = SessionToken.generate()
        val upgradedHash =
            if (PasswordHasher.isLegacySha256(user.passwordHash)) withContext(Dispatchers.Default) {
                PasswordHasher.createHash(passwordPlain)
            }
            else user.passwordHash
        userDao.updateCredentials(user.id, upgradedHash, SessionToken.hash(newToken), now)
        val updatedUser = user.copy(passwordHash = upgradedHash, token = SessionToken.hash(newToken))
        sessionStore?.clearFailures(normalized)
        sessionStore?.writeToken(newToken)
        _currentUser.value = updatedUser
        return AuthResult.Success(updatedUser)
    }

    /**
     * The shared guest profile.
     *
     * Identified by `authType = ANONYMOUS` rather than by a reserved email address, so the
     * guest slot no longer occupies a real-looking identifier that someone could try to
     * register. Its password hash is still a real PBKDF2 hash of a fixed local value, because
     * it is never used to authenticate — the guest button is the only way in.
     *
     * Returns the same [AuthResult] as [login] and [register]. That type is the only reason this
     * can report a failure: it previously ended in `error("Guest profile could not be created")`,
     * an `IllegalStateException` thrown out of a repository into a `viewModelScope.launch` that
     * did not catch it, so the one path where the guest row could not be read took the process
     * down instead of asking for a retry.
     */
    suspend fun loginAsGuest(): AuthResult {
        val guest = userDao.findFirstByAuthType(StorageValues.AuthType.ANONYMOUS.storageValue)
            ?: run {
                val guestToken = SessionToken.generate()
                val guestUser = UserEntity(
                    identifier = "Guest Learner",
                    identifierNormalized = StorageValues.AuthType.ANONYMOUS.storageValue,
                    authType = StorageValues.AuthType.ANONYMOUS.storageValue,
                    passwordHash = withContext(Dispatchers.Default) {
                        PasswordHasher.createHash(GUEST_LOCAL_SECRET)
                    },
                    displayName = "Guest Learner",
                    token = SessionToken.hash(guestToken),
                    isGuest = true
                )
                val id = try {
                    insertWithPerUserRows(guestUser)
                } catch (conflict: android.database.sqlite.SQLiteConstraintException) {
                    // Another process created it first; fall through to the re-read below.
                    -1L
                }
                if (id > 0) {
                    sessionStore?.writeToken(guestToken)
                    guestUser.copy(id = id)
                } else {
                    // Someone else won the race, so their row is the guest profile and their
                    // token is the live one. Re-read it and adopt it rather than minting a
                    // second one, which would leave a live token in the database that nothing
                    // can present and silently replace the winner's.
                    //
                    // The consequence is that this one race cannot persist a session: the raw
                    // token for a row this process did not mint is unrecoverable by design. The
                    // guest still works for this process, and the next launch asks for the guest
                    // button again — which is the honest outcome, rather than storing a fabricated
                    // token that would fail validation on the next launch anyway.
                    userDao.findFirstByAuthType(StorageValues.AuthType.ANONYMOUS.storageValue)
                }
            }
        // Reachable only if the insert lost the race *and* the winner's row was deleted before
        // this read. Reported rather than thrown, so the guest button can say so and try again.
        return if (guest == null) {
            AuthResult.Error("Could not open the guest profile. Please try again.")
        } else {
            _currentUser.value = guest
            AuthResult.Success(guest)
        }
    }

    /**
     * Ends the session.
     *
     * The database row stays — the learner's words, progress and streak are not thrown away by
     * signing out — but the device's pointer to it is dropped, so the next launch starts at the
     * sign-in screen instead of quietly re-entering the account that was just left.
     */
    fun logout() {
        sessionStore?.clear()
        _currentUser.value = null
    }

    fun setCurrentUser(user: UserEntity) {
        _currentUser.value = user
    }

    // ---- study settings ----------------------------------------------------------------------------------

    /**
     * The learner's own daily caps, observed.
     *
     * Emits `null` while there is no signed-in learner, and for the brief window where a profile
     * exists but its preferences row has not been read yet — so a caller can tell "no limit set"
     * from "not loaded", which is the difference between showing a default and showing a lie.
     * The defaults themselves are the entity's, and the dashboard applies the same two
     * fallbacks (`COALESCE(..., 10)` / `COALESCE(..., 60)`) on its own side, so an absent row
     * degrades to one agreed set of numbers rather than two.
     */
    fun observePreferences(userId: Long): Flow<UserPreferenceEntity?> =
        database?.userPreferenceDao()?.observeForUser(userId) ?: emptyFlow()

    /**
     * Sets the daily new-word cap, leaving every other preference as it is.
     *
     * Read-modify-write inside one transaction rather than a narrow `UPDATE`, because
     * [UserPreferenceDao.updateForUser] sets every column at once. Two concurrent writes to
     * different settings would otherwise each read the same "before" row and the loser's change
     * would be silently discarded. The whole-row write is the DAO's contract; doing the read
     * inside the same transaction is what makes it safe to use.
     *
     * The range is clamped rather than rejected, and it clamps to [MAX_DAILY_LIMIT] rather than to
     * the smaller number the stepper offers. This is a stepper in the settings screen, so an
     * out-of-range value can only arrive from a bug or a restored backup — but a learner whose
     * stored value is already above what the stepper reaches must be able to step *down* from it
     * without the write quietly flooring them at the stepper's ceiling first. Silently ignoring a
     * learner's setting is worse than storing the nearest legal one.
     */
    suspend fun setDailyNewWordLimit(userId: Long, limit: Int) =
        updatePreferences(userId) { it.copy(dailyNewWordLimit = limit.coerceIn(0, MAX_DAILY_LIMIT)) }

    /** As [setDailyNewWordLimit], for the daily review cap. */
    suspend fun setDailyReviewLimit(userId: Long, limit: Int) =
        updatePreferences(userId) { it.copy(dailyReviewLimit = limit.coerceIn(0, MAX_DAILY_LIMIT)) }

    private suspend fun updatePreferences(
        userId: Long,
        transform: (UserPreferenceEntity) -> UserPreferenceEntity
    ) {
        val db = database ?: return
        val dao = db.userPreferenceDao()
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = dao.getForUser(userId) ?: UserPreferenceEntity(userId = userId)
            val updated = transform(existing).copy(userId = userId, updatedAt = now)
            // Insert-then-update, because the surrogate primary key makes `@Upsert` insert a
            // second row and trip the unique index on `userId`. The reason is written out on
            // `UserProfileDao`; this is the same rule in the same shape.
            if (existing.id == 0L) dao.insert(updated) else dao.updateForUser(
                userId = updated.userId,
                themeMode = updated.themeMode,
                ttsSpeed = updated.ttsSpeed,
                dailyNewWordLimit = updated.dailyNewWordLimit,
                dailyReviewLimit = updated.dailyReviewLimit,
                remindersEnabled = updated.remindersEnabled,
                reminderHour = updated.reminderHour,
                showPinyin = updated.showPinyin,
                showStrokeOrder = updated.showStrokeOrder,
                now = now
            )
        }
    }

    companion object {
        /**
         * The largest daily cap the write path will store.
         *
         * `Validator.MAX_DAILY_WORD_LIMIT`, reused rather than restated, because the app should
         * have exactly one statement of what a legal daily limit is.
         *
         * This started out as a second, tighter constant (50) borrowed from what the settings
         * stepper offers, which is a different question and had a visible consequence: a learner
         * whose stored value was 100 — reachable through the registration path, which validates
         * to 200 — would see 100 in settings, press "−" once, and have it silently rewritten to
         * 50 as the write clamped on the way down. One tap and a third of their workload
         * disappeared with no message.
         *
         * How far the UI *offers* to go is a presentation decision and lives with the stepper
         * ([OFFERED_MAX_NEW_WORDS]). What is *legal* belongs here.
         */
        const val MAX_DAILY_LIMIT = Validator.MAX_DAILY_WORD_LIMIT

        /**
         * The most new words the settings stepper offers, which is well below [MAX_DAILY_LIMIT].
         *
         * A stepper that runs to 200 teaches the wrong idea about what a day's load looks like.
         * Offering 50 and accepting 200 means the ceiling is a nudge rather than a limit, and a
         * value above it — from an older build or a restored backup — still reads back unchanged.
         */
        const val OFFERED_MAX_NEW_WORDS = 50

        /** As [OFFERED_MAX_NEW_WORDS], for reviews. Reviews are words already known, so more is reasonable. */
        const val OFFERED_MAX_REVIEWS = 200

        /**
         * The value hashed into the guest profile. It is a local placeholder, not a secret:
         * there is no remote account behind it and no code path accepts it as a credential.
         */
        const val GUEST_LOCAL_SECRET = "guest-local-placeholder-not-a-credential"
    }
}
