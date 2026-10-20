package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.data.model.StorageValues

// ─────────────────────────────────────────────────────────────────────────────
// HanziSRS palette.
//
// One neutral system in two themes, plus a small set of accent tokens. Surfaces
// step from background to card to raised fills in greys; text steps from primary
// to tertiary in greys; the primary button fill is near-black on light and
// near-white on dark. Green for success and red for error carry meaning, as
// they always have.
//
// The accent tokens - blue for primary actions, orange for vocabulary and
// review, purple for selected learning areas - are the approved colourful
// direction. They are accents on the neutral system, not a replacement for it:
// a screen adopts them where the mockup calls for colour and keeps the neutral
// steps everywhere else, so the app stays one design. Every accent has a light
// and a dark value and is contrast-checked against the surfaces it is drawn on
// by MonoContrastTest, so a colourful screen is still a readable one.
//
// Every token below exists in a light and a dark value. Screens must read them
// through [AppTheme] rather than naming a fixed colour, which is what makes the
// theme switch actually switch the app instead of only its Material components.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Every colour the UI may use, resolved for one theme.
 *
 * A flat data class rather than `MaterialTheme.colorScheme` roles because the
 * app's components predate scheme roles: cards, badges, charts, the stroke
 * grid and the review wheel all need named steps, not `primaryContainer`.
 * The Material scheme in `Theme.kt` is derived from these same values so the
 * stock components (switches, text fields, dialogs, navigation) agree.
 */
data class AppColors(
    // Surfaces: background -> card -> raised fills, greys only.
    val background: Color,
    val surfaceAlt: Color,
    val card: Color,
    val elevated: Color,
    val highest: Color,
    // Text: primary -> secondary -> tertiary, greys only.
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // Lines.
    val border: Color,
    val borderSubtle: Color,
    // The one interactive fill.
    val button: Color,
    val onButton: Color,
    // Meaning: success (green) and error (warm red).
    val success: Color,
    val successContainer: Color,
    val error: Color,
    val errorContainer: Color,
    // Primary actions: bright blue. Fills the primary button and marks the
    // focused field and the selected filter. Passes 4.5:1 as text on card and
    // as a fill under the button label in both themes.
    val primaryAction: Color,
    val onPrimaryAction: Color,
    val primaryActionContainer: Color,
    // Vocabulary and review: orange. Marks pinyin, HSK badges and the weak-word
    // practice affordance - the "study material" hue of the colourful direction.
    val vocabulary: Color,
    val vocabularyContainer: Color,
    // Selected learning areas: purple. Tints the learning-state chips and the
    // pillar icons where a selection or a focus area needs to stand apart from
    // the blue action and the orange study accents.
    val learningFocus: Color,
    val learningFocusContainer: Color,
    // Scheduling states. Again reads as error, Good reads as success; Hard and
    // Easy are neutral greys at two distinct steps so the four stay separable
    // without reintroducing hue. Every button also carries a text label, so
    // colour is never the only signal.
    val hard: Color,
    val hardContainer: Color,
    val easy: Color,
    val easyContainer: Color,
    // Stroke-practice grid.
    val gridLine: Color,
    val gridCenter: Color,
    val ghostText: Color,
)

internal val DarkAppColors = AppColors(
    background = Color(0xFF000000),
    surfaceAlt = Color(0xFF111111),
    card = Color(0xFF0A0A0A),
    elevated = Color(0xFF161616),
    highest = Color(0xFF202020),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB3B3B3),
    textTertiary = Color(0xFF6E6E6E),
    border = Color(0xFF262626),
    borderSubtle = Color(0xFF1A1A1A),
    button = Color(0xFFFFFFFF),
    onButton = Color(0xFF000000),
    success = Color(0xFF4ADE80),
    successContainer = Color(0xFF14301D),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3A1515),
    hard = Color(0xFF9A9A9A),
    hardContainer = Color(0xFF2A2A2A),
    easy = Color(0xFFD8D8D8),
    easyContainer = Color(0xFF333333),
    // Accents on the OLED-dark ground: light hues so they clear 4.5:1 on the
    // near-black card, with deep containers so a tinted chip still carries
    // white text.
    primaryAction = Color(0xFF60A5FA),
    onPrimaryAction = Color(0xFF0B1220),
    primaryActionContainer = Color(0xFF1E3A5F),
    vocabulary = Color(0xFFFB923C),
    vocabularyContainer = Color(0xFF431A05),
    learningFocus = Color(0xFFA78BFA),
    learningFocusContainer = Color(0xFF2E1065),
    gridLine = Color(0xFF262626),
    gridCenter = Color(0xFF8A8A8A),
    ghostText = Color(0x24FFFFFF),
)

