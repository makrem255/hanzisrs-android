package com.example

import com.example.util.PasswordHasher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the password hash format and the legacy SHA-256 migration path used by
 * [com.example.data.repository.UserRepository] when upgrading an old profile.
 *
 * [PasswordHasher] encodes the salt and digest with `android.util.Base64`, which is an
 * Android framework class. Plain JUnit therefore throws "not mocked" for it, so this
 * suite runs on the Robolectric runner to get a real implementation of that class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PasswordHasherTest {

    @Test
    fun `a created hash verifies against its own password`() {
        val hash = PasswordHasher.createHash("correct horse battery")

        assertTrue(PasswordHasher.verify("correct horse battery", hash))
    }

    @Test
    fun `a created hash rejects the wrong password`() {
        val hash = PasswordHasher.createHash("correct horse battery")

        assertFalse(PasswordHasher.verify("Correct horse battery", hash))
        assertFalse(PasswordHasher.verify("", hash))
    }

    @Test
    fun `each hash uses a fresh salt so identical passwords differ on disk`() {
        val first = PasswordHasher.createHash("same-password")
        val second = PasswordHasher.createHash("same-password")

        assertFalse("identical passwords must not produce identical hashes", first == second)
        assertTrue(PasswordHasher.verify("same-password", first))
        assertTrue(PasswordHasher.verify("same-password", second))
    }

    @Test
    fun `verify rejects a malformed or foreign hash instead of throwing`() {
        assertFalse(PasswordHasher.verify("anything", "not-a-valid-hash"))
        assertFalse(PasswordHasher.verify("anything", ""))
        assertFalse(PasswordHasher.verify("anything", "pbkdf2-sha256-v1\$notanumber\$abc\$def"))
    }

    @Test
    fun `legacy sha256 digests are recognised by the migration guard`() {
        val legacy = PasswordHasher.legacySha256("learnhanzi")

        assertTrue(PasswordHasher.isLegacySha256(legacy))
        assertFalse(PasswordHasher.isLegacySha256(PasswordHasher.createHash("learnhanzi")))
    }

    @Test
    fun `legacy sha256 comparison accepts the right password only`() {
        val legacy = PasswordHasher.legacySha256("learnhanzi")

        assertTrue(PasswordHasher.legacySha256Matches("learnhanzi", legacy))
        assertFalse(PasswordHasher.legacySha256Matches("learnhanzu", legacy))
        assertFalse(PasswordHasher.legacySha256Matches("", legacy))
    }

    @Test
    fun `legacy sha256 comparison tolerates a malformed digest`() {
        // Must return false rather than throw on odd-length or non-hex input.
        assertFalse(PasswordHasher.legacySha256Matches("learnhanzi", "abc"))
        assertFalse(PasswordHasher.legacySha256Matches("learnhanzi", "zz" + "0".repeat(62)))
        assertFalse(PasswordHasher.legacySha256Matches("learnhanzi", ""))
    }
}
