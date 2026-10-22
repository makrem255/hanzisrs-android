package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.StorageValues
import com.example.ui.components.AppCard
import com.example.ui.components.IconBadge
import com.example.ui.components.PrimaryButton
import com.example.ui.components.ProgressRing
import com.example.ui.components.SecondaryButton
import com.example.ui.components.SegmentedSelector
import com.example.ui.components.StatusDot
import com.example.ui.components.StatusPill
import com.example.ui.screens.HomeStatusRow
import com.example.ui.screens.WelcomeScreen
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A board of the design system's parts, rendered in both themes.
 *
 * The colours in this app are not decoration that can be checked in a text
 * editor: a button that is 4.4:1 instead of 4.6:1 is a failure a reader of the
 * diff would never see. This renders the shared pieces - the primary button, the
 * four rating fills, the chips, the badges, the progress meters, the navigation
 * bar - so the result can be looked at in both themes, side by side with the
 * contrast numbers the palette test asserts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class DesignSystemBoardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun light_board() {
        composeTestRule.setContent {
            MyApplicationTheme(themeMode = StorageValues.ThemeMode.LIGHT) {
                Board()
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage(filePath = "src/test/screenshots/design-board-light.png")
    }

    @Test
    fun dark_board() {
        composeTestRule.setContent {
            MyApplicationTheme(themeMode = StorageValues.ThemeMode.DARK) {
                Board()
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage(filePath = "src/test/screenshots/design-board-dark.png")
    }

    @Test
    fun light_welcome_screen() {
        composeTestRule.setContent {
            MyApplicationTheme(themeMode = StorageValues.ThemeMode.LIGHT) {
                WelcomeScreen(onGetStarted = {}, onLogIn = {})
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage(filePath = "src/test/screenshots/welcome-screen-light.png")
    }

    @Test
    fun dark_welcome_screen() {
        composeTestRule.setContent {
            MyApplicationTheme(themeMode = StorageValues.ThemeMode.DARK) {
                WelcomeScreen(onGetStarted = {}, onLogIn = {})
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage(filePath = "src/test/screenshots/welcome-screen-dark.png")
    }
}

@Composable
private fun Board() {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Primary action", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        PrimaryButton(text = "Start review", onClick = {})

        Text("Secondary, vocabulary accent", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        SecondaryButton(text = "Practice weak words", onClick = {}, accent = colors.vocabulary)

        Text("The four grades", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Grade("Again", colors.error, colors.onError)
            Grade("Hard", colors.vocabulary, colors.onVocabulary)
            Grade("Good", colors.success, colors.onSuccess)
            Grade("Easy", colors.primaryAction, colors.onPrimaryAction)
        }

        Text("Badges and pills", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Default.AutoAwesome, tint = colors.primaryAction, background = colors.primaryAction.copy(alpha = 0.16f))
            IconBadge(icon = Icons.Default.AutoAwesome, tint = colors.vocabulary, background = colors.vocabulary.copy(alpha = 0.16f))
            IconBadge(icon = Icons.Default.AutoAwesome, tint = colors.learningFocus, background = colors.learningFocus.copy(alpha = 0.16f))
            IconBadge(icon = Icons.Default.AutoAwesome, tint = colors.success, background = colors.success.copy(alpha = 0.16f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(text = "Mastered", accent = colors.success)
            StatusPill(text = "In progress", accent = colors.vocabulary)
            StatusPill(text = "Reviewing", accent = colors.learningFocus)
            StatusPill(text = "New", accent = colors.primaryAction)
        }

        Text("A vocabulary row", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        AppCard(modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(colors.vocabularyContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text("好", fontSize = 24.sp, color = colors.textPrimary)
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("hǎo", style = MaterialTheme.typography.titleSmall, color = colors.vocabulary)
                    Text("HSK 1", style = MaterialTheme.typography.labelSmall, color = colors.learningFocus)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = colors.success)
                        Text(
                            "  Reviewing",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }
        }

        Text("Progress", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProgressRing(progress = 0.5f, accent = colors.primaryAction, diameter = 64.dp) {
                Text("50%", style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
            }
            ProgressRing(progress = 1f, accent = colors.success, diameter = 64.dp) {
                Text("5/5", style = MaterialTheme.typography.labelMedium, color = colors.textPrimary)
            }
        }

        Text("Switch and selection", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Switch(
                checked = true,
                onCheckedChange = {},
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimaryAction,
                    checkedTrackColor = colors.primaryAction
                )
            )
            SegmentedSelector(
                options = listOf("Day", "Week", "Month"),
                selected = "Day",
                onSelect = {},
                label = { it },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text("The compact home indicators", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        HomeStatusRow(totalXp = 1240, level = 3, streakDays = 5)

        Text("Navigation", style = MaterialTheme.typography.labelLarge, color = colors.textSecondary)
        NavigationBar(containerColor = colors.background, tonalElevation = 0.dp) {
            NavigationBarItem(
                selected = true,
                onClick = {},
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text("Home", fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.onPrimaryAction,
                    selectedTextColor = colors.primaryAction,
                    indicatorColor = colors.primaryAction,
                    unselectedIconColor = colors.textSecondary,
                    unselectedTextColor = colors.textSecondary
                )
            )
            NavigationBarItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Default.School, contentDescription = null) },
                label = { Text("Learn", fontSize = 11.sp) }
            )
            NavigationBarItem(
                selected = false,
                onClick = {},
                icon = { Icon(Icons.Default.Person, contentDescription = null) },
                label = { Text("Profile", fontSize = 11.sp) }
            )
        }
    }
}

/** One rating button, drawn the way the review screen draws it. */
@Composable
private fun Grade(label: String, fill: Color, ink: Color) {
    ElevatedButton(
        onClick = {},
        colors = ButtonDefaults.elevatedButtonColors(containerColor = fill, contentColor = ink),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.height(56.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = ink)
            Text("<1d", fontSize = 11.sp, color = ink)
        }
    }
}


