package com.clipboardsync.android.protocol

import kotlinx.serialization.json.Json

object ProtocolJson {
    val codec = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

