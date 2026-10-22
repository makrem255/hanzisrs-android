package com.example

import com.example.shouldShowWelcome
import com.example.ui.screens.AuthMode
import com.example.ui.screens.authModeFromRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who sees the welcome screen, and which form the sign-in screen opens on.
 *
 * Two rules, and both of them are about not showing a learner the wrong thing:
 * the welcome screen belongs to a learner with no session and to nobody else,
 * and the form behind it opens on the tab the learner asked for.
 */
class WelcomeRoutingTest {

    @Test
    fun `a learner with no session sees the welcome screen`() {
        assertTrue(shouldShowWelcome(sessionRestored = true, signedIn = false))
    }

    @Test
    fun `a signed-in learner never sees it`() {
        assertFalse(shouldShowWelcome(sessionRestored = true, signedIn = true))
    }

    @Test
    fun `a learner whose session is not read yet is not sent to it`() {
        // The session check is a local read that takes a few milliseconds. Sending
        // the learner to the front door before the app knows whether they have an
        // account would flash the wrong screen at every launch.
        assertFalse(shouldShowWelcome(sessionRestored = false, signedIn = false))
        assertFalse(shouldShowWelcome(sessionRestored = false, signedIn = true))
    }

    @Test
    fun `the register route opens the sign-up form`() {
        assertEquals(AuthMode.REGISTER, authModeFromRoute("register"))
    }

    @Test
    fun `the login route opens the sign-in form`() {
        assertEquals(AuthMode.LOGIN, authModeFromRoute("login"))
    }

    @Test
    fun `an unrecognised route opens the sign-in form`() {
        // The safe default: a form that asks for a new account cannot sign an
        // existing one in, so a typo must never land on it.
        assertEquals(AuthMode.LOGIN, authModeFromRoute(null))
        assertEquals(AuthMode.LOGIN, authModeFromRoute(""))
        assertEquals(AuthMode.LOGIN, authModeFromRoute("sign-up"))
    }

    @Test
    fun `the route argument is read whatever its case`() {
        assertEquals(AuthMode.REGISTER, authModeFromRoute("Register"))
    }
}
