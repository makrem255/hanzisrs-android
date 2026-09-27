package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.db.UserDao
import com.example.data.model.StorageValues
import com.example.data.model.StreakEntity
import com.example.data.model.UserEntity
import com.example.data.model.UserPreferenceEntity
import com.example.data.model.UserProfileEntity
import com.example.util.PasswordHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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
 */
class UserRepository(
    private val userDao: UserDao,
    private val database: AppDatabase? = null
) {
    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    suspend fun autoLogin(): UserEntity? {
        // Profiles are local to this device; do not silently select one at launch.
        // A real persistent session requires a backend-issued credential.
        return null
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

        val token = "local_profile_${UUID.randomUUID()}"
        val newUser = UserEntity(
            identifier = trimmedId,
            identifierNormalized = normalized,
            authType = if (isPhone) StorageValues.AuthType.PHONE.storageValue else StorageValues.AuthType.EMAIL.storageValue,
            passwordHash = PasswordHasher.createHash(passwordPlain),
            displayName = displayName.ifBlank {
                if (isPhone) "Learner $trimmedId" else trimmedId.substringBefore("@")
            },
            token = token
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

    suspend fun login(
        identifier: String,
        passwordPlain: String
    ): AuthResult {
        val user = userDao.findByIdentifier(identifier.trim().lowercase())
            ?: return AuthResult.Error("No account found with this identifier")

        // Passwords are verified against the stored PBKDF2 hash. The only tolerated
        // legacy format is a bare SHA-256 digest from an older build, which is
        // transparently upgraded to PBKDF2 on successful login.
        //
        // There is deliberately no hard-coded demo credential bypass here: a
        // magic-string password check is an authentication backdoor, and the seeded
        // demo profile already stores a real PBKDF2 hash.
        val validPassword = PasswordHasher.verify(passwordPlain, user.passwordHash) ||
            (PasswordHasher.isLegacySha256(user.passwordHash) &&
                PasswordHasher.legacySha256Matches(passwordPlain, user.passwordHash))
        if (!validPassword) {
            return AuthResult.Error("Incorrect password")
        }

        val newToken = "local_profile_${UUID.randomUUID()}"
        val upgradedHash =
            if (PasswordHasher.isLegacySha256(user.passwordHash)) PasswordHasher.createHash(passwordPlain)
            else user.passwordHash
        userDao.updateCredentials(user.id, upgradedHash, newToken, System.currentTimeMillis())
        val updatedUser = user.copy(passwordHash = upgradedHash, token = newToken)
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
                val guestUser = UserEntity(
                    identifier = "Guest Learner",
                    identifierNormalized = StorageValues.AuthType.ANONYMOUS.storageValue,
                    authType = StorageValues.AuthType.ANONYMOUS.storageValue,
                    passwordHash = PasswordHasher.createHash(GUEST_LOCAL_SECRET),
                    displayName = "Guest Learner",
                    token = "local_guest_${UUID.randomUUID()}",
                    isGuest = true
                )
                val id = try {
                    insertWithPerUserRows(guestUser)
                } catch (conflict: android.database.sqlite.SQLiteConstraintException) {
                    // Another process created it first; fall through to the re-read below.
                    -1L
                }
                if (id > 0) {
                    guestUser.copy(id = id)
                } else {
                    userDao.findFirstByAuthType(StorageValues.AuthType.ANONYMOUS.storageValue)
                        ?: error("Guest profile could not be created")
                }
            }
        _currentUser.value = guest
        return guest
    }

    fun logout() {
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
