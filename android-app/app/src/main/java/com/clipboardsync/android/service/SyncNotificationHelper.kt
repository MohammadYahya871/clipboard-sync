package com.clipboardsync.android.service

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.clipboardsync.android.R
import com.clipboardsync.android.MainActivity

object SyncNotificationHelper {
    const val CHANNEL_ID = "clipboard_sync"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.service_channel_description)
        }
        manager.createNotificationChannel(channel)
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
            .addAction(0, context.getString(R.string.service_notification_action_pause), pauseIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }
}
