package com.clipboardsync.android.transport

import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.pairing.TrustedPeer
import com.clipboardsync.android.protocol.DiscoveryMessage
import com.clipboardsync.android.protocol.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class LanPeerDiscovery(
    private val logger: AppLogger
) {
    suspend fun discoverTrustedPeer(peer: TrustedPeer, timeoutMillis: Int = 1_500): TrustedPeer? = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = timeoutMillis

            val request = DiscoveryMessage(type = DiscoveryMessage.DISCOVER_TYPE)
            val requestBytes = ProtocolJson.codec.encodeToString(request).toByteArray(Charsets.UTF_8)
            val requestPacket = DatagramPacket(
                requestBytes,
                requestBytes.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            runCatching {
                socket.send(requestPacket)
                logger.info("Sent LAN discovery probe for ${peer.displayName}")
            }.onFailure {
                logger.warn("Failed to send LAN discovery probe: ${it.message}")
                return@withContext null
            }

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                val response = try {
                    socket.receive(responsePacket)
                    String(responsePacket.data, responsePacket.offset, responsePacket.length, Charsets.UTF_8)
                } catch (_: SocketTimeoutException) {
                    break
                }

                val message = runCatching {
                    ProtocolJson.codec.decodeFromString<DiscoveryMessage>(response)
                }.getOrNull() ?: continue

                val host = message.host?.takeIf { it.isNotBlank() } ?: responsePacket.address.hostAddress ?: continue
                val port = message.port ?: continue
                if (matchesTrustedPeer(message, peer)) {
                    logger.info("Discovered saved Windows peer ${peer.displayName} at $host:$port")
                    return@withContext peer.copy(host = host, port = port)
                }
            }
        }

        logger.warn("LAN discovery did not find saved peer ${peer.displayName}")
        null
    }

    private fun matchesTrustedPeer(message: DiscoveryMessage, peer: TrustedPeer): Boolean {
        if (message.type != DiscoveryMessage.RESPONSE_TYPE) return false
        val sameIdentity = message.deviceId == peer.deviceId || message.serviceName == peer.serviceName
        val sameCertificate = message.certificateSha256.equals(peer.certificateSha256, ignoreCase = true)
        return sameIdentity && sameCertificate
    }

    private companion object {
        private const val DISCOVERY_PORT = 43872
    }
}
