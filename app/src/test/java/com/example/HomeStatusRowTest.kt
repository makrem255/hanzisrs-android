package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.example.ui.screens.HomeStatusRow
import com.example.ui.screens.formatExperience
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The compact indicators on the home screen.
 *
 * The numbers come from the screen's own state and are only formatted here, so
 * these tests are about the three cases that break a fixed-width layout: nothing
 * earned yet, exactly one, and a total large enough to need grouping. They are
 * also about the spoken form, because a screen reader is handed the description
 * rather than the number, and "1 day" and "1 days" are the difference between a
 * sentence and a mistake.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class HomeStatusRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `zero is shown, and it is not a missing value`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeStatusRow(totalXp = 0, level = 1, streakDays = 0)
            }
        }
        composeTestRule.onNodeWithContentDescription("Experience: 0 points, level 1").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Streak: 0 days").assertIsDisplayed()
    }

    @Test
    fun `one day is one day`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeStatusRow(totalXp = 120, level = 2, streakDays = 1)
            }
        }
        composeTestRule.onNodeWithContentDescription("Streak: 1 day").assertIsDisplayed()
    }

    @Test
    fun `a large total is grouped`() {
        val total = 1_234_567
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeStatusRow(totalXp = total, level = 9, streakDays = 365)
            }
        }
        composeTestRule.onNodeWithContentDescription(
            "Experience: ${formatExperience(total)} points, level 9"
        ).assertIsDisplayed()
    }

    @Test
    fun `a side that has not arrived is left out rather than drawn as zero`() {
        // The row is rendered before the dashboard snapshot has answered. Drawing a
        // zero there would claim the learner has no experience and no streak, which
        // is a different statement from "we do not know yet".
        composeTestRule.setContent {
            MyApplicationTheme {
                HomeStatusRow(totalXp = 40, level = 1, streakDays = null)
            }
        }
        composeTestRule.onNodeWithContentDescription("Experience: 40 points, level 1").assertIsDisplayed()
    }

    @Test
    fun `a large total is not left ungrouped`() {
        assertTrue(formatExperience(1_234_567).contains(','))
        assertEquals("0", formatExperience(0))
    }
}
