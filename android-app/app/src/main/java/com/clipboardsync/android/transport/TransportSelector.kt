package com.clipboardsync.android.transport

import com.clipboardsync.android.protocol.TransportKind

class TransportSelector {
    fun select(lanReady: Boolean, bleAvailable: Boolean): TransportKind {
        return when {
            lanReady -> TransportKind.LAN
            bleAvailable -> TransportKind.BLE_FALLBACK
            else -> TransportKind.NONE
        }
    }
}
