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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed class AuthResult {
    data class Success(val user: UserEntity, val token: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

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
        return AuthResult.Success(savedUser, token)
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
            return AuthResult.Error("No account found with this identifier")
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
            return AuthResult.Error("Incorrect password")
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
        return AuthResult.Success(updatedUser, newToken)
    }

    /**
     * The shared guest profile.
     *
     * Identified by `authType = ANONYMOUS` rather than by a reserved email address, so the
     * guest slot no longer occupies a real-looking identifier that someone could try to
     * register. Its password hash is still a real PBKDF2 hash of a fixed local value, because
     * it is never used to authenticate — the guest button is the only way in.
     */
    suspend fun loginAsGuest(): UserEntity {
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
                        ?: error("Guest profile could not be created")
                }
            }
        _currentUser.value = guest
        return guest
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

    companion object {
        /**
         * The value hashed into the guest profile. It is a local placeholder, not a secret:
         * there is no remote account behind it and no code path accepts it as a credential.
         */
        const val GUEST_LOCAL_SECRET = "guest-local-placeholder-not-a-credential"
    }
}
