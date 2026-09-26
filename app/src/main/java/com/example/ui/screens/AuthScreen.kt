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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkBg
import com.example.ui.theme.DarkSurfaceCard
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.LilacPrimary
import com.example.ui.theme.LilacPrimaryDark
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

    var emailOrPhone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val authError by viewModel.authError.collectAsState()
    val authLoading by viewModel.authLoading.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
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
                // Top Emblem Logo (Lilac with Dark Indigo character)
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(LilacPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "学",
                        color = LilacPrimaryDark,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Normal
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "HanziFlow",
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

                Surface(
                    color = DarkSurfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "This version stores learning profiles on this device. Demo: learner@hanzisrs.com · password: learnhanzi",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Input Type Switcher Pill (Email vs Phone Number)
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
                            .padding(4.dp)
                    ) {
                        // Email Tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(CircleShape)
                                .background(if (inputMode == 0) LilacPrimary else Color.Transparent)
                                .clickable { inputMode = 0 },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Email",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (inputMode == 0) LilacPrimaryDark else TextSubtle
                            )
                        }

                        // Phone Tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(CircleShape)
                                .background(if (inputMode == 1) LilacPrimary else Color.Transparent)
                                .clickable { inputMode = 1 },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Phone",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (inputMode == 1) LilacPrimaryDark else TextSubtle
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
                            label = { Text("Display Name / Nickname") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = LilacPrimary) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("display_name_input"),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight,
                                focusedBorderColor = LilacPrimary,
                                unfocusedBorderColor = OutlineBorder,
                                focusedLabelColor = LilacPrimary,
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
                            tint = LilacPrimary
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (inputMode == 0) KeyboardType.Email else KeyboardType.Phone
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("identifier_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = LilacPrimary,
                        unfocusedBorderColor = OutlineBorder,
                        focusedLabelColor = LilacPrimary,
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
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = LilacPrimary) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = TextMuted
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = LilacPrimary,
                        unfocusedBorderColor = OutlineBorder,
                        focusedLabelColor = LilacPrimary,
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

                // Primary Submit Button (Full pill button with lilac background and dark text)
                Button(
                    onClick = {
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
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("auth_submit_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LilacPrimary,
                        contentColor = LilacPrimaryDark
                    ),
                    shape = RoundedCornerShape(26.dp),
                    enabled = !authLoading && emailOrPhone.isNotBlank() && password.length >= 8
                ) {
                    if (authLoading) {
                        CircularProgressIndicator(
                            color = LilacPrimaryDark,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (isRegisterMode) "Create Account" else "Sign In",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Toggle Login / Register
                TextButton(
                    onClick = {
                        isRegisterMode = !isRegisterMode
                    }
                ) {
                    Text(
                        text = if (isRegisterMode) "Already have an account? Sign In" else "New here? Create account",
                        color = LilacPrimary,
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
