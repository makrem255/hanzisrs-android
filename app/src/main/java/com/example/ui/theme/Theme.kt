package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * The midnight-navy scheme. The app's only scheme.
 *
 * The `surfaceContainer` family is set explicitly. Material 3's own defaults are
 * purple-tinted (they assume a dynamic-colour seed), and the navy ramp in
 * `Color.kt` is a deliberate step ramp, so letting the library derive the
 * mid-tones would shift every card in the app by a visible amount.
 */
private val MidnightNavyColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = AccentPrimaryInk,
    primaryContainer = AccentPrimaryDim,
    onPrimaryContainer = TextLight,
    secondary = AccentCyan,
    onSecondary = DarkBg,
    secondaryContainer = AccentCyanContainer,
    onSecondaryContainer = TextLight,
    tertiary = AccentViolet,
    onTertiary = DarkBg,
    tertiaryContainer = AccentVioletContainer,
    onTertiaryContainer = TextLight,
    error = AccentRed,
    onError = DarkBg,
    errorContainer = AccentRedContainer,
    onErrorContainer = AccentRed,
    background = DarkBg,
    onBackground = TextLight,
    surface = DarkSurfaceCard,
    onSurface = TextLight,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkSurfaceContainer,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceElevated,
    surfaceContainerHighest = DarkSurfaceHighest,
    outline = OutlineBorder,
    outlineVariant = OutlineSubtle
)

/**
 * Corner radii. Material 3's stock shapes are conservative; the product language is
 * rounder and more generous, so every step is opened up by roughly one notch.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

/**
 * Applies the app's theme.
 *
 * There is no `darkTheme` and no `dynamicColor` parameter, and that is a decision
 * rather than an omission.
 *
 * **Honouring `darkTheme` does not work today, and shipping it would have broken the app.**
 * The screens do not read their colours from `MaterialTheme.colorScheme` — they name
 * `DarkBg`, `DarkSurfaceCard`, `TextLight` and so on directly. Swapping in
 * `lightColorScheme` therefore changes only the components that *do* use the scheme:
 * `NavigationBar`, `TopAppBar`, `Switch`, `OutlinedTextField`, `LinearProgressIndicator`.
 * A phone set to light mode would have got a white bottom bar and pale text fields sitting
 * on a navy screen. That is worse than ignoring the parameter, because it looks like
 * a theme and is not one.
 *
 * **What a light theme would actually require:** every one of those call sites replaced with
 * a `MaterialTheme.colorScheme` token, plus a second value per token for the light case.
 * That is a tokenisation pass over the whole UI, not a colour-scheme swap, and doing it
 * as one line here would only have hidden the work.
 *
 * **`dynamicColor` is the same argument with a second problem.** Wallpaper-derived accents
 * would replace the blues that carry meaning: the four SRS rating buttons are the one
 * place in the app where a colour is a *label*, and they sit next to a legend that names
 * them. If those four could change hue to match someone's wallpaper, the legend would stop
 * being true.
 *
 * Both parameters belong in the signature of the first commit that has tokenised the
 * screens, and not before. `isSystemInDarkTheme` is deliberately not consulted.
 */
@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MidnightNavyColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
