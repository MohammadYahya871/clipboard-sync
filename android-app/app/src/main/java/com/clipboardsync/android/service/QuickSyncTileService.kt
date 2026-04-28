package com.clipboardsync.android.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clipboardsync.android.ClipboardSyncApplication
import com.clipboardsync.android.NotificationSyncActivity

class QuickSyncTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        launchForegroundSync()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            subtitle = "Syncing"
            updateTile()
        }
    }

    private fun launchForegroundSync() {
        val intent = Intent(this, NotificationSyncActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                201,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = (application as ClipboardSyncApplication).container.syncRepository.uiState.value
        tile.label = "Clipboard Sync"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (!state.syncEnabled) {
                "Off"
            } else if (state.pairedDeviceLabel == "Not paired") {
                "Pair first"
            } else {
                "Screenshot or clipboard"
            }
        }
        tile.state = when {
            !state.syncEnabled -> Tile.STATE_INACTIVE
            state.pairedDeviceLabel == "Not paired" -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }
}
