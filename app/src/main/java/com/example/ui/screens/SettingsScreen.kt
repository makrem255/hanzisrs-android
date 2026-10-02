package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.MinTouchTarget
import com.example.data.repository.UserRepository
import com.example.ui.components.DailyLimitStepper
import com.example.ui.components.SwitchRow
import com.example.ui.components.rememberNotificationRequest
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.SrsAgainDark
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.MainViewModel
import com.example.util.plural

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onLogout: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isSlowTts by viewModel.isSlowTts.collectAsStateWithLifecycle()
    val dueCount by viewModel.dueCount.collectAsStateWithLifecycle()

    // Saveable so turning the phone sideways does not silently re-enable a setting the
    // learner just turned off. It is still not persisted across leaving the screen, which
    // is the honest state of things: this is a local preview switch, not a stored
    // preference, and pretending otherwise would mean inventing a preferences table.
    var notificationPreviewsEnabled by rememberSaveable { mutableStateOf(true) }
    var notificationMessage by remember { mutableStateOf<String?>(null) }

    // Declared here rather than next to the button. It was inside the scrolling `Column`,
    // and a `var` written in a lambda is local to that lambda — so the `AlertDialog` below
    // the column, which is what actually reads and writes it, could not see it.
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    // The permission check and the request live in `rememberNotificationRequest`, shared with
    // the home screen's bell. This screen had its own correct copy inline; the home screen had
    // none, which is why its bell silently did nothing on Android 13+. One rule, one
    // implementation, so the two controls cannot diverge again.
    val requestNotification = rememberNotificationRequest(
        onGranted = { viewModel.sendDueReminderNotification() },
        onDenied = {
            notificationMessage = "Notifications are off. You can enable them in Android system settings."
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                // This app bar does not add the status-bar inset itself: the outer
                // Scaffold in MainActivity already padded the whole NavHost by it, and the
                // insets were therefore applied twice - once by that padding and once by
                // TopAppBarDefaults.windowInsets - pushing every title down by an extra
                // ~24-48dp. AuthScreen is the reason this is fixed here rather than by
                // zeroing the outer Scaffold's contentWindowInsets: it has no app bar of its
                // own and depends on that outer padding for its top inset.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Profile",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextLight
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // User Account Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(AccentPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentUser?.displayName?.firstOrNull()?.toString()?.uppercase() ?: "U",
                            color = AccentPrimaryInk,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentUser?.displayName?.takeIf { it.isNotBlank() }
                                ?: "Chinese learner",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextLight
                        )
                        Text(
                            // No invented fallback address. It used to be a literal
                            // "learner@hanzisrs.com" shown whenever there was no user, which
                            // is a real-looking identifier for an account that does not
                            // exist. An honest blank beats a plausible fabrication.
                            text = currentUser?.identifier?.takeIf { it.isNotBlank() }
                                ?: "Signed out",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = DarkSurfaceElevated,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder)
                        ) {
                            Text(
                                text = "Local profile · this device",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Daily workload --------------------------------------------------------------------------------
            //
            // These two numbers decide what the app asks the learner to study each day.
            // `DashboardDao` reads them to cap the new words and reviews offered, and the
            // "caught up" judgement is made against the same pair - so they are the app's answer
            // to "what should I do today?", set by nobody.
            //
            // Both were stored, validated (0..200), migrated and consumed, and unreachable from
            // any screen. Every learner was on 10 new words a day for the life of their account,
            // which is a defensible default for an unknown learner and a wrong one for a known
            // one: someone studying for an exam in three weeks and someone dipping in for ten
            // minutes are not served by the same number.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = AccentPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Daily Workload",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextLight
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "How much the app offers you each day. Reviews come first; new " +
                            "words fill whatever room is left.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // `null` while the preference row is still loading, which is different from
                    // "the learner set it to the default". Rendering the default in that window
                    // would show a number as though it were chosen, and a stepper there would
                    // let a tap set a value the learner never looked at.
                    val limits = viewModel.dailyLimits.collectAsStateWithLifecycle().value

                    if (limits == null) {
                        Text(
                            text = "Loading your limits…",
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    } else {
                        DailyLimitStepper(
                            label = "New words per day",
                            supporting = "Unfamiliar characters introduced at once",
                            value = limits.newWords,
                            minValue = 0,
                            // The offered ceiling, not `MAX_DAILY_LIMIT`. A value above this
                            // can exist — the registration path accepts up to 200 — and the
                            // stepper must render it truthfully rather than showing a number the
                            // learner never chose. "Increase" simply stops being available.
                            maxValue = UserRepository.OFFERED_MAX_NEW_WORDS,
                            step = 5,
                            onValueChange = viewModel::setDailyNewWordLimit,
                            modifier = Modifier.testTag("daily_new_word_limit")
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        DailyLimitStepper(
                            label = "Reviews per day",
                            supporting = "Words already in your collection",
                            value = limits.reviews,
                            minValue = 0,
                            maxValue = UserRepository.OFFERED_MAX_REVIEWS,
                            step = 10,
                            onValueChange = viewModel::setDailyReviewLimit,
                            modifier = Modifier.testTag("daily_review_limit")
                        )

                        // Zero is a legitimate choice - it is how a learner pauses new material
                        // and keeps their retention alive - and without this the screen offers a
                        // setting whose consequence it never explains.
                        if (limits.newWords == 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No new words will be introduced. Words you already " +
                                    "have still come up for review.",
                                fontSize = 11.sp,
                                color = AccentPrimary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Daily Reminder Routine Notification Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AccentPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                        text = "Review Alert Preview",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextLight
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Preview the alert shown when you choose to review. Scheduled daily reminders are not configured in this local-only version.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // The whole row is the switch, not just the switch.
                    //
                    // The target was already 48dp — but it sat in the right-hand ~15% of a
                    // full-width row, and a phone user aims at the words "Enable alert
                    // previews", which did nothing. `SwitchRow` puts the row on the toggle
                    // and marks it with `Role.Switch`, so it is announced as one control
                    // rather than as a label followed by an unlabelled switch.
                    SwitchRow(
                        checked = notificationPreviewsEnabled,
                        onCheckedChange = { notificationPreviewsEnabled = it },
                        label = "Enable alert previews",
                        supporting = "Android may ask for notification permission",
                        labelColor = TextLight,
                        supportingColor = TextMuted,
                        modifier = Modifier.testTag("notification_previews_switch")
                    ) {
                        Switch(
                            checked = notificationPreviewsEnabled,
                            // Null: the row owns the interaction, so the switch must not
                            // also claim the tap or the state change would be handled twice.
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentPrimaryInk,
                                checkedTrackColor = AccentPrimary,
                                uncheckedThumbColor = TextSubtle,
                                uncheckedTrackColor = DarkSurfaceContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = requestNotification,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DarkSurfaceContainer,
                            contentColor = AccentPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("test_notification_btn"),
                        enabled = notificationPreviewsEnabled
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            // Was `"$dueCount words due"`, so a learner with exactly one word
                            // due - the most likely moment to be previewing an alert at all -
                            // read "1 words due". `NotificationHelper` fixed the identical
                            // defect in its own string; this was the third site, hand-rolled
                            // because the shared helper was private to `DashboardAggregator`.
                            text = "Preview alert ($dueCount ${plural(dueCount, "word", "words")} due)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (notificationMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(notificationMessage ?: "", color = SrsAgainDark, fontSize = 11.sp)
                    }
                }
            }

            // Mandarin Audio & TTS Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = AccentPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Mandarin Speech Synthesis (TTS)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextLight)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // The whole row is the switch. See the row above for why.
                    SwitchRow(
                        checked = isSlowTts,
                        onCheckedChange = { viewModel.toggleSlowTts() },
                        label = "Slow pronunciation",
                        supporting = if (isSlowTts) {
                            "0.65× — slower, clearer"
                        } else {
                            "0.90× — natural speed"
                        },
                        labelColor = TextLight,
                        supportingColor = TextMuted,
                        modifier = Modifier.testTag("slow_tts_switch")
                    ) {
                        Switch(
                            checked = isSlowTts,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentPrimaryInk,
                                checkedTrackColor = AccentPrimary,
                                uncheckedThumbColor = TextSubtle,
                                uncheckedTrackColor = DarkSurfaceContainer
                            )
                        )
                    }
                }
            }

            // AI Model Configuration
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentPrimary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("AI Content Generation Engine", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextLight)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Generates editable word details when a Gemini key is configured; otherwise, the app uses its built-in starter dictionary and character fallback.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            }

            // Sign Out
            //
            // Confirmed, because this is the one control here that ends the session and
            // cannot be undone from the screen. A mis-tap on a phone is cheap and easy; the
            // consequence of signing out by accident is losing the current session's context
            // and having to authenticate again to get it.
            OutlinedButton(
                onClick = { confirmSignOut = true },
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SrsAgainDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, SrsAgainDark.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .testTag("logout_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign out", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?", color = TextLight, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Your words and review history stay on this device. You will need to " +
                        "sign in again to study.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        viewModel.logout()
                        onLogout()
                    },
                    modifier = Modifier.testTag("confirm_sign_out")
                ) {
                    Text("Sign out", color = SrsAgainDark, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text("Cancel", color = AccentPrimary)
                }
            },
            containerColor = DarkSurfaceCard,
            shape = RoundedCornerShape(24.dp)
        )
    }
}
