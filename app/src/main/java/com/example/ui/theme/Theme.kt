package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.data.model.StorageValues

/**
 * The monochrome schemes, derived from the same tokens the screens read.
 *
 * Only the stock Material components take their colours from here (navigation
 * bar, switches, text fields, dialogs, progress indicators); everything custom
 * reads [AppTheme] directly. The two must agree, so both schemes are built
 * from the resolved [AppColors] rather than carrying a second set of values
 * that could drift.
 */
private fun lightScheme(colors: AppColors) = lightColorScheme(
    primary = colors.button,
    onPrimary = colors.onButton,
    primaryContainer = colors.elevated,
    onPrimaryContainer = colors.textPrimary,
    secondary = colors.textSecondary,
    onSecondary = colors.background,
    secondaryContainer = colors.elevated,
    onSecondaryContainer = colors.textPrimary,
    tertiary = colors.textSecondary,
    onTertiary = colors.background,
    tertiaryContainer = colors.elevated,
    onTertiaryContainer = colors.textPrimary,
    error = colors.error,
    onError = colors.background,
    errorContainer = colors.errorContainer,
    onErrorContainer = colors.error,
    background = colors.background,
    onBackground = colors.textPrimary,
    surface = colors.card,
    onSurface = colors.textPrimary,
    surfaceVariant = colors.elevated,
    onSurfaceVariant = colors.textSecondary,
    surfaceContainerLowest = colors.background,
    surfaceContainerLow = colors.surfaceAlt,
    surfaceContainer = colors.elevated,
    surfaceContainerHigh = colors.elevated,
    surfaceContainerHighest = colors.highest,
    outline = colors.border,
    outlineVariant = colors.borderSubtle,
)

private fun darkScheme(colors: AppColors) = darkColorScheme(
    primary = colors.button,
    onPrimary = colors.onButton,
    primaryContainer = colors.elevated,
    onPrimaryContainer = colors.textPrimary,
    secondary = colors.textSecondary,
    onSecondary = colors.background,
    secondaryContainer = colors.elevated,
    onSecondaryContainer = colors.textPrimary,
    tertiary = colors.textSecondary,
    onTertiary = colors.background,
    tertiaryContainer = colors.elevated,
    onTertiaryContainer = colors.textPrimary,
    error = colors.error,
    onError = colors.background,
    errorContainer = colors.errorContainer,
    onErrorContainer = colors.error,
    background = colors.background,
    onBackground = colors.textPrimary,
    surface = colors.card,
    onSurface = colors.textPrimary,
    surfaceVariant = colors.elevated,
    onSurfaceVariant = colors.textSecondary,
    surfaceContainerLowest = colors.background,
    surfaceContainerLow = colors.surfaceAlt,
    surfaceContainer = colors.elevated,
    surfaceContainerHigh = colors.elevated,
    surfaceContainerHighest = colors.highest,
    outline = colors.border,
    outlineVariant = colors.borderSubtle,
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
 * @param themeMode the learner's choice: explicit light or dark, or the system setting.
 * Defaults to system so previews and tests render without naming a mode.
 *
 * The mode resolves to one boolean, and everything below it - the token palette, the
 * Material scheme, the status- and navigation-bar icon colours - follows from that
 * boolean. There is deliberately no other input: a theme that read three sources could
 * disagree with itself, and the failure would be a screen that is half light.
 */
@Composable
fun MyApplicationTheme(
    themeMode: StorageValues.ThemeMode = StorageValues.ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        StorageValues.ThemeMode.LIGHT -> false
        StorageValues.ThemeMode.DARK -> true
        StorageValues.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkAppColors else LightAppColors

    // Edge-to-edge is enabled in MainActivity, so the system bars sit over app content.
    // Their icon colours must follow the theme or white icons vanish on the light
    // background and black icons vanish on the dark one.
    val context = LocalContext.current
    SideEffect {
        val activity = context as? Activity ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView
        )
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(colors) else lightScheme(colors),
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
