package com.clipboardsync.android.pairing

import android.util.Base64
import com.clipboardsync.android.protocol.PairingPayload
import com.clipboardsync.android.protocol.ProtocolJson

object PairingCodeCodec {
    fun encode(payload: PairingPayload): String {
        val json = ProtocolJson.codec.encodeToString(payload)
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun decode(encoded: String): PairingPayload {
        val json = String(Base64.decode(encoded.trim(), Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
        return ProtocolJson.codec.decodeFromString(json)
    }
}
