package com.example

import com.example.data.model.StorageValues
import com.example.data.review.contrastRatio
import com.example.ui.theme.AppColors
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import com.example.ui.theme.srsStateColorIn
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a card's scheduling state looks like.
 *
 * Four states, four colours, and no borrowing: the same rule has to hold in both
 * themes, because the distribution bar on the dashboard and the badge on the
 * library row both read these colours, and a learner who learns "blue is new"
 * has to be able to rely on it in either one.
 *
 * The first case here is a regression test for a real defect. `NEW` was drawn in
 * the error colour, so a word the learner had never studied - the most innocent
 * state there is - carried the colour the app uses to mean "you failed this one".
 * Nothing had failed; the word simply had not been met yet.
 */
class SchedulingStateColorTest {

    private val states = StorageValues.CardState.entries

    private val palettes = listOf(LightAppColors, DarkAppColors)

    @Test
    fun `the four states are four different colours`() {
        for (colors in palettes) {
            val mapped = states.map { srsStateColorIn(colors, it) }
            assertEquals(
                "every scheduling state needs a colour of its own",
                states.size,
                mapped.distinct().size
            )
        }
    }

    @Test
    fun `a word nobody has studied yet is not drawn in the failure colour`() {
        assertNotEquals(
            LightAppColors.error,
            srsStateColorIn(LightAppColors, StorageValues.CardState.NEW)
        )
        assertNotEquals(
            DarkAppColors.error,
            srsStateColorIn(DarkAppColors, StorageValues.CardState.NEW)
        )
    }

    @Test
    fun `mastery is the success colour`() {
        assertEquals(
            LightAppColors.success,
            srsStateColorIn(LightAppColors, StorageValues.CardState.MASTERED)
        )
        assertEquals(
            DarkAppColors.success,
            srsStateColorIn(DarkAppColors, StorageValues.CardState.MASTERED)
        )
    }

    @Test
    fun `no state borrows the colour of another meaning`() {
        // The four states take the four semantic hues, one each. If two of them
        // ever land on the same colour, the distribution bar stops being readable
        // and the app starts using one hue for two different statements.
        for (colors in palettes) {
            val meanings = listOf(
                colors.primaryAction,
                colors.vocabulary,
                colors.learningFocus,
                colors.success
            )
            val mapped = states.map { srsStateColorIn(colors, it) }
            assertEquals(
                "each state must take exactly one of the four semantic hues",
                meanings.toSet(),
                mapped.toSet()
            )
        }
    }

    @Test
    fun `every state colour is readable on the card`() {
        // The dashboard's distribution legend prints each state's count in that
        // state's colour, on the card, at body size.
        for (colors in palettes) {
            for (state in states) {
                val ratio = contrastRatio(
                    argbOf(srsStateColorIn(colors, state)),
                    argbOf(colors.card)
                )
                assertTrue(
                    "$state is %.2f:1 on the card, below the 4.5:1 floor".format(ratio),
                    ratio >= 4.5
                )
            }
        }
    }

    /**
     * Packs a Compose colour by hand rather than calling `toArgb`, which needs the
     * Android graphics class the plain JVM test source set does not carry.
     */
    private fun argbOf(color: androidx.compose.ui.graphics.Color): Int {
        val a = (color.alpha * 255).roundToInt()
        val r = (color.red * 255).roundToInt()
        val g = (color.green * 255).roundToInt()
        val b = (color.blue * 255).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
