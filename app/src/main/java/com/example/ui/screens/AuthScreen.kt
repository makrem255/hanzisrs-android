package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.InlineNotice
import com.example.ui.components.SegmentedOption
import com.example.R
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentPrimary
import com.example.ui.theme.AccentPrimaryInk
import com.example.ui.theme.AccentSweep
import com.example.ui.theme.OutlineBorder
import com.example.ui.theme.TextLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSubtle
import com.example.ui.viewmodel.MainViewModel

@Composable
fun AuthScreen(
    viewModel: MainViewModel,
    onAuthSuccess: () -> Unit
) {
    var isRegisterMode by remember { mutableStateOf(false) }
    var inputMode by remember { mutableIntStateOf(0) } // 0 = Email, 1 = Phone Number

    // The keyboard's Next key has to be able to move between these fields. Declaring an
    // `ImeAction` without an action behind it draws the right glyph on the keyboard and
    // then does nothing when the key is pressed, which is worse than no action key at all.
    val focusManager = LocalFocusManager.current

    var emailOrPhone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Lifecycle-aware, so collection stops when the screen leaves the foreground instead of
    // keeping the auth query alive behind a locked screen.
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val authLoading by viewModel.authLoading.collectAsStateWithLifecycle()

    val canSubmit = !authLoading && emailOrPhone.isNotBlank() && password.length >= 8

    // One place that decides what a submit does, so the keyboard's Done key and the button
    // cannot drift apart.
    fun submit() {
        if (isRegisterMode) {
            viewModel.register(
                identifier = emailOrPhone,
                passwordPlain = password,
                displayName = displayName,
                isPhone = inputMode == 1,
                onSuccess = onAuthSuccess
            )
        } else {
            viewModel.login(
                identifier = emailOrPhone,
                passwordPlain = password,
                onSuccess = onAuthSuccess
            )
        }
    }

    // The scroll lives here, on the viewport, and not on the card.
    //
    // It was on the card, whose height is its own content height — a scroll modifier whose
    // viewport is already as tall as its content has nothing to scroll, so it never scrolled.
    // The Box around it is `contentAlignment = Center`, so whenever the card was taller than
    // the screen its top and bottom were pushed off both edges and stayed there. On a
    // 640dp phone that meant the logo and "Continue as Guest Learner" were both off-screen
    // with no way to reach them; in landscape it was guaranteed; in register mode, which adds
    // a field, the card outgrew a 360x640 screen outright.
    //
    // `imePadding` is the other half. The app runs edge to edge, so the window does not
    // resize for the keyboard and nothing is inset above it. Without this the password field
    // sits under the IME and the submit button with it.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Emblem Logo
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(AccentSweep),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "学",
                        color = AccentPrimaryInk,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    // From `strings.xml`, like the launcher icon's label. This said
                    // "HanziFlow" while the icon under it on the home screen said
                    // "HanziSRS" — two names for one app, visible in the same screenshot.
                    text = stringResource(R.string.app_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextLight,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isRegisterMode) "Create a local learning profile" else "Your daily SRS companion",
                    fontSize = 14.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(22.dp))

                InlineNotice(
                    text = "Profiles are stored on this device. Demo: learner@hanzisrs.com · password: learnhanzi",
                    tone = AccentCyan,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Input Type Switcher Pill (Email vs Phone Number)
                //
                // 48dp and `selectable`, where it was 38dp and two `clickable` Boxes. Below
                // the touch floor, and a screen reader heard two unrelated buttons with no
                // way to tell which identifier type was in use.
                Surface(
                    shape = CircleShape,
                    color = DarkSurfaceContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        SegmentedOption(
                            selected = inputMode == 0,
                            onClick = { inputMode = 0 },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("auth_mode_email")
                        ) {
                            Text(
                                text = "Email",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (inputMode == 0) AccentPrimaryInk else TextSubtle
                            )
                        }

                        SegmentedOption(
                            selected = inputMode == 1,
                            onClick = { inputMode = 1 },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("auth_mode_phone")
                        ) {
                            Text(
                                text = "Phone",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (inputMode == 1) AccentPrimaryInk else TextSubtle
                            )
                        }
                    }
                }

                // Register-only Display Name
                AnimatedVisibility(visible = isRegisterMode) {
                    Column {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = { Text("Display name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = AccentPrimary) },
                            singleLine = true,
                            // A nickname, not an account name: the old label promised an
                            // email-shaped field, and the keyboard consequently offered
                            // autocorrect and a key on a value that is stored as a display
                            // string and never used to sign in.
                            //
                            // `imeAction` belongs *here*, not as a top-level
                            // `OutlinedTextField` argument. Material 3 dropped that
                            // parameter in 1.3.0, so the only place the action key can be
                            // declared is the one the field actually reads.
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Next) }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("display_name_input"),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight,
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = OutlineBorder,
                                focusedLabelColor = AccentPrimary,
                                unfocusedLabelColor = TextMuted,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                // Email or Phone field
                OutlinedTextField(
                    value = emailOrPhone,
                    onValueChange = { emailOrPhone = it },
                    label = { Text(if (inputMode == 0) "Email address" else "Phone number") },
                    placeholder = { Text(if (inputMode == 0) "name@example.com" else "+1 (555) 019-2834", color = TextMuted) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (inputMode == 0) Icons.Default.Email else Icons.Default.Phone,
                            contentDescription = null,
                            tint = AccentPrimary
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (inputMode == 0) KeyboardType.Email else KeyboardType.Phone,
                        // No autocorrect on an address or a phone number: the keyboard's
                        // dictionary will confidently rewrite them, and the field's own
                        // validation is the only thing that should be deciding.
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next
                    ),
                    // Next, and it actually goes next. Without this the action key is
                    // decorative: it renders the right glyph and does nothing when pressed.
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identifier_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = OutlineBorder,
                        focusedLabelColor = AccentPrimary,
                        unfocusedLabelColor = TextMuted,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (8+ characters)") },
                    placeholder = { Text("••••••••", color = TextMuted) },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = AccentPrimary) },
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.testTag("password_visibility_toggle")
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = TextMuted
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    // Done, and it submits. The keyboard's action key used to do nothing at
                    // all, so signing in meant reaching for the button with the keyboard still
                    // covering it. This is the action a phone user expects from the key.
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canSubmit) submit()
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = OutlineBorder,
                        focusedLabelColor = AccentPrimary,
                        unfocusedLabelColor = TextMuted,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                // Auth Error message
                if (authError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = authError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                // The reason the button is disabled, stated. It used to grey out below 8
                // characters with nothing on screen saying why, which on a phone reads as a
                // broken button rather than a rule the label has already printed.
                val blockedReason = when {
                    authLoading -> null
                    emailOrPhone.isBlank() -> "Enter your ${if (inputMode == 0) "email" else "phone number"} to continue."
                    password.length < 8 ->
                        "Passwords need at least 8 characters — ${8 - password.length} to go."
                    else -> null
                }

                if (blockedReason != null) {
                    Text(
                        text = blockedReason,
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }

                // Primary Submit Button (Full pill button with lilac background and dark text)
                Button(
                    onClick = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("auth_submit_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary,
                        contentColor = AccentPrimaryInk
                    ),
                    shape = RoundedCornerShape(26.dp),
                    enabled = canSubmit
                ) {
                    if (authLoading) {
                        CircularProgressIndicator(
                            color = AccentPrimaryInk,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (isRegisterMode) "Create account" else "Sign in",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Toggle Login / Register
                TextButton(
                    onClick = {
                        isRegisterMode = !isRegisterMode
                        // A failed attempt from the other mode is not a failure of this one,
                        // and leaving the message up reads as if the learner had just got it
                        // wrong.
                        viewModel.clearAuthError()
                    }
                ) {
                    Text(
                        text = if (isRegisterMode) "Already have an account? Sign In" else "New here? Create account",
                        color = AccentPrimary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Divider line with "Or continue with"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(OutlineBorder)
                    )
                    Text(
                        text = "OR",
                        fontSize = 11.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(OutlineBorder)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Quick Demo Account / Guest Mode
                OutlinedButton(
                    onClick = {
                        viewModel.loginAsGuest(onSuccess = onAuthSuccess)
                    },
                    // The submit button above gates on `authLoading`; this one did not, so a
                    // rapid double-tap started two `loginAsGuest` calls at once. Each one derives
                    // a PBKDF2 hash of the guest secret - 210_000 iterations - and each one races
                    // the others to create the single guest row.
                    enabled = !authLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("guest_login_button"),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextLight
                    )
                ) {
                    Text(
                        text = "Continue as Guest Learner",
                        color = TextLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
