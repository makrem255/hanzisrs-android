package com.example

import com.example.data.review.contrastRatio
import com.example.ui.theme.AppColors
import com.example.ui.theme.DarkAppColors
import com.example.ui.theme.LightAppColors
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The monochrome system guarantees its own legibility.
 *
 * Every pair below is text (or a text-sized signal) on the surface it is drawn
 * on, in both themes. 4.5:1 is the WCAG AA floor for normal text; a palette
 * value that drifts below it does not ship, because a theme switch must never
 * turn readable screens into grey-on-grey ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MonoContrastTest {

    private data class PairCheck(
        val name: String,
        val foreground: AppColors.() -> androidx.compose.ui.graphics.Color,
        val background: AppColors.() -> androidx.compose.ui.graphics.Color,
    )

    private val pairs = listOf(
        PairCheck("text on background", { textPrimary }, { background }),
        PairCheck("text on card", { textPrimary }, { card }),
        PairCheck("secondary on background", { textSecondary }, { background }),
        PairCheck("secondary on card", { textSecondary }, { card }),
        PairCheck("secondary on raised", { textSecondary }, { elevated }),
        PairCheck("button label on button", { onButton }, { button }),
        PairCheck("success on card", { success }, { card }),
        PairCheck("error on card", { error }, { card }),
        PairCheck("hard state on card", { hard }, { card }),
        PairCheck("easy state on card", { easy }, { card }),
    )

    @Test
    fun `dark theme text pairs meet AA`() {
        assertPairs(DarkAppColors, "dark")
    }

    @Test
    fun `light theme text pairs meet AA`() {
        assertPairs(LightAppColors, "light")
    }

    private fun assertPairs(colors: AppColors, theme: String) {
        for (pair in pairs) {
            val ratio = contrastRatio(
                argbOf(pair.foreground(colors)),
                argbOf(pair.background(colors)),
            )
            assertTrue(
                "$theme ${pair.name} is %.2f:1, below the 4.5:1 floor".format(ratio),
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `light and dark palettes are actually different themes`() {
        val trueBlack = androidx.compose.ui.graphics.Color(0xFF000000)
        val white = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
        val ink = androidx.compose.ui.graphics.Color(0xFF111111)

        assertTrue(DarkAppColors != LightAppColors)
        // The dark ground is genuine OLED black, not charcoal.
        assertEquals(trueBlack, DarkAppColors.background)
        assertEquals(white, LightAppColors.background)
        assertEquals(white, DarkAppColors.textPrimary)
        assertEquals(ink, LightAppColors.textPrimary)
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
