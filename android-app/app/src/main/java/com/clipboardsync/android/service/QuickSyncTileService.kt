package com.clipboardsync.android.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clipboardsync.android.ClipboardSyncApplication

class QuickSyncTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        ForegroundSyncService.syncNow(this)
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            subtitle = "Syncing"
            updateTile()
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
