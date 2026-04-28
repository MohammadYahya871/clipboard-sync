package com.clipboardsync.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.clipboardsync.android.ClipboardSyncApplication

class ForegroundSyncService : Service() {
    private lateinit var repository: SyncRepository

    override fun onCreate() {
        super.onCreate()
        repository = (application as ClipboardSyncApplication).container.syncRepository
        SyncNotificationHelper.ensureChannel(this)
        startForeground(
            SyncNotificationHelper.NOTIFICATION_ID,
            SyncNotificationHelper.buildNotification(this)
        )
        repository.setServiceActive(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        repository.setServiceActive(true)
        when (intent?.action) {
            ACTION_SYNC_SMART -> repository.syncSmartNow("quick-settings")
            ACTION_PAUSE_PRIVACY -> repository.setPrivacyPaused(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        repository.setServiceActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SYNC_SMART = "com.clipboardsync.android.action.SYNC_SMART"
        const val ACTION_PAUSE_PRIVACY = "com.clipboardsync.android.action.PAUSE_PRIVACY"

        fun sync(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForegroundSyncService::class.java)
            )
        }

        fun syncNow(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ForegroundSyncService::class.java).setAction(ACTION_SYNC_SMART)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundSyncService::class.java))
        }
    }
}
