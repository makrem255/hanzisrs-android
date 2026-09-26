package com.example.data.repository

import com.example.data.db.UserDao
import com.example.data.model.UserEntity
import com.example.util.PasswordHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

sealed class AuthResult {
    data class Success(val user: UserEntity, val token: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class UserRepository(private val userDao: UserDao) {
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

        val existing = userDao.findByIdentifier(trimmedId)
        if (existing != null) {
            return AuthResult.Error("Account already exists with this ${if (isPhone) "phone number" else "email"}")
        }

        val hash = PasswordHasher.createHash(passwordPlain)
        val token = "local_profile_${UUID.randomUUID()}"
        val newUser = UserEntity(
            identifier = trimmedId,
            authType = if (isPhone) "PHONE" else "EMAIL",
            passwordHash = hash,
            displayName = displayName.ifBlank { if (isPhone) "Learner $trimmedId" else trimmedId.substringBefore("@") },
            token = token
        )

        val newId = userDao.insertUser(newUser)
        val savedUser = newUser.copy(id = newId)
        _currentUser.value = savedUser
        return AuthResult.Success(savedUser, token)
    }

    suspend fun login(
        identifier: String,
        passwordPlain: String
    ): AuthResult {
        val trimmedId = identifier.trim()
        val user = userDao.findByIdentifier(trimmedId)
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
        val updatedUser = user.copy(
            passwordHash = upgradedHash,
            token = newToken
        )
        userDao.updateUser(updatedUser)
        _currentUser.value = updatedUser
        return AuthResult.Success(updatedUser, newToken)
    }

    suspend fun loginAsGuest(): UserEntity {
        var guest = userDao.findByIdentifier("guest@hanzisrs.com")
        if (guest == null) {
            val guestUser = UserEntity(
                identifier = "guest@hanzisrs.com",
                authType = "EMAIL",
                passwordHash = PasswordHasher.createHash("guest123"),
                displayName = "Guest Learner",
                token = "local_guest_${System.currentTimeMillis()}"
            )
            val id = userDao.insertUser(guestUser)
            guest = guestUser.copy(id = id)
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
}
