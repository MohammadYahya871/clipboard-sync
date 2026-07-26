package com.clipboardsync.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipboardsync.android.service.SyncMode
import com.clipboardsync.android.service.SyncRepository
import com.clipboardsync.android.service.SyncUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ClipboardSyncViewModel(
    private val repository: SyncRepository
) : ViewModel() {
    val state: StateFlow<SyncUiState> = repository.uiState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = repository.uiState.value
    )

    fun onUiForegroundChanged(active: Boolean) = repository.onUiForegroundChanged(active)

    fun onClipboardChanged() = repository.onClipboardChanged()

    fun onSyncEnabledChanged(enabled: Boolean) = repository.setSyncEnabled(enabled)

    fun onNotificationEnabledChanged(enabled: Boolean) = repository.setNotificationEnabled(enabled)

    fun onAutoScreenshotSyncChanged(enabled: Boolean) = repository.setAutoScreenshotSyncEnabled(enabled)

    fun onPrivacyPausedChanged(paused: Boolean) = repository.setPrivacyPaused(paused)

    fun onSyncModeChanged(mode: SyncMode) = repository.setSyncMode(mode)

    fun onAllowTextSyncChanged(enabled: Boolean) = repository.setAllowTextSync(enabled)

    fun onAllowUrlSyncChanged(enabled: Boolean) = repository.setAllowUrlSync(enabled)

    fun onAllowImageSyncChanged(enabled: Boolean) = repository.setAllowImageSync(enabled)

    fun onMaxImageSizeChanged(value: Int) = repository.setMaxImageSizeMb(value)

    fun onPair(payload: String) = repository.pair(payload)

    fun onManualPayloadChanged(payload: String) = repository.updateManualPairingPayload(payload)

    fun onFindNearbyHosts() = repository.findNearbyHosts()

    fun onPairNearbyHost(encodedPayload: String) = repository.pairNearbyHost(encodedPayload)

    fun onReconnect() = repository.reconnect()

    fun onScanSavedDevices() = repository.scanSavedDevices()

    fun onSelectSavedDevice(deviceId: String) = repository.selectSavedDevice(deviceId)

    fun onSyncSmart() = repository.syncCurrentClipboardNow()

    fun onResendRecent(eventId: String) = repository.resendRecent(eventId)

    fun onCopyRecentToClipboard(eventId: String) = repository.copyRecentToClipboard(eventId)

    fun onApplyDeferredIncoming(eventId: String) = repository.applyDeferredIncoming(eventId)

    fun onClearLogs() = repository.clearLogs()

    fun onCopyDebugReport() = repository.copyDebugReport()
}

class ClipboardSyncViewModelFactory(
    private val repository: SyncRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ClipboardSyncViewModel(repository) as T
    }
}
