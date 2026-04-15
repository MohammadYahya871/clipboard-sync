package com.clipboardsync.android.pairing

import android.content.Context
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.protocol.PairingPayload
import com.clipboardsync.android.protocol.ProtocolJson

data class TrustedPeer(
    val deviceId: String,
    val displayName: String,
    val serviceName: String,
    val host: String,
    val port: Int,
    val pairingCode: String,
    val certificateSha256: String
)

class TrustedDeviceRepository(
    context: Context,
    private val logger: AppLogger
) {
    private val prefs = context.getSharedPreferences("trusted_peer", Context.MODE_PRIVATE)

    fun getTrustedPeer(): TrustedPeer? {
        val raw = prefs.getString(KEY_PEER, null) ?: return null
        return runCatching {
            val payload = ProtocolJson.codec.decodeFromString<PairingPayload>(raw)
            payload.toTrustedPeer()
        }.onFailure {
            logger.error("Failed to decode trusted peer", it)
        }.getOrNull()
    }

    fun savePairingPayload(payload: PairingPayload) {
        prefs.edit().putString(KEY_PEER, ProtocolJson.codec.encodeToString(payload)).apply()
        logger.info("Saved trusted peer ${payload.displayName}")
    }

    fun clear() {
        prefs.edit().remove(KEY_PEER).apply()
        logger.warn("Cleared trusted peer")
    }

    private fun PairingPayload.toTrustedPeer(): TrustedPeer = TrustedPeer(
        deviceId = deviceId,
        displayName = displayName,
        serviceName = serviceName,
        host = host,
        port = port,
        pairingCode = pairingCode,
        certificateSha256 = certificateSha256
    )

    companion object {
        private const val KEY_PEER = "peer"
    }
}

