package com.obscura.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.obscura.kit.ProcessedCounts

/**
 * Posts a single generic local notification from drained push counts.
 *
 * The kit NEVER posts OS notifications itself — it only returns [ProcessedCounts].
 * This is the one place the app turns those counts into user-visible text. Both the
 * real FCM path ([ObscuraFcmService]) and the debug test-push path call this, so the
 * notification UX is identical regardless of how the wake was triggered.
 */
object PushNotifier {
    private const val TAG = "ObscuraApp"
    private const val CHANNEL_ID = "obscura_messages"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "New pix and messages" }
            )
        }
    }

    /** Build generic text from counts and post. No-op if nothing actionable was drained. */
    fun notify(context: Context, counts: ProcessedCounts) {
        val total = counts.pixCount + counts.messageCount
        if (total == 0) {
            Log.d(TAG, "PushNotifier: nothing to notify (pix=0 msg=0)")
            return
        }
        ensureChannel(context)

        val text = when {
            counts.pixCount > 0 && counts.messageCount > 0 ->
                "$total new ${plural(total, "item")}"
            counts.pixCount > 0 -> "${counts.pixCount} new ${plural(counts.pixCount, "pix")}"
            else -> "${counts.messageCount} new ${plural(counts.messageCount, "message")}"
        }

        val tapIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Obscura")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "PushNotifier: posted \"$text\"")
    }

    // "pix" is invariant in plural; only pluralize the others
    private fun plural(n: Int, word: String): String =
        if (word == "pix" || n == 1) word else "${word}s"
}
