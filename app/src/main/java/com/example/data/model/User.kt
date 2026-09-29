package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A local account. Holds credentials and nothing else.
 *
 * Learner-facing settings live in [UserPreferenceEntity] and editable attributes in
 * [UserProfileEntity], so this row stays a stable identity that the other tables can point
 * at without dragging mutable presentation state along with it.
 *
 * `identifierNormalized` exists because the uniqueness rule is case-insensitive, and Room
 * cannot express a collation on an index. Registration lower-cases into this column and the
 * unique index is taken out on it, which closes the check-then-insert race in
 * [com.example.data.repository.UserRepository] at the database level.
 */
@Entity(
    tableName = "users",
    indices = [
        Index(value = ["identifierNormalized"], unique = true)
    ]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val identifier: String, // Email or Phone number
    val identifierNormalized: String, // lower-cased identifier; unique
    val authType: String = StorageValues.AuthType.EMAIL.storageValue, // EMAIL | PHONE | ANONYMOUS
    val passwordHash: String,
    val displayName: String,
    /**
     * The SHA-256 digest of this device's session token, or empty when there is no session.
     *
     * A digest and not the token itself: the raw token lives only in
     * [com.example.data.auth.SessionStore], in app-private preferences, so a database file
     * lifted off a device cannot be replayed as a credential. Resolved by
     * `UserDao.findByToken` at launch.
     *
     * This is a local, device-bound credential and cannot be anything else - there is no server
     * in this app, and the password check that guards it is equally local.
     */
    val token: String,
    val isGuest: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
