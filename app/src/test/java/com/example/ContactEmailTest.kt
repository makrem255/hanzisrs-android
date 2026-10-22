package com.example

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.util.ContactEmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the app asks a device to open an email.
 *
 * The address is the app's one published contact point, so the two things that
 * carry it - the constant the intent is built from and the string the screen
 * shows - are asserted to be the same. They are two copies of one fact, and two
 * copies drift.
 *
 * The intent is a request, not an action: it names a mail app and a recipient and
 * sends nothing. The test for that is that the intent carries no subject and no
 * body, so whatever the learner writes is what gets sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactEmailTest {

    @Test
    fun `the address is the one the app publishes`() {
        assertEquals("makrembensalem2008@gmail.com", ContactEmail.ADDRESS)
    }

    @Test
    fun `the address on the screen is the address the app writes to`() {
        // The same fact in two places: the constant the intent is built from, and
        // the string resource the settings screen shows. If they drift, the
        // learner is shown one address and their mail is addressed to another.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(
            ContactEmail.ADDRESS,
            context.getString(R.string.contact_email)
        )
    }

    @Test
    fun `the intent asks for a mail app and names the recipient`() {
        val intent = ContactEmail.intentFor()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto:makrembensalem2008@gmail.com", intent.data?.toString())
    }

    @Test
    fun `the intent carries no subject and no body`() {
        // Nothing is written for the learner. A pre-filled subject would put words
        // in their mouth, and a pre-filled body would be an email they never wrote.
        val intent = ContactEmail.intentFor()
        assertNull(intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `the intent names no other recipient`() {
        // A shared intent can carry several; this one carries one address, the
        // app's own, so a learner cannot send their message to anyone else by
        // accident.
        val intent = ContactEmail.intentFor()
        assertTrue(
            "the intent must not carry an extra recipient",
            intent.getStringArrayExtra(Intent.EXTRA_EMAIL).isNullOrEmpty()
        )
    }

    @Test
    fun `a stray space in the address does not break the uri`() {
        assertEquals("mailto:learner@example.com", ContactEmail.mailtoFor("  learner@example.com  "))
    }
}
