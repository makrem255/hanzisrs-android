package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The lilac-on-charcoal scheme. The app's only scheme.
 *
 * The `surfaceContainer` family is named rather than derived: Material 3's own defaults
 * are purple-tinted, and the app's greys are a deliberate neutral ramp, so letting the
 * library pick them would shift every card in the app by a visible amount.
 */
private val SophisticatedDarkColorScheme = darkColorScheme(
    primary = LilacPrimary,
    onPrimary = LilacPrimaryDark,
    primaryContainer = LilacSecondaryContainer,
    onPrimaryContainer = TextLight,
    secondary = LilacSecondary,
    onSecondary = LilacPrimaryDark,
    secondaryContainer = DarkSurfaceElevated,
    onSecondaryContainer = TextLight,
    tertiary = LilacTertiary,
    onTertiary = LilacPrimaryDark,
    background = DarkBg,
    onBackground = TextLight,
    surface = DarkSurfaceCard,
    onSurface = TextLight,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = TextMuted,
    outline = OutlineBorder,
    outlineVariant = OutlineBorder.copy(alpha = 0.5f)
)

/**
 * Applies the app's theme.
 *
 * There is no `darkTheme` and no `dynamicColor` parameter, and that is a decision rather
 * than an omission. An earlier version accepted both and ignored both; a later version
 * accepted both and honoured them, and both were wrong.
 *
 * **Honouring `darkTheme` does not work today, and shipping it would have broken the app.**
 * The screens do not read their colours from `MaterialTheme.colorScheme` — they name
 * `DarkBg`, `DarkSurfaceCard`, `TextLight` and so on at 143 call sites. Swapping in
 * `lightColorScheme` therefore changes only the components that *do* use the scheme:
 * `NavigationBar`, `TopAppBar`, `Switch`, `OutlinedTextField`, `LinearProgressIndicator`.
 * A phone set to light mode would have got a white bottom bar and pale text fields sitting
 * on a charcoal screen. That is worse than ignoring the parameter, because it looks like
 * a theme and is not one.
 *
 * **What a light theme would actually require:** every one of those 143 sites replaced with
 * a `MaterialTheme.colorScheme` token, plus a second value per token for the light case.
 * That is a tokenisation pass over the whole UI, not a colour-scheme swap, and doing it
 * as one line here would only have hidden the work.
 *
 * **`dynamicColor` is the same argument with a second problem.** Wallpaper-derived accents
 * would replace the lilac that carries meaning: the four SRS rating buttons are the one
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
        colorScheme = SophisticatedDarkColorScheme,
        typography = Typography,
        content = content
    )
}
