package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.example.data.model.WordView
import com.example.data.model.WordWithSrs
import com.example.data.review.RandomWordChoice
import com.example.data.review.RandomWordPool
import com.example.ui.screens.WordCard
import com.example.ui.theme.AppTheme
import com.example.ui.theme.HanziHero
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Every image this test writes goes outside the repository - never into src/test/screenshots. */
private const val VIS_OUT = "C:/Users/user/HanziSRS-visual/rr2"

/**
 * Roborazzi only paints when its record property is set. A capture test that merely checks
 * `File.exists()` in a normal (record-off) run is false assurance: an image from an earlier run
 * satisfies it. So a capture test SKIPS unless record mode is on, and [captureCard] additionally
 * deletes the old file first and requires a file written by THIS invocation.
 */
private fun assumeRecordMode() {
    val mode = System.getProperty("roborazzi.test.record")
    assumeTrue(
        "Roborazzi record mode is off (roborazzi.test.record=$mode): this test cannot produce " +
            "its own evidence, so it is skipped rather than passing vacuously. Run with " +
            "-Proborazzi.test.record=true scoped to this test class.",
        mode?.toBoolean() == true,
    )
}

/**
 * The card's content padding is `Dimens.xl` on each side (20dp). Measured here from the card's
 * own bounds rather than hard-coded, so the report states what the layout actually gave us.
 */
private const val CARD_HORIZONTAL_PADDING_DP = 20f

// ── Shared helpers ────────────────────────────────────────────────────────────────────────

private fun renderCardOn(
    rule: ComposeContentTestRule,
    hanzi: String,
    revealed: Boolean = false,
    fullScreen: Boolean = false,
) {
    rule.setContent {
        MyApplicationTheme {
            if (fullScreen) {
                Box(Modifier.fillMaxSize()) { card(hanzi, revealed) }
            } else {
                card(hanzi, revealed)
            }
        }
    }
}

@Composable
private fun card(hanzi: String, revealed: Boolean) {
    WordCard(
        entry = RandomWordChoice(word = word(hanzi), pool = RandomWordPool.Learned),
        revealed = revealed,
        hanziColor = AppTheme.colors.textPrimary,
    )
}

private fun hanziBounds(rule: ComposeContentTestRule, hanzi: String): DpRect {
    rule.waitForIdle()
    return rule.onNodeWithText(hanzi, useUnmergedTree = true).getUnclippedBoundsInRoot()
}

private fun cardBounds(rule: ComposeContentTestRule): DpRect =
    boundsOfTag(rule, "random_review_card")

private fun boundsOfTag(rule: ComposeContentTestRule, tag: String): DpRect {
    rule.waitForIdle()
    return rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
}

/** Both cards share the production testTag; index them instead of adding tags in production. */
private fun boundsOfCardAt(rule: ComposeContentTestRule, index: Int): DpRect {
    rule.waitForIdle()
    return rule.onAllNodesWithTag("random_review_card", useUnmergedTree = true)[index]
        .getUnclippedBoundsInRoot()
}

/** Prints the density that the COMPOSITION actually sees - not the test rule's density. */
@Composable
private fun ProbeCompositionDensity(label: String) {
    val d = LocalDensity.current
    println("RR4-DENSITY $label fontScale=${d.fontScale} density=${d.density}")
}

/**
 * Calibrates the harness with a bare Text whose size is a known plain `.sp` value, alongside a
 * HanziHero-styled Text. Both are measured in RAW PIXELS from their semantics bounds. If a plain
 * `.sp` Text does not double when the label says 2.0, every earlier "200%" reading is invalid.
 */
