package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale, sized for a phone.
 *
 * This was previously a single `bodyLarge` override with every other role left at the M3
 * desktop baseline, and nothing in the app used it: 159 call sites across 10 files
 * hardcoded `fontSize = N.sp` directly. The practical consequences were that the app could
 * not honour the system font-size setting, and that the smallest text in the app had
 * drifted to 9sp — below the legibility floor, and marginal on a 6.1" phone at arm's
 * length.
 *
 * The values below are Material 3's roles, tightened by roughly half a step where the
 * desktop baseline is generous, because a phone is read at arm's length on a 360dp-wide
 * surface rather than at desk distance on a monitor. `labelSmall` is the floor at 11sp and
 * is where the 9sp and 10sp outliers are meant to land.
 *
 * Migrating the remaining call sites onto these roles is deliberate incremental work, not
 * a scripted find-and-replace: a mechanical rewrite of `fontSize = 12.sp` to
 * `style = MaterialTheme.typography.bodySmall` can silently change the weight of a label
 * that already sets `fontWeight`, and the screenshot tests are the only thing that would
 * notice.
 *
 * **11sp is the floor for any text a learner has to read, not a preference.** Four labels
 * were sitting at 9sp — the home pillar subtitles, the HSK badge in the library, and the
 * profile badge in settings — and two more at 10sp. On a 6.1" phone at arm's length those
 * are below the point where the glyphs are reliably distinguishable, and every one of them
 * is a badge carrying information: a difficulty level, a review state, a source. They are
 * all now at 11sp, which is what `labelSmall` holds. There is no constant enforcing this,
 * because a constant nothing reads is documentation wearing a type — the rule lives here
 * and is checked by reading the call sites, which is why the audit is in
 * `docs/MOBILE_UX_AUDIT.md` rather than in a lint rule.
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

