package com.example.data.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The session token: how one is minted, and how it is stored.
 *
 * ## Why the database holds a hash and not the token
 *
 * The raw token lives in exactly one place — [SessionStore], in app-private preferences — and
 * `users.token` holds its SHA-256 digest. That is the same arrangement OAuth refresh tokens
 * use, and it is here for one concrete reason: a stolen database file on its own must not be
 * enough to impersonate a learner.
 *
 * The previous arrangement wrote the token itself into `users.token` and kept no copy
 * elsewhere, so the database was a complete session credential. Extracting one file from a
 * backup, or pulling it off a device with a file-system reader, would have yielded a working
 * session. With the digest, the database proves *that* a token is valid but cannot supply one.
 * An attacker needs both halves.
 *
 * SHA-256 rather than a slow KDF is deliberate and is not a weakening. A session token is
 * 256 bits of `SecureRandom` output, so there is no dictionary to search and no guessable
 * structure to attack; stretching buys nothing. The slow KDF exists for *passwords*, which are
 * low-entropy and human-chosen, and [com.example.util.PasswordHasher] covers that case.
 *
 * ## What this does and does not protect against
 *
 * This is a local, device-bound credential. It is not a substitute for server-side
 * authentication and cannot be: there is no server. It makes the *client* correct — a session
 * that is random, single-use-rotating, expiring, revocable, and not readable from the database
 * alone. It does not defend against an attacker who already controls the device, because
 * app-private storage is only a boundary by convention and process isolation.
 */
object SessionToken {

    /** 256 bits. Hex would be simpler to read in a log; base64url is shorter for the same entropy. */
    private const val TOKEN_BYTES = 32

    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    private val random = SecureRandom()

    /**
     * Mints a fresh, unguessable session token.
     *
     * Called on every successful sign-in. A new token per sign-in means a token captured
     * earlier stops working the moment the learner signs in again, which is the rotation that
     * makes a single-token-per-user model safe to keep rather than a reason to grow a session
     * table.
     */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.encodeToString(bytes, BASE64_FLAGS)
    }

    /**
     * The digest stored in `users.token`.
     *
     * Hex rather than base64 because this value ends up in SQL and in `WHERE` clauses, where a
     * base64url alphabet invites quoting mistakes, and because a 64-character hex string makes
     * accidental truncation obvious in a query log.
     */
    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            out.append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