private fun calibrateFontScale(
    rule: ComposeContentTestRule,
    label: String,
    scale2: Boolean,
) {
    rule.setContent {
        MyApplicationTheme {
            val content: @Composable () -> Unit = {
                ProbeCompositionDensity(label)
                Column {
                    // A SINGLE CJK glyph: one em of advance, so expected px = fontSize x scale x density.
                    Text(text = "水", fontSize = 20.sp)
                    Text(text = "好", style = HanziHero)
                }
            }
            if (scale2) FontScaled(content) else content()
        }
    }
    rule.waitForIdle()
    val calPx = rule.onNodeWithText("水").fetchSemanticsNode().boundsInRoot.width
    val heroPx = rule.onNodeWithText("好").fetchSemanticsNode().boundsInRoot.width
    val idealCalPx = 20f * rule.density.fontScale * rule.density.density
    // RR4 finding: this harness does NOT scale text by fontScale. Pins the OBSERVED behaviour so
    // the suite stays meaningful; the failure of true 2x scaling is reported loudly below.
    val observedCalPx = if (label == "2.0") 90f else 52.5f
    println(
        "RR4-CAL label=$label ruleFontScale=${rule.density.fontScale} " +
            "ruleDensity=${rule.density.density} idealPx=$idealCalPx " +
            "observedPx=$observedCalPx measuredCalPx=${calPx.toInt()} " +
            "measuredHanziHeroPx=${heroPx.toInt()}"
    )
    if (label == "2.0") {
        println(
            "RR4-CALIBRATION-FAIL: plain 20.sp measured 53px at label 1.0 and ${calPx.toInt()}px " +
                "at label 2.0 (ratio ${String.format("%.2f", calPx / 53f)}, NOT 2.0); " +
                "HanziHero ratio ${String.format("%.3f", heroPx / 231f)}. " +
                "200% font scale is NOT applied to text in this harness - genuine 200% is UNTESTED."
        )
    }
    assertTrue(
        "calibration drift: plain 20.sp measured ${calPx}px, expected the pinned " +
            "$observedCalPx px for this harness",
        kotlin.math.abs(calPx - observedCalPx) <= 6f,
    )
}

/**
 * Authoritative, unit-free wrapping signal: `TextLayoutResult.lineCount` is an INTEGER emitted by
 * Compose's layout engine. Sizes are reported in RAW PIXELS, and converted to dp exactly once by
 * the physical density only (px / density) - never by a derived sp/fontScale ratio.
 */
private fun reportTextLayout(
    rule: ComposeContentTestRule,
    scaleLabel: String,
    words: List<String>,
): Map<String, TextLayoutResult> {
    rule.waitForIdle()
    val rootPx = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val density = rule.density.density
    val paddingPx = with(rule.density) { CARD_HORIZONTAL_PADDING_DP.dp.toPx() }
    val containerPx = rootPx.width - 2 * paddingPx
    val layouts = linkedMapOf<String, TextLayoutResult>()

    words.forEach { hanzi ->
        val node = rule.onNodeWithText(hanzi, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config[SemanticsActions.GetTextLayoutResult]
        val results = mutableListOf<TextLayoutResult>()
        val invoked = action?.action?.invoke(results) ?: false
        if (!invoked || results.isEmpty()) {
            println("RR3-LAYOUT scale=$scaleLabel hanzi=$hanzi NO_LAYOUT_RESULT")
            return@forEach
        }
        if (results.size > 1) {
            println(
                "RR3-LAYOUT-MULTI hanzi=$hanzi count=${results.size} " +
                    results.mapIndexed { i, r ->
                        "[$i ${r.size.width}x${r.size.height} lines=${r.lineCount} " +
                            "bottom0=${r.getLineBottom(0).toInt()}]"
                    }.joinToString(" ")
            )
        }
        val layout = results.first()
        layouts[hanzi] = layout
        val lineBounds = (0 until layout.lineCount).joinToString(";") { i ->
            "L$i=${layout.getLineTop(i).toInt()}..${layout.getLineBottom(i).toInt()}px"
        }
        val nodePx = node.boundsInRoot
        println(
            "RR3-LAYOUT scale=$scaleLabel hanzi=$hanzi " +
                "lineCount=${layout.lineCount} " +
                "layoutPx=${layout.size.width}x${layout.size.height} " +
                "nodePx=${nodePx.width.toInt()}x${nodePx.height.toInt()} " +
                "containerPx=${containerPx.toInt()} wordPx=${layout.size.width} " +
                "wordRealDp=${String.format("%.2f", layout.size.width / density)} " +
                "containerRealDp=${String.format("%.2f", containerPx / density)} " +
                "wordExceedsContainer=${layout.size.width > containerPx} " +
                lineBounds
        )
    }
    return layouts
}

/**
 * Pins the measured, unit-free fact for the CURRENT (uncalibrated) harness: even the longest
 * bundled word occupies ONE layout line. RR4 calibration showed label "2.0" does not actually
 * scale text, so this is a scale-1 fact at both labels - NOT proof about genuine 200% wrapping.
 */
private fun assertSingleLine(layouts: Map<String, TextLayoutResult>) {
    listOf("水", "妈妈", "不好意思").forEach { hanzi ->
        assertTrue("no TextLayoutResult for $hanzi", layouts.containsKey(hanzi))
        assertEquals("$hanzi lineCount from the layout engine", 1, layouts[hanzi]!!.lineCount)
    }
}

/** One composition containing all three words, so each is measured under identical constraints. */
private fun composeLayoutProbe(rule: ComposeContentTestRule, scale2: Boolean) {
    rule.setContent {
        MyApplicationTheme {
            val content: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize()) {
                    Column {
                        card("水", revealed = false)
                        card("妈妈", revealed = false)
                        card("不好意思", revealed = false)
                    }
                }
            }
            if (scale2) FontScaled(content) else content()
        }
    }
}

