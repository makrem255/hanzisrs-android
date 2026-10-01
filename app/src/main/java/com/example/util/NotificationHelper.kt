package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {
    const val CHANNEL_ID = "hanzi_daily_srs_reminders"
    const val NOTIFICATION_ID = 8881

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Daily SRS Review Reminders"
            val descriptionText = "Notifications alerting you when Chinese words are due for spaced repetition review"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showDueWordsNotification(context: Context, dueCount: Int) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            // `FLAG_ACTIVITY_CLEAR_TASK` used to be here, with `NEW_TASK`.

            // Together they told the system to finish the existing task and start a fresh
            // one - which is exactly what `singleTop` on the activity is there to prevent. The
            // existing instance was destroyed, so `onNewIntent` still could not run, and a
            // learner who tapped the alert mid-sitting lost the review session, which lives in
            // the Activity-scoped view model, with no way back to it.
            //
            // `SINGLE_TOP` instead: deliver to the running activity if it is at the top of the
            // task, create it only if the app is not running. `NEW_TASK` is still required -
            // this starts an activity from a `PendingIntent`, which is not itself in a task.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to", "deck")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (dueCount > 0) "⏰ Daily Chinese SRS Review" else "🎉 All Caught Up!"
        val message = if (dueCount > 0) {
            // Pluralised. This was `"$dueCount words"`, so a learner with exactly one card due
            // — the most likely moment to be reminded at all — was told they had "1 words".
            // `ProgressModels.describe` exists to stop exactly this, and this string had been
            // written alongside it without using it.
            val noun = if (dueCount == 1) "word" else "words"
            "You have $dueCount $noun to review today! Maintain your learning streak."
        } else {
            "No words due right now! Great job staying on top of your daily reviews."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 13+ and `POST_NOTIFICATIONS` not granted. The catch itself is correct -
            // `notify` genuinely throws here rather than no-opping - but it was empty, so a
            // control that did nothing left nothing behind: not a log line, not a return value,
            // no way to tell afterwards whether the tap reached this method at all.
            //
            // Logged rather than returned. The caller is a Compose button, and threading a
            // "nothing happened" signal up through `MainViewModel` for one recoverable,
            // user-recoverable condition would be a wider change than the condition warrants -
            // the screens now ask for the permission before getting here at all.
            Log.w(
                "NotificationHelper",
                "POST_NOTIFICATIONS not granted; the due-review alert was not posted."
            )
        }
    }
}
