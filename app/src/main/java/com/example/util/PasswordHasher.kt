package com.example.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password storage for device-local learner profiles. This is deliberately not
 * represented as authentication for a remote service: there is no backend in
 * this project. The encoded value contains the per-password salt and work factor.
 */
object PasswordHasher {
    private const val VERSION = "pbkdf2-sha256-v1"
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    fun createHash(password: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)
        val derivedKey = deriveKey(password, salt, ITERATIONS)
        return listOf(
            VERSION,
            ITERATIONS.toString(),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(derivedKey, Base64.NO_WRAP)
        ).joinToString("$")
    }

    fun verify(password: String, encodedHash: String): Boolean {
        val parts = encodedHash.split("$")
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        return try {
            val salt = Base64.decode(parts[2], Base64.NO_WRAP)
            val expected = Base64.decode(parts[3], Base64.NO_WRAP)
            MessageDigest.isEqual(deriveKey(password, salt, iterations), expected)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun isLegacySha256(encodedHash: String): Boolean =
        encodedHash.matches(Regex("[0-9a-f]{64}"))

    fun legacySha256(password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(keySpec)
                .encoded
        } finally {
            keySpec.clearPassword()
        }
    }
}
