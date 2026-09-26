package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    onSurfaceVariant = TextSubtle,
    outline = OutlineBorder,
    outlineVariant = OutlineBorder.copy(alpha = 0.5f)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Sophisticated Dark is default
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = SophisticatedDarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}