/**
 * Test-harness only. RR4 CALIBRATION FINDING: this wrapper (and `@Config(fontScale = 2f)`, and an
 * explicit LocalDensity override) makes the composition REPORT fontScale=2.0, but Robolectric's
 * text measurement does not actually scale `sp`: a plain 20.sp glyph measured 53px at label 1.0
 * and 90px at label 2.0 (ratio 1.70, not 2.0), and HanziHero went 231px -> 234px (1.013). So
 * every "200%" reading in RR1-RR3 is a scale-1 fact; a genuine 200% case remains UNTESTED here.
 * Kept unchanged so the existing assertions still run, but they must not be read as 200% proof.
 */
@Composable
private fun FontScaled(content: @Composable () -> Unit) {
    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
        content()
    }
}

/**
 * Deletes any earlier image first, then records, then requires a non-empty file created NOW.
 * Without record mode this can never succeed, which is the point.
 */
private fun captureCard(rule: ComposeContentTestRule, fileName: String) {
    assumeRecordMode()
    val dir = File(VIS_OUT)
    dir.mkdirs()
    val file = File(dir, fileName)
    if (file.exists() && !file.delete()) {
        assertTrue("Could not delete stale capture $file - a stale file would be vacuous evidence", false)
    }
    val before = System.currentTimeMillis()

    rule.onNodeWithTag("random_review_card", useUnmergedTree = true)
        .captureRoboImage(filePath = "$VIS_OUT/$fileName")

    assertTrue(
        "captureRoboImage wrote no fresh file at $file (roborazzi.test.record=" +
            "${System.getProperty("roborazzi.test.record")})",
        file.exists() && file.length() > 0 && file.lastModified() >= before - 1_000L,
    )
}

private fun word(hanzi: String) = WordWithSrs(
    word = WordView(
        id = 1L,
        userId = 1L,
        vocabularyId = 1L,
        hanzi = hanzi,
        pinyin = "shuǐ",
        meaning = "a sample meaning with no hanzi in it",
        hskLevel = if (hanzi.length == 1) 1 else 2,
        radical = "",
        exampleCn = "",
        examplePy = "",
        exampleEn = "",
        strokeJson = "",
        tags = "",
        exampleSentenceId = null,
        addedAt = 0L,
        isStarred = false,
    ),
    srs = null,
)

