package com.clipboardsync.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipboardsync.android.ClipboardSyncApplication

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = (context.applicationContext as ClipboardSyncApplication).container.syncRepository
        if (repository.uiState.value.syncEnabled &&
            repository.uiState.value.notificationEnabled &&
            NotificationPermissionHelper.canShowNotifications(context)
        ) {
            ForegroundSyncService.sync(context)
        }
    }
}
