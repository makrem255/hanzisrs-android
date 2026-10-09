package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.example.ui.components.GoalBar
import com.example.ui.components.PrimaryButton
import com.example.ui.components.ProgressRing
import com.example.ui.components.SecondaryButton
import com.example.ui.components.StatTile
import com.example.ui.screens.AnswerLine
import com.example.ui.screens.MetaChip
import com.example.ui.screens.RecognisedBadge
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The design-system components Robo taps through, verified on the JVM.
 *
 * Screens need a view model and a database, so they cannot run here; these atoms can. Every
 * assertion is about something Robo depends on out in the field: the label is really composed
 * (not drawn on canvas where exploration cannot read it), the tap really fires, and the control
 * is really displayed. A component that only *looks* like a button would pass a screenshot and
 * fail a crawl.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class ComponentRoboTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun primary_button_shows_its_label_and_fires_exactly_once() {
        var clicks = 0
        composeTestRule.setContent {
            MyApplicationTheme { PrimaryButton(text = "Start Review", onClick = { clicks++ }) }
        }

        composeTestRule.onNodeWithText("Start Review")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun secondary_button_shows_its_label_and_fires() {
        var clicks = 0
        composeTestRule.setContent {
            MyApplicationTheme { SecondaryButton(text = "Reveal the answer", onClick = { clicks++ }) }
        }

        composeTestRule.onNodeWithText("Reveal the answer")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun stat_tile_reads_value_then_label() {
        composeTestRule.setContent {
            MyApplicationTheme {
                StatTile(label = "Current streak", value = "7", accent = AccentPrimary)
            }
        }

        composeTestRule.onNodeWithText("7").assertIsDisplayed()
        composeTestRule.onNodeWithText("Current streak").assertIsDisplayed()
    }

    @Test
    fun progress_bar_composes_at_empty() {
        composeTestRule.setContent {
            MyApplicationTheme {
                GoalBar(progress = 0f)
            }
        }
        composeTestRule.onRoot().assertIsDisplayed()
    }

    @Test
    fun progress_ring_composes_at_full_with_content() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ProgressRing(progress = 1f) {
                    androidx.compose.material3.Text("done")
                }
            }
        }
        composeTestRule.onNodeWithText("done").assertIsDisplayed()
    }

    @Test
    fun recognised_badge_announces_itself() {
        composeTestRule.setContent {
            MyApplicationTheme { RecognisedBadge() }
        }

        composeTestRule.onNodeWithContentDescription("Recognised").assertIsDisplayed()
    }

    @Test
    fun answer_line_and_meta_chip_render_their_text() {
        composeTestRule.setContent {
            MyApplicationTheme {
                AnswerLine(label = "Meaning", value = "to study")
                MetaChip(text = "Word 3 · practice only")
            }
        }

        composeTestRule.onNodeWithText("Meaning").assertIsDisplayed()
        composeTestRule.onNodeWithText("to study").assertIsDisplayed()
        composeTestRule.onNodeWithText("Word 3 · practice only").assertIsDisplayed()
    }
}