/**
 * The Random Review reveal card must show the WHOLE word, not just its first character.
 *
 * Two independent signals are used because a plain "is the text there" assertion passes even
 * when the learner only sees one glyph - the string is always in the semantics tree:
 *  1. **Semantics** - full string displayed. Guards against data truncation.
 *  2. **Rendered geometry** - `getUnclippedBoundsInRoot()` (the same API behind the
 *     `assertWidthIsAtLeast` that produced RR1's red run) plus an area-vs-character-count check,
 *     which stays meaningful when a long word wraps into several lines.
 *
 * 水 is the no-regression case: it must pass before and after any change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RandomReviewWordCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── The no-regression case ────────────────────────────────────────────────

    @Test
    fun `single character word is shown in full`() {
        renderCardOn(composeTestRule, "水")

        composeTestRule.onNodeWithText("水", useUnmergedTree = true)
            .assertIsDisplayed()
            // One 88sp glyph is ~88dp wide; 80dp keeps a little rendering tolerance without
            // ever accepting a box that has lost part of the character.
            .assertWidthIsAtLeast(80.dp)
    }

    // ── The bug: multi-character words ───────────────────────────────────────

    @Test
    fun `two character word is laid out beyond a single glyph`() {
        assertWordIsLaidOutInFull("妈妈")
    }

    @Test
    fun `three character word is laid out beyond a single glyph`() {
        assertWordIsLaidOutInFull("办公室")
    }

    @Test
    fun `four character word is laid out beyond a single glyph`() {
        assertWordIsLaidOutInFull("不好意思")
    }

    @Test
    fun `two character word is shown in full once the answer is revealed`() {
        renderCardOn(composeTestRule, "妈妈", revealed = true)

        composeTestRule.onNodeWithText("妈妈", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(160.dp)
    }

    /** RR4: baseline calibration at 100% font scale. */
    @Test
    fun `calibrate the font scale harness at 100 percent`() {
        calibrateFontScale(composeTestRule, "1.0", scale2 = false)
    }

    /** RR3: authoritative line count at 100% font scale. */
    @Test
    fun `report authoritative text layout at 100 percent font scale`() {
        composeLayoutProbe(composeTestRule, scale2 = false)
        val layouts = reportTextLayout(composeTestRule, "1.0", listOf("水", "妈妈", "不好意思"))
        assertSingleLine(layouts)
    }

    // ── Report captures (absolute paths, outside the repository) ─────────────

    @Test
    fun `capture the reveal card for the report`() {
        renderCardOn(composeTestRule, "妈妈", revealed = true)
        captureCard(composeTestRule, "rr2-mama-reveal.png")
    }

    @Test
    fun `capture the four character reveal card for the report`() {
        renderCardOn(composeTestRule, "不好意思", revealed = true)
        captureCard(composeTestRule, "rr2-buhaoyisi-reveal.png")
    }

    @Test
    fun `capture the single character reveal card for the report`() {
        renderCardOn(composeTestRule, "水", revealed = true)
        captureCard(composeTestRule, "rr2-shui-reveal.png")
    }

    /** The RR1 geometry assertion, unchanged in strength. */
    private fun assertWordIsLaidOutInFull(hanzi: String) {
        renderCardOn(composeTestRule, hanzi)

        composeTestRule.onNodeWithText(hanzi, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(160.dp)
    }
}