internal val LightAppColors = AppColors(
    background = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF5F5F5),
    card = Color(0xFFFFFFFF),
    elevated = Color(0xFFF0F0F0),
    highest = Color(0xFFE8E8E8),
    textPrimary = Color(0xFF111111),
    // One step darker than the reference #737373: that value reaches only 4.16:1 on the
    // raised surface, below the 4.5 floor this file's contrast test enforces. The
    // accessibility requirement outranks the exact hex.
    textSecondary = Color(0xFF6B6B6B),
    textTertiary = Color(0xFFA6A6A6),
    border = Color(0xFFE5E5E5),
    borderSubtle = Color(0xFFEFEFEF),
    button = Color(0xFF111111),
    onButton = Color(0xFFFFFFFF),
    success = Color(0xFF15803D),
    successContainer = Color(0xFFE3F2E8),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFAE9E9),
    hard = Color(0xFF737373),
    hardContainer = Color(0xFFEFEFEF),
    easy = Color(0xFF333333),
    easyContainer = Color(0xFFE2E2E2),
    // Accents on the white ground: deeper hues so each clears 4.5:1 as text on
    // the card, with pale containers for the tinted chips. Blue is a step
    // darker than the pure bright-blue reference for exactly the same reason
    // textSecondary is: the contrast floor outranks the exact hex.
    primaryAction = Color(0xFF1D6FD8),
    onPrimaryAction = Color(0xFFFFFFFF),
    primaryActionContainer = Color(0xFFDCEBFF),
    vocabulary = Color(0xFFB4530E),
    vocabularyContainer = Color(0xFFFFE9D6),
    learningFocus = Color(0xFF6D28D9),
    learningFocusContainer = Color(0xFFF0E7FF),
    gridLine = Color(0xFFDCDCDC),
    gridCenter = Color(0xFF737373),
    ghostText = Color(0x24000000),
)

/**
 * The colours for the current theme.
 *
 * Provided by [MyApplicationTheme]. The default is dark so a preview or test
 * that forgets the theme still renders the historical look rather than
 * crashing on an unprovided composition local.
 */
val LocalAppColors = compositionLocalOf { DarkAppColors }

/** Read-only access to [LocalAppColors] without naming the local at each site. */
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

/**
 * The one mapping from a card's scheduling state to the colour that means it.
 *
 * Composable because the answer depends on the theme: Hard and Easy are grey
 * steps that differ between light and dark. The `when` is over the enum with
 * no `else`, so adding a state is a compile error here. Unknown stored values
 * resolve to `null` at the boundary ([srsStateColorOrNull]), rendered as
 * tertiary grey - "state not recognised" - rather than a colour that asserts
 * a scheduling judgement the data does not support.
 */
@Composable
@ReadOnlyComposable
fun srsStateColor(state: StorageValues.CardState): Color {
    val colors = AppTheme.colors
    return when (state) {
        StorageValues.CardState.NEW -> colors.error
        StorageValues.CardState.LEARNING -> colors.hard
        StorageValues.CardState.REVIEW -> colors.success
        StorageValues.CardState.MASTERED -> colors.easy
    }
}

/**
 * As [srsStateColor], for a state still held as its stored `String`.
 *
 * Returns `null` for a value that is not a declared state, so the caller has to
 * decide what an unknown state looks like rather than inheriting a default by
 * accident.
 */
@Composable
@ReadOnlyComposable
fun srsStateColorOrNull(storedState: String?): Color? =
    StorageValues.CardState.fromStorage(storedState)?.let { srsStateColor(it) }
