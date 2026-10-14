package com.example

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.example.data.model.StorageValues
import com.example.ui.components.InteractiveStrokeSection
import com.example.ui.components.denormalizeTracePoints
import com.example.ui.components.normalizeTracePoint
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Handwriting practice: normalized storage plus the hide/hint/reveal controls.
 *
 * Strokes are stored as canvas fractions, so the pure geometry below pins the
 * contract without any UI: normalize-then-denormalize round-trips, and resizing
 * the canvas scales old strokes with it instead of stranding them. The
 * interaction tests drive the real section: a finger drag becomes an undoable
 * stroke, Clear empties the canvas, and Hide/Show/Hint only change whether the
 * reference glyph is drawn - never the strokes, the counter, or the scoring
 * disclaimer (there is no scoring, and nothing here may imply otherwise).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class TracePracticeTest {

    @get:Rule val composeTestRule = createComposeRule()

    // ---- pure geometry: fractions survive any canvas size ----------------------------------------

    @Test
    fun `normalize then denormalize round-trips`() {
        val p = Offset(150f, 75f)

        val stored = normalizeTracePoint(p, 300f, 150f)
        assertEquals(Offset(0.5f, 0.5f), stored)

        val back = denormalizeTracePoints(listOf(stored), 300f, 150f)
        assertEquals(listOf(p), back)
    }

    @Test
    fun `resizing scales old strokes instead of stranding them`() {
        val stored = normalizeTracePoint(Offset(100f, 50f), 400f, 200f)

        // Same stroke on a canvas twice as wide and three times as tall.
        val grown = denormalizeTracePoints(listOf(stored), 800f, 600f)
        assertEquals(listOf(Offset(200f, 150f)), grown)
    }

    @Test
    fun `unknown canvas size draws nothing instead of collapsing`() {
        assertEquals(Offset.Zero, normalizeTracePoint(Offset(10f, 10f), 0f, 100f))
        assertEquals(Offset.Zero, normalizeTracePoint(Offset(10f, 10f), 100f, 0f))
        assertTrue(denormalizeTracePoints(listOf(Offset(0.5f, 0.5f)), 0f, 100f).isEmpty())
        assertTrue(denormalizeTracePoints(emptyList(), 300f, 300f).isEmpty())
    }

    @Test
    fun `touch points outside the grid are clamped into it`() {
        assertEquals(
            Offset(1f, 0f),
            normalizeTracePoint(Offset(999f, -5f), 300f, 150f),
        )
    }

    // ---- interactions: draw, clear, hide, hint, reveal ----------------------------------------------

    private fun showPractice() {
        composeTestRule.setContent {
            MyApplicationTheme {
                InteractiveStrokeSection(hanzi = "人", strokeBreakdown = "")
            }
        }
        openPracticeTab()
    }

    private fun openPracticeTab() {
        composeTestRule.onNodeWithText("Practise tracing").performClick()
    }

    private fun drawOneStroke() {
        composeTestRule.onNodeWithTag("trace_grid").performTouchInput {
            swipe(
                start = center - Offset(60f, 0f),
                end = center + Offset(60f, 0f),
                durationMillis = 200,
            )
        }
    }

    @Test
    fun `a finger drag becomes an undoable stroke`() {
        showPractice()
        composeTestRule.onNodeWithText("Hide").assertIsDisplayed()

        drawOneStroke()

        // Undo exists only while at least one stroke does: its appearance is the
        // observable proof the drag was recorded.
        composeTestRule.onNodeWithTag("trace_undo").assertIsDisplayed()
    }

    @Test
    fun `clear empties the canvas`() {
        showPractice()
        drawOneStroke()
        composeTestRule.onNodeWithTag("trace_undo").assertIsDisplayed()

        composeTestRule.onNodeWithTag("trace_clear").performClick()

        composeTestRule.onAllNodesWithTag("trace_undo", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `hide and show only toggle the reference`() {
        showPractice()
        drawOneStroke()

        composeTestRule.onNodeWithText("Hide").performClick()
        composeTestRule.onNodeWithText("Show").assertIsDisplayed()
        // The drawn stroke survives hiding: toggling changes what is drawn
        // underneath, never the learner's work.
        composeTestRule.onNodeWithTag("trace_undo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hint").assertIsDisplayed()

        composeTestRule.onNodeWithText("Show").performClick()
        composeTestRule.onNodeWithText("Hide").assertIsDisplayed()
    }

    @Test
    fun `hint is offered while hidden and leaves the state hidden`() {
        showPractice()
        composeTestRule.onNodeWithText("Hide").performClick()

        composeTestRule.onNodeWithText("Hint").performClick()

        // The flash is momentary: afterwards the reference is hidden again and
        // the learner is back to writing from memory.
        composeTestRule.mainClock.advanceTimeBy(2_000L)
        composeTestRule.onNodeWithText("Show").assertIsDisplayed()
    }

    // ---- themes -----------------------------------------------------------------------------------------

    private fun showPracticeIn(mode: StorageValues.ThemeMode) {
        composeTestRule.setContent {
            MyApplicationTheme(themeMode = mode) {
                InteractiveStrokeSection(hanzi = "人", strokeBreakdown = "")
            }
        }
        openPracticeTab()
    }

    @Test
    fun `practice renders in light theme`() {
        showPracticeIn(StorageValues.ThemeMode.LIGHT)

        composeTestRule.onNodeWithTag("trace_grid").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
    }

    @Test
    fun `practice renders in dark theme`() {
        showPracticeIn(StorageValues.ThemeMode.DARK)

        composeTestRule.onNodeWithTag("trace_grid").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
    }
}

