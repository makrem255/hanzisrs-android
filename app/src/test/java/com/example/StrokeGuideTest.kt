package com.example

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ui.components.InteractiveStrokeSection
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stroke-order guide, driven like a learner would drive it.
 *
 * The guide's data is stroke *names* in order - the app owns no per-stroke geometry,
 * and this suite asserts nothing about shapes. What it pins is the behaviour around
 * that data: a supported character demonstrates (counter, name, advancing playback,
 * pause, stepping, reset), and an unsupported one says so instead of animating
 * nothing - or worse, something invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class StrokeGuideTest {

    @get:Rule val composeTestRule = createComposeRule()

    /** The real catalogue entry for 爱: ten named strokes. */
    private val aiStrokes =
        "撇 (Piě), 点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), " +
            "横钩 (Héng Gōu), 横 (Héng), 撇 (Piě), 横撇 (Héng Piě), 捺 (Nà)"

    private fun showGuide(hanzi: String = "爱", breakdown: String = aiStrokes) {
        composeTestRule.setContent {
            MyApplicationTheme {
                InteractiveStrokeSection(hanzi = hanzi, strokeBreakdown = breakdown)
            }
        }
    }

    @Test
    fun supported_character_opens_on_stroke_one_of_ten() {
        showGuide()

        composeTestRule.onNodeWithTag("stroke_step_counter").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stroke 1 of 10").assertIsDisplayed()
        composeTestRule.onNodeWithText("撇").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stroke_progress").assertIsDisplayed()
    }

    @Test
    fun playback_advances_and_pause_holds() {
        // The default auto-advancing clock: pending step delays do not block test
        // idleness, and each `advanceTimeBy` fires exactly the steps it covers, so
        // the assertions below are deterministic. (A manual clock was tried: frames
        // stop pumping with it in this setup and freshly recomposed text never
        // becomes displayed, which made every assertion after a state change fail.)
        showGuide()

        composeTestRule.onNodeWithText("Play").performClick()
        // The tap engaged playback iff the label flipped: exactly one Stop, no Play.
        composeTestRule.onAllNodesWithText("Stop").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Play").assertCountEquals(0)
        // Frames do not pump themselves with a manual clock; move one so layout runs.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("Stroke 1 of 10").assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(1_500L)
        composeTestRule.onNodeWithText("Stroke 2 of 10").assertIsDisplayed()

        // Pause: advancing time further moves nothing.
        composeTestRule.onNodeWithTag("stroke_play_pause").performClick()
        composeTestRule.mainClock.advanceTimeBy(5_000L)
        composeTestRule.onNodeWithText("Stroke 2 of 10").assertIsDisplayed()
    }

    @Test
    fun next_prev_and_reset_move_reliably() {
        showGuide()

        composeTestRule.onNodeWithTag("stroke_next").performClick()
        composeTestRule.onNodeWithTag("stroke_next").performClick()
        composeTestRule.onNodeWithText("Stroke 3 of 10").assertIsDisplayed()

        composeTestRule.onNodeWithTag("stroke_prev").performClick()
        composeTestRule.onNodeWithText("Stroke 2 of 10").assertIsDisplayed()

        composeTestRule.onNodeWithTag("stroke_reset").performClick()
        composeTestRule.onNodeWithText("Stroke 1 of 10").assertIsDisplayed()
    }

    @Test
    fun unsupported_character_says_so_and_offers_no_player() {
        showGuide(hanzi = "龘", breakdown = "")

        composeTestRule.onNodeWithTag("stroke_no_data").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "No stroke order for this character",
            substring = true
        ).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("stroke_play_pause", useUnmergedTree = true)
            .assertCountEquals(0)
    }
}
