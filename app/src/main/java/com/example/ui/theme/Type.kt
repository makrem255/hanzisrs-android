package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale, sized for a phone.
 *
 * These are Material 3's roles, tightened by roughly half a step where the
 * desktop baseline is generous, because a phone is read at arm's length on a
 * 360dp-wide surface rather than at desk distance on a monitor. `labelSmall` is
 * the floor at 11sp and is where the 9sp and 10sp outliers are meant to land.
 *
 * **11sp is the floor for any text a learner has to read, not a preference.** The
 * smallest text in the app is a badge carrying information — a difficulty level, a
 * review state, a source — and below 11sp those are not reliably distinguishable
 * on a 6.1" phone at arm's length.
 *
 * The scales below the M3 roles are the product's own: Chinese display sizes,
 * pinyin, and the tabular number style used by the statistics cards. They live here
 * rather than inline so that the size of a hero character is decided in one place.
 */
private val Default = FontFamily.Default

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.25).sp
    ),
    displaySmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp
    )
)

// ── Chinese display scale ────────────────────────────────────────────────────
//
// Chinese characters are the primary learning element, so they get their own scale
// and are always laid out with room to breathe. The family is the platform default,
// which resolves to Noto Sans CJK on every device this app supports; bundling a
// display face is a deliberate future step, not something to fake with a font that
// may not contain the glyphs.

/** The hero character on the review deck and the character screen. */
val HanziHero = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 88.sp,
    lineHeight = 96.sp
)

/** A large character: card headers, the character detail sheet. */
val HanziLarge = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 56.sp,
    lineHeight = 64.sp
)

/** A medium character: list leaders and inline emphasis. */
val HanziMedium = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 36.sp,
    lineHeight = 44.sp
)

/** A small character: compact rows. */
val HanziSmall = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 30.sp
)

// ── Pinyin ───────────────────────────────────────────────────────────────────
//
// Pinyin sits directly under the character and above the translation. It is set a
// step smaller than the character and a step larger than the meaning, so the three
// read as a single stack rather than three unrelated labels.

val PinyinLarge = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.6.sp
)

val PinyinText = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.5.sp
)

// ── Statistics ───────────────────────────────────────────────────────────────

/**
 * Numbers that get compared across a row — streak, accuracy, counts — are set with
 * tabular figures so the digits do not shift width as the value changes.
 */
val StatNumber = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    fontFeatureSettings = "tnum"
)

val StatNumberSmall = TextStyle(
    fontFamily = Default,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 26.sp,
    fontFeatureSettings = "tnum"
)
