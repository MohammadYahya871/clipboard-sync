package com.clipboardsync.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

    fun onPair(payload: String) = repository.pair(payload)

    fun onManualPayloadChanged(payload: String) = repository.updateManualPairingPayload(payload)

    fun onReconnect() = repository.reconnect()

    fun onSyncCurrentClipboard() = repository.syncCurrentClipboardNow()

    fun onClearLogs() = repository.clearLogs()
}

class ClipboardSyncViewModelFactory(
    private val repository: SyncRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ClipboardSyncViewModel(repository) as T
    }
}
