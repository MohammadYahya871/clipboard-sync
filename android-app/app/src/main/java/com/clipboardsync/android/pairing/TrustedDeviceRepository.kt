package com.clipboardsync.android.pairing

import android.content.Context
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.protocol.PairingPayload
import com.clipboardsync.android.protocol.ProtocolJson
import kotlinx.serialization.builtins.ListSerializer

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
        val peers = getTrustedPeers()
        val selectedId = prefs.getString(KEY_SELECTED_DEVICE_ID, null)
        return peers.firstOrNull { it.deviceId == selectedId } ?: peers.firstOrNull()
    }

    fun getTrustedPeers(): List<TrustedPeer> {
        val currentPeers = readPeerList()
        if (currentPeers.isNotEmpty()) {
            return currentPeers.map { it.toTrustedPeer() }
        }

        val legacy = readLegacyPeer()
        if (legacy != null) {
            savePeerList(listOf(legacy))
            prefs.edit().putString(KEY_SELECTED_DEVICE_ID, legacy.deviceId).apply()
            return listOf(legacy.toTrustedPeer())
        }

        return emptyList()
    }

    fun selectPeer(deviceId: String) {
        val peer = getTrustedPeers().firstOrNull { it.deviceId == deviceId } ?: return
        prefs.edit().putString(KEY_SELECTED_DEVICE_ID, deviceId).apply()
        logger.info("Selected saved peer ${peer.displayName}")
    }

    fun savePairingPayload(payload: PairingPayload) {
        val peers = readPeerList().filterNot { it.deviceId == payload.deviceId } + payload
        savePeerList(peers)
        prefs.edit()
            .putString(KEY_PEER, ProtocolJson.codec.encodeToString(payload))
            .putString(KEY_SELECTED_DEVICE_ID, payload.deviceId)
            .apply()
        logger.info("Saved trusted peer ${payload.displayName}")
    }

    fun updateEndpoint(peer: TrustedPeer) {
        val payload = PairingPayload(
            deviceId = peer.deviceId,
            displayName = peer.displayName,
            serviceName = peer.serviceName,
            host = peer.host,
            port = peer.port,
            pairingCode = peer.pairingCode,
            certificateSha256 = peer.certificateSha256
        )
        val peers = readPeerList().filterNot { it.deviceId == payload.deviceId } + payload
        savePeerList(peers)
        if (prefs.getString(KEY_SELECTED_DEVICE_ID, null) == payload.deviceId) {
            prefs.edit().putString(KEY_PEER, ProtocolJson.codec.encodeToString(payload)).apply()
        }
        logger.info("Updated saved peer endpoint for ${peer.displayName} to ${peer.host}:${peer.port}")
    }

    fun clear() {
        prefs.edit().remove(KEY_PEER).remove(KEY_PEERS).remove(KEY_SELECTED_DEVICE_ID).apply()
        logger.warn("Cleared trusted peers")
    }

    private fun readPeerList(): List<PairingPayload> {
        val raw = prefs.getString(KEY_PEERS, null) ?: return emptyList()
        return runCatching {
            ProtocolJson.codec.decodeFromString(ListSerializer(PairingPayload.serializer()), raw)
        }.onFailure {
            logger.error("Failed to decode trusted peer list", it)
        }.getOrDefault(emptyList())
    }

    private fun savePeerList(peers: List<PairingPayload>) {
        prefs.edit()
            .putString(KEY_PEERS, ProtocolJson.codec.encodeToString(ListSerializer(PairingPayload.serializer()), peers))
            .apply()
    }

    private fun readLegacyPeer(): PairingPayload? {
        val raw = prefs.getString(KEY_PEER, null) ?: return null
        return runCatching {
            ProtocolJson.codec.decodeFromString<PairingPayload>(raw)
        }.onFailure {
            logger.error("Failed to decode legacy trusted peer", it)
        }.getOrNull()
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
        private const val KEY_PEERS = "peers"
        private const val KEY_SELECTED_DEVICE_ID = "selected_device_id"
    }
}

