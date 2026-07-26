package com.clipboardsync.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clipboardsync.android.MainActivity
import com.clipboardsync.android.NotificationSyncActivity
import com.clipboardsync.android.R

object SyncNotificationHelper {
    const val CHANNEL_ID = "clipboard_sync"
    const val WAKE_CHANNEL_ID = "clipboard_sync_wake"
    const val NOTIFICATION_ID = 1001
    const val WAKE_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.service_channel_description)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                WAKE_CHANNEL_ID,
                context.getString(R.string.service_wake_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.service_wake_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    fun buildNotification(context: Context): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val syncIntent = clipboardOnlyPendingIntent(context, requestCode = 102, smart = false)
        val pauseIntent = PendingIntent.getService(
            context,
            101,
            Intent(context, ForegroundSyncService::class.java).setAction(ForegroundSyncService.ACTION_PAUSE_PRIVACY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.service_notification_title))
            .setContentText(context.getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent)
            .addAction(0, context.getString(R.string.service_notification_action_sync), syncIntent)
            .addAction(0, context.getString(R.string.service_notification_action_pause), pauseIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    /**
     * Bring [NotificationSyncActivity] to the foreground from a background clipboard event.
     * Uses PendingIntent.send + a short-lived full-screen notification because HyperOS
     * blocks plain startActivity from a foreground service.
     */
    fun launchClipboardSyncActivity(context: Context): Boolean {
        ensureChannel(context)
        val pending = clipboardOnlyPendingIntent(context, requestCode = 110, smart = false)

        var sent = runCatching {
            pending.send()
            true
        }.getOrDefault(false)

        runCatching {
            context.startActivity(
                Intent(context, NotificationSyncActivity::class.java).apply {
                    action = NotificationSyncActivity.ACTION_SYNC_CLIPBOARD_ONLY
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                }
            )
            sent = true
        }

        // Full-screen wake is the reliable path when BAL blocks both of the above.
        val wakeNotification = NotificationCompat.Builder(context, WAKE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.service_wake_title))
            .setContentText(context.getString(R.string.service_wake_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(true)
            .setTimeoutAfter(2_500L)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setSilent(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(WAKE_NOTIFICATION_ID, wakeNotification)
            sent = true
        }

        return sent
    }

    private fun clipboardOnlyPendingIntent(
        context: Context,
        requestCode: Int,
        smart: Boolean
    ): PendingIntent {
        val action = if (smart) {
            NotificationSyncActivity.ACTION_SYNC_SMART
        } else {
            NotificationSyncActivity.ACTION_SYNC_CLIPBOARD_ONLY
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, NotificationSyncActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