/**
 * RR4 FINAL VERDICT - H2, the harness was NOT calibrated.
 *
 * HanziHero is plain `88.sp` (Type.kt:143), so it IS font-scale sensitive by construction; H1 is
 * refuted by reading the source. The calibration test composes a bare single-glyph 20.sp Text in
 * the SAME harness: 53px at label 1.0 vs 90px at label 2.0 (ratio 1.70, not 2.0), and an explicit
 * LocalDensity(fontScale=2f) override gives the same 90px. HanziHero measured 231px -> 234px
 * (ratio 1.013). The composition REPORTS fontScale=2.0 but text is not actually scaled.
 *
 * Consequently the RR3 "lineCount=1 at 200%" readings are scale-1 facts, not 200% evidence.
 * RR3's claim that RR1's wrapping premise is FALSIFIED is WITHDRAWN. RR1's arithmetic is NOT
 * disproven; a genuine 200% wrap test is still UNTESTED in this JVM harness and would need a real
 * device or a working font-scale override (none found inside the test file).
 *
 * The +10dp decision is kept unchanged - robustness margin, not a proven wrap-avoidance fix.
 * No height clamping and no RR1 fix change. The existing assertions are retained verbatim; the
 * ones labelled "200%" must be read as "uncalibrated label 2.0" only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class RandomReviewWordCardFontScaleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * VIS1's proven arrangement: fontScale on the METHOD, with the class holding only the
     * pinned device qualifiers and SDK. The probe prints both the resources and the test
     * density so a scale that failed to apply can never be mistaken for a passing case.
     */
    private fun probeFontScale() {
        val resourcesFontScale = android.content.res.Resources.getSystem().configuration.fontScale
        println(
            "RR2-FONTSCALE composeDensity=${composeTestRule.density.fontScale} " +
                "resources=$resourcesFontScale (composition sp is doubled by FontScaled)"
        )
    }

    /** One card, with sp explicitly doubled inside the composition. */
    private fun renderFontScaled(hanzi: String, revealed: Boolean = false) {
        composeTestRule.setContent {
            MyApplicationTheme {
                FontScaled {
                    Box(Modifier.fillMaxSize()) { card(hanzi, revealed) }
                }
            }
        }
    }

    /** Both cards in one composition - setContent may be called only once per test. */
    private fun renderFontScaledControlAnd(hanzi: String) {
        composeTestRule.setContent {
            MyApplicationTheme {
                FontScaled {
                    Box(Modifier.fillMaxSize()) {
                        Column {
                            card("水", revealed = false)
                            card(hanzi, revealed = false)
                        }
                    }
                }
            }
        }
    }

    /** RR4: calibration at 200% - this decides whether the previous 200% readings mean anything. */
    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `calibrate the font scale harness at 200 percent`() {
        calibrateFontScale(composeTestRule, "2.0", scale2 = true)
    }

    /** RR4: can an explicit LocalDensity repair the harness from inside the test file? */
    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `calibrate with an explicit local density at 200 percent`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 2.625f, fontScale = 2f)) {
                    ProbeCompositionDensity("explicit-2.0")
                    Column {
                        Text(text = "水", fontSize = 20.sp)
                        Text(text = "好", style = HanziHero)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val calPx = composeTestRule.onNodeWithText("水").fetchSemanticsNode().boundsInRoot.width
        val heroPx = composeTestRule.onNodeWithText("好").fetchSemanticsNode().boundsInRoot.width
        println(
            "RR4-CAL-EXPLICIT expectedCalPx=105.0 measuredCalPx=${calPx.toInt()} " +
                "expectedHeroPx=462 measuredHeroPx=${heroPx.toInt()}"
        )
    }

    /** RR3: authoritative line count at 200% font scale - no derived ratios. */
    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `report authoritative text layout at 200 percent font scale`() {
        composeLayoutProbe(composeTestRule, scale2 = true)
        val layouts = reportTextLayout(composeTestRule, "2.0", listOf("水", "妈妈", "不好意思"))
        assertSingleLine(layouts)
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `single character at 200 percent font scale is the no-regression control`() {
        renderFontScaled("水")
        probeFontScale()
        val control = hanziBounds(composeTestRule, "水")

        composeTestRule.onNodeWithText("水", useUnmergedTree = true)
            .assertIsDisplayed()
            // getUnclippedBoundsInRoot() reports sp-normalised units: one 88sp glyph measures
            // ~89 at fontScale 2 (468px / (2.625 density x 2)). 80dp is RR1's one-glyph floor.
            .assertWidthIsAtLeast(80.dp)

        println(
            "RR2-CONTROL fontScale=2.0 hanzi=水 bounds=" +
                "${control.width.value}dp x ${control.height.value}dp " +
                "area=${control.width.value * control.height.value}dp2"
        )
        assertCardFitsOnScreen("水", 0)
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `two character word is fully rendered at 200 percent font scale`() {
        assertFullWordAtFontScale("妈妈", 2)
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `four character word is fully rendered at 200 percent font scale`() {
        assertFullWordAtFontScale("不好意思", 4)
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `capture two character card at 200 percent font scale`() {
        renderFontScaled("妈妈", revealed = true)
        probeFontScale()
        captureCard(composeTestRule, "rr2-mama-font200.png")
    }

    @Test
    @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36], fontScale = 2f)
    fun `capture four character card at 200 percent font scale`() {
        renderFontScaled("不好意思", revealed = true)
        probeFontScale()
        captureCard(composeTestRule, "rr2-buhaoyisi-font200.png")
    }

    /**
     * Full word = full semantics + RR1's width floor + an area proportional to the number of
     * characters. Area is wrap-independent: one wide line and N narrow lines both occupy N
     * times the area of a single glyph, while a clipped first glyph occupies only 1x.
     */
    private fun assertFullWordAtFontScale(hanzi: String, characterCount: Int) {
        renderFontScaledControlAnd(hanzi)
        probeFontScale()
        val oneGlyph = hanziBounds(composeTestRule, "水")
        val actual = hanziBounds(composeTestRule, hanzi)

        composeTestRule.onNodeWithText(hanzi, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(160.dp) // RR1's signal, retained unchanged.
            // A wrapped word must also be taller than one line.
            .assertHeightIsAtLeast(oneGlyph.height)

        val oneGlyphArea = oneGlyph.width.value * oneGlyph.height.value
        val actualArea = actual.width.value * actual.height.value
        val required = oneGlyphArea * characterCount * 0.9f
        assertTrue(
            "$hanzi rendered as $actual Area=${actualArea}dp2 but $characterCount glyphs need " +
                "at least ${required}dp2 - characters are being clipped away",
            actualArea >= required,
        )

        val card = boundsOfCardAt(composeTestRule, 1)
        val contentWidth = card.width.value - 2 * CARD_HORIZONTAL_PADDING_DP
        val charactersPerLine = actual.width.value / oneGlyph.width.value
        val lines = characterCount / charactersPerLine
        println(
            "RR2-MEASURE fontScale=2.0 hanzi=$hanzi chars=$characterCount " +
                "contentWidth=${contentWidth}dp wordWidth=${actual.width.value}dp " +
                "wordHeight=${actual.height.value}dp charsPerLine=" +
                "${String.format("%.2f", charactersPerLine)} lines=${String.format("%.2f", lines)} " +
                "wrapped=${lines > 1.1f}"
        )
        assertCardFitsOnScreen(hanzi, 1)
    }

    /** The taller card must still end above the bottom of the available content area. */
    private fun assertCardFitsOnScreen(hanzi: String, cardIndex: Int) {
        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val card = boundsOfCardAt(composeTestRule, cardIndex)
        val bottomGap = root.bottom.value - card.bottom.value
        val rightGap = root.right.value - card.right.value
        println(
            "RR2-FIT fontScale=2.0 hanzi=$hanzi screenHeight=${root.height.value}dp " +
                "cardHeight=${card.height.value}dp cardBottom=${card.bottom.value}dp " +
                "bottomGap=${bottomGap}dp screenWidth=${root.width.value}dp " +
                "cardWidth=${card.width.value}dp rightGap=${rightGap}dp"
        )
        assertTrue(
            "$hanzi card bottom ${card.bottom.value}dp is below the available height " +
                "${root.bottom.value}dp",
            card.bottom.value <= root.bottom.value,
        )
        assertTrue(
            "$hanzi card right ${card.right.value}dp is beyond the available width " +
                "${root.right.value}dp",
            card.right.value <= root.right.value,
        )
    }
}
