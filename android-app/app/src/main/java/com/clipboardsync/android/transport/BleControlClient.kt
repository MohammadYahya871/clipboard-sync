package com.clipboardsync.android.transport

import com.clipboardsync.android.diagnostics.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BleAvailability {
    UNAVAILABLE,
    IDLE,
    READY
}

class BleControlClient(
    private val logger: AppLogger
) {
    private val _availability = MutableStateFlow(BleAvailability.UNAVAILABLE)
    val availability = _availability.asStateFlow()

    fun refreshAvailability() {
        logger.info("BLE control client is scaffolded but not yet active in the MVP build")
        _availability.value = BleAvailability.UNAVAILABLE
    }
}

