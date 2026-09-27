package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.StorageValues
import com.example.data.model.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    /**
     * Looks a user up by their lower-cased identifier.
     *
     * Matching the normalized column rather than the display one is what makes login
     * case-insensitive, and it is the same column the unique index sits on, so this lookup
     * and the uniqueness rule can never disagree.
     *
     * `lower()` is applied to the *parameter* rather than relying on the caller to normalise
     * first. SQLite compares TEXT case-sensitively, so a bare `= :parameter` only behaves
     * case-insensitively while every call site remembers to lowercase — the invariant lived in
     * convention, and a caller that forgot would silently get "no such account". Folding the
     * normalisation into the query makes the behaviour a property of the lookup instead.
     *
     * The function is on the bound value, not on the column, so the unique index on
     * `identifierNormalized` still serves this query.
     */
    @Query("SELECT * FROM users WHERE identifierNormalized = lower(:identifierNormalized) LIMIT 1")
    suspend fun findByIdentifier(identifierNormalized: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    suspend fun getUserById(userId: Long): UserEntity?

    @Query("SELECT * FROM users WHERE token = :token AND token <> '' LIMIT 1")
    suspend fun findByToken(token: String): UserEntity?

    @Query("SELECT * FROM users ORDER BY id ASC")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int

    /**
     * ABORT, not REPLACE.
     *
     * This is the second half of closing the registration race: `register` still checks for an
     * existing identifier first so it can return a friendly message, but two registrations can
     * pass that check concurrently. The unique index on `identifierNormalized` is what actually
     * decides, and ABORT surfaces the violation as an exception the repository translates.
     *
     * REPLACE here would be far worse than a failed insert: it deletes the conflicting user row
     * and cascades through every table that references `users`, destroying that account's
     * words, schedule, sessions and history.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: UserEntity): Long

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query(
        """
        UPDATE users
        SET passwordHash = :passwordHash, token = :token, updatedAt = :now
        WHERE id = :userId
        """
    )
    suspend fun updateCredentials(userId: Long, passwordHash: String, token: String, now: Long): Int

    @Query("UPDATE users SET displayName = :displayName, updatedAt = :now WHERE id = :userId")
    suspend fun updateDisplayName(userId: Long, displayName: String, now: Long): Int

    @Query("UPDATE users SET isGuest = :isGuest, updatedAt = :now WHERE id = :userId")
    suspend fun updateGuestFlag(userId: Long, isGuest: Boolean, now: Long): Int

    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteUser(userId: Long)

    /**
     * A registration that resolves to the shared guest profile must never collide with a real
     * account, so the guest identity is a reserved one.
     */
    @Query("SELECT * FROM users WHERE identifierNormalized = :guestIdentifier LIMIT 1")
    suspend fun findGuest(guestIdentifier: String): UserEntity?

    @Query("SELECT * FROM users WHERE authType = :authType LIMIT 1")
    suspend fun findFirstByAuthType(authType: String): UserEntity?

    @Query("SELECT COUNT(*) FROM users WHERE authType <> :anonymous LIMIT 1")
    suspend fun countRealAccounts(anonymous: String = StorageValues.AuthType.ANONYMOUS.storageValue): Int
}
