package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns a lambda that posts the due-review notification, asking for permission first if it
 * does not have it.
 *
 * ## Why this exists
 *
 * Two screens offer a "show me the alert" control: the bell in the home screen's top bar, and
 * the "Preview alert" button in settings. Settings checked `POST_NOTIFICATIONS` and launched
 * the runtime request. The home screen did not - it called
 * `MainViewModel.sendDueReminderNotification()` directly.
 *
 * That made the home screen's bell a control that does nothing, silently, on every device on
 * Android 13 and above. `NotificationHelper` catches the resulting `SecurityException` and
 * discards it, and `POST_NOTIFICATIONS` is requested nowhere else in the app - so a fresh
 * install tapped the bell and saw no notification, no error and no prompt, with nothing on
 * screen to say the tap had been received at all.
 *
 * Two spellings of one rule was how they diverged. The rule lives here now.
 *
 * ## Why it asks rather than just checking
 *
 * `POST_NOTIFICATIONS` is a runtime permission from API 33. Checking it and posting anyway -
 * the home screen's behaviour - cannot work. There is no degradation available: the user has
 * to grant it, so the only useful outcome of an ungranted state is the request.
 *
 * ## Denial
 *
 * Denial is not silent. [onDenied] is called both when the request comes back refused and when
 * the user has permanently denied it, because in the second case `launch` produces no result
 * worth waiting for and the caller still has to say something. Android does not distinguish
 * "denied once" from "don't ask again", and the honest message is the same either way: point
 * at system settings.
 */
@Composable
fun rememberNotificationRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit
): () -> Unit {
    val context = LocalContext.current

    // The launcher outlives any single composition of the caller, so it must invoke the
    // current lambdas rather than the ones captured when it was first created.
    val currentGranted = rememberUpdatedState(onGranted)
    val currentDenied = rememberUpdatedState(onDenied)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) currentGranted.value() else currentDenied.value()
    }

    return remember(context, launcher) {
        {
            val needsPermission =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED

            if (needsPermission) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                currentGranted.value()
            }
        }
    }
}