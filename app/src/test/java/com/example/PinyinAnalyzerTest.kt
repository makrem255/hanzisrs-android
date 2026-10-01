package com.example

import com.example.data.srs.PinyinAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decomposition behind the `pinyin_syllables` table.
 *
 * `syllable` + `toneNumber` is that table's unique key, so a mis-parsed reading does not just
 * render badly, it creates a second row for a reading that already exists and splits a
 * learner's vocabulary entry in two. These cases pin the tone-mark placement and the
 * initial/final split that the identity depends on.
 */
class PinyinAnalyzerTest {

    @Test
    fun `tone mark on a is read back correctly`() {
        val analysis = PinyinAnalyzer.analyze("xué")

        assertEquals("xue", analysis.syllable)
        assertEquals(2, analysis.toneNumber)
        assertEquals("xué", analysis.toneMarked)
        assertEquals("35", analysis.toneContour)
        assertEquals("x", analysis.initial)
        assertEquals("ue", analysis.final)
    }

    @Test
    fun `the same syllable at different tones is a different reading`() {
        val ma1 = PinyinAnalyzer.analyze("mā")
        val ma2 = PinyinAnalyzer.analyze("má")
        val ma3 = PinyinAnalyzer.analyze("mǎ")
        val ma4 = PinyinAnalyzer.analyze("mà")

        assertEquals(listOf(1, 2, 3, 4), listOf(ma1.toneNumber, ma2.toneNumber, ma3.toneNumber, ma4.toneNumber))
        // The toneless form is identical, which is exactly why it is only half the key.
        assertEquals(setOf("ma"), setOf(ma1.syllable, ma2.syllable, ma3.syllable, ma4.syllable))
    }

    @Test
    fun `a neutral tone keeps the number zero and the mid-level contour`() {
        val analysis = PinyinAnalyzer.analyze("de")

        assertEquals(0, analysis.toneNumber)
        assertEquals("de", analysis.toneMarked)
        assertEquals("33", analysis.toneContour)
    }

    @Test
    fun `multi character initials beat their single letter prefixes`() {
        val zhi = PinyinAnalyzer.analyze("zhī")
        val chi = PinyinAnalyzer.analyze("chī")
        val shi = PinyinAnalyzer.analyze("shī")

        assertEquals("zh", zhi.initial)
        assertEquals("ch", chi.initial)
        assertEquals("sh", shi.initial)
        assertEquals("i", zhi.final)
    }

    @Test
    fun `y and w are spelling conventions rather than initials`() {
        val yao = PinyinAnalyzer.analyze("yáo")
        val wo = PinyinAnalyzer.analyze("wǒ")

        assertEquals("", yao.initial)
        assertEquals("iao", yao.final)
        assertEquals("", wo.initial)
        assertEquals("uo", wo.final)
    }

    @Test
    fun `the y and w spellings share a rime with the consonant spelling`() {
        // The whole reason the letter is restored rather than left in place: `you` and `liu`
        // are the same rime, so a query by rime has to find both.
        assertEquals(
            PinyinAnalyzer.analyze("liú").final,
            PinyinAnalyzer.analyze("yóu").final
        )
        assertEquals(
            PinyinAnalyzer.analyze("guī").final,
            PinyinAnalyzer.analyze("wēi").final
        )
        // `yu` is the one case where the letter hides a umlaut rather than a missing i.
        assertEquals("üan", PinyinAnalyzer.analyze("yuán").final)
    }

    @Test
    fun `a syllable with no consonant initial keeps an empty initial`() {
        val ai = PinyinAnalyzer.analyze("ài")

        assertEquals("ai", ai.syllable)
        assertEquals("", ai.initial)
        assertEquals("ai", ai.final)
        assertEquals("51", ai.toneContour)
    }

    @Test
    fun `the ascii v spelling of u-umlaut is expanded`() {
        // Older content and some pinyin input methods write lü as "lv".
        val analysis = PinyinAnalyzer.analyze("lü")

        assertEquals("lü", analysis.syllable)
        assertEquals("l", analysis.initial)
        assertEquals("ü", analysis.final)
    }

