package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.screens.WelcomeScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The welcome screen: what it shows, and what its two buttons do.
 *
 * The screen's whole job is to be a front door that opens the right existing
 * form. These tests are about that job - the name and the promise are on screen,
 * and each button opens the form the learner asked for - rather than about how
 * the illustration is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class WelcomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the name and the promise are on the screen`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                WelcomeScreen(onGetStarted = {}, onLogIn = {})
            }
        }
        composeTestRule.onNodeWithText("HanziSRS").assertIsDisplayed()
        composeTestRule.onNodeWithText("Master Chinese, one step at a time.").assertIsDisplayed()
    }

    @Test
    fun `both ways in are on the screen`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                WelcomeScreen(onGetStarted = {}, onLogIn = {})
            }
        }
        composeTestRule.onNodeWithText("Get Started").assertIsDisplayed()
        composeTestRule.onNodeWithText("Log In").assertIsDisplayed()
    }

    @Test
    fun `get started opens the sign-up flow`() {
        var started = false
        var loggedIn = false
        composeTestRule.setContent {
            MyApplicationTheme {
                WelcomeScreen(
                    onGetStarted = { started = true },
                    onLogIn = { loggedIn = true }
                )
            }
        }
        composeTestRule.onNodeWithText("Get Started").performClick()
        assertTrue("Get Started must open the sign-up flow", started)
        assertTrue("and must not open the sign-in flow", !loggedIn)
    }

    @Test
    fun `log in opens the sign-in flow`() {
        var started = false
        var loggedIn = false
        composeTestRule.setContent {
            MyApplicationTheme {
                WelcomeScreen(
                    onGetStarted = { started = true },
                    onLogIn = { loggedIn = true }
                )
            }
        }
        composeTestRule.onNodeWithText("Log In").performClick()
        assertTrue("Log In must open the sign-in flow", loggedIn)
        assertTrue("and must not open the sign-up flow", !started)
    }
}