    @Test
    fun `input is normalised rather than rejected`() {
        val analysis = PinyinAnalyzer.analyze("  XUÉ  ")

        assertEquals("xue", analysis.syllable)
        assertEquals(2, analysis.toneNumber)
    }

    @Test
    fun `unparseable input degrades to a neutral tone instead of throwing`() {
        val analysis = PinyinAnalyzer.analyze("!!!???")

        assertEquals(0, analysis.toneNumber)
        // Degrading to neutral means the neutral contour, not the absence of one: nothing is
        // discarded, the reading is just no longer claimed to be a marked tone.
        assertEquals("33", analysis.toneContour)
        assertEquals("!!!???", analysis.syllable)
    }

    @Test
    fun `empty input produces an empty analysis rather than an exception`() {
        val analysis = PinyinAnalyzer.analyze("   ")

        assertEquals("", analysis.syllable)
        assertEquals(0, analysis.toneNumber)
        assertTrue(analysis.initial.isEmpty())
        // Added because this test passed while the field beside it was wrong. `analyze("")` was
        // returning `toneContour = ""` for exactly as long as this method existed, and the
        // assertion that would have caught it is the one this test did not make.
        assertEquals("33", analysis.toneContour)
    }

    /**
     * `Analysis.toneContour` documents itself as always populated: an empty string means "no
     * contour", and every site that draws a tone contour would then have to special-case it.
     *
     * That invariant was only half-enforced — the empty-input branch violated it, and the main
     * path read the same map through a `.orEmpty()` that could reintroduce it. Both are now
     * routed through one total accessor, so this asserts the property over inputs chosen to hit
     * every branch rather than trusting a comment to say which branches exist.
     */
    @Test
    fun `no input produces a missing tone contour`() {
        val inputs = listOf(
            "", "   ", "ni", "nǐ", "ní", "nì", "nī", "lüè", "lǜe", "v", "V",
            "xuéá", "ng", "hm", "5", "!", "ni hao3", "zzzzzzzz", "ā", "é", "ǚ"
        )

        inputs.forEach { input ->
            val analysis = PinyinAnalyzer.analyze(input)
            assertTrue(
                "toneContour was empty for input \"$input\", which the Analysis contract says " +
                    "cannot happen; a caller drawing the contour gets a blank stroke diagram",
                analysis.toneContour.isNotEmpty()
            )
            assertTrue(
                "toneContour for input \"$input\" must be one of the five declared contours, " +
                    "not \"${analysis.toneContour}\"",
                analysis.toneContour in setOf("55", "35", "214", "51", "33")
            )
        }
    }

    /**
     * The contour must agree with the tone number, on every path.
     *
     * A contour that is merely *present* but wrong — the neutral `"33"` leaking into a
     * third-tone reading, say — is the more damaging failure of the two, because it draws
     * convincingly. So the pairing is asserted, not just the non-emptiness.
     */
    @Test
    fun `the contour always matches the tone number it belongs to`() {
        val expected = mapOf(0 to "33", 1 to "55", 2 to "35", 3 to "214", 4 to "51")

        // Tone 0 is unreachable for the marked forms, so it is covered by the plain forms.
        listOf("ni", "nǐ", "ní", "nǐ", "nì", "", "zhong", "lǜ", "mā", "ma").forEach { input ->
            val analysis = PinyinAnalyzer.analyze(input)
            assertEquals(
                "contour for \"$input\" (tone ${analysis.toneNumber}) did not match its tone",
                expected[analysis.toneNumber],
                analysis.toneContour
            )
        }
    }

    @Test
    fun `a second tone mark does not override the first`() {
        // "xuéá" is not a valid reading; the first mark wins and the rest is treated as letters.
        val analysis = PinyinAnalyzer.analyze("xuéá")

        assertEquals(2, analysis.toneNumber)
    }

    @Test
    fun `the tone mark lands on the correct vowel for the iu and ui rimes`() {
        // Standard placement: the mark goes on the second vowel in both cases.
        assertEquals("liú", PinyinAnalyzer.analyze("liú").toneMarked)
        assertEquals("guī", PinyinAnalyzer.analyze("guī").toneMarked)
    }
}
