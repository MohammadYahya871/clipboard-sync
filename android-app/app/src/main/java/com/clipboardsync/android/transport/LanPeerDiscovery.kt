package com.clipboardsync.android.transport

import android.content.Context
import android.net.wifi.WifiManager
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.pairing.TrustedPeer
import com.clipboardsync.android.protocol.DiscoveryMessage
import com.clipboardsync.android.protocol.PairingPayload
import com.clipboardsync.android.protocol.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class LanPeerDiscovery(
    private val appContext: Context,
    private val logger: AppLogger
) {
    suspend fun discoverTrustedPeer(peer: TrustedPeer, timeoutMillis: Int = 1_500): TrustedPeer? =
        discoverResponses(timeoutMillis)
            .firstNotNullOfOrNull { (message, host) ->
                val port = message.port ?: return@firstNotNullOfOrNull null
                if (matchesTrustedPeer(message, peer)) {
                    logger.info("Discovered saved peer ${peer.displayName} at $host:$port")
                    peer.copy(host = host, port = port)
                } else {
                    null
                }
            }
            .also {
                if (it == null) {
                    logger.warn("LAN discovery did not find saved peer ${peer.displayName}")
                }
            }

    suspend fun discoverTrustedPeers(peers: List<TrustedPeer>, timeoutMillis: Int = 1_500): List<TrustedPeer> {
        if (peers.isEmpty()) return emptyList()
        val found = linkedMapOf<String, TrustedPeer>()

        for ((message, host) in discoverResponses(timeoutMillis)) {
            val port = message.port ?: continue
            val peer = peers.firstOrNull { matchesTrustedPeer(message, it) } ?: continue
            found[peer.deviceId] = peer.copy(host = host, port = port)
            logger.info("Discovered saved peer ${peer.displayName} at $host:$port")
        }
        return found.values.toList()
    }

    suspend fun discoverPairableHosts(timeoutMillis: Int = 2_500): List<PairingPayload> {
        val found = linkedMapOf<String, PairingPayload>()
        var rawCount = 0
        for ((message, host) in discoverResponses(timeoutMillis)) {
            rawCount += 1
            if (message.type != DiscoveryMessage.RESPONSE_TYPE) continue
            if (message.pairingAllowed != true) {
                logger.info("Ignoring nearby host ${message.displayName ?: host}: pairing not allowed")
                continue
            }
            val pairingCode = message.pairingCode?.takeIf { it.isNotBlank() } ?: continue
            val deviceId = message.deviceId?.takeIf { it.isNotBlank() } ?: continue
            val displayName = message.displayName?.takeIf { it.isNotBlank() } ?: deviceId
            val serviceName = message.serviceName?.takeIf { it.isNotBlank() } ?: "$displayName-clipboard-sync"
            val port = message.port ?: continue
            val certificateSha256 = message.certificateSha256?.takeIf { it.isNotBlank() } ?: continue
            found[deviceId] = PairingPayload(
                deviceId = deviceId,
                displayName = displayName,
                serviceName = serviceName,
                host = host,
                port = port,
                pairingCode = pairingCode,
                certificateSha256 = certificateSha256
            )
            logger.info("Discovered pairable host $displayName at $host:$port")
        }
        logger.info("Nearby discovery raw responses=$rawCount pairable=${found.size}")
        return found.values.toList()
    }

    private suspend fun discoverResponses(timeoutMillis: Int): List<Pair<DiscoveryMessage, String>> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<Pair<DiscoveryMessage, String>>()
            val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifi?.createMulticastLock("clipboard-sync-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.reuseAddress = true
                    socket.soTimeout = timeoutMillis.coerceAtLeast(200)

                    val request = DiscoveryMessage(type = DiscoveryMessage.DISCOVER_TYPE)
                    val requestBytes = ProtocolJson.codec.encodeToString(request).toByteArray(Charsets.UTF_8)
                    val targets = discoveryTargets()
                    for (target in targets) {
                        runCatching {
                            socket.send(
                                DatagramPacket(
                                    requestBytes,
                                    requestBytes.size,
                                    target,
                                    DISCOVERY_PORT
                                )
                            )
                        }.onFailure {
                            logger.warn("Failed discovery send to $target: ${it.message}")
                        }
                    }
                    logger.info("Sent LAN discovery probe to ${targets.size} target(s)")

                    val deadline = System.currentTimeMillis() + timeoutMillis
                    val buffer = ByteArray(8192)
                    while (System.currentTimeMillis() < deadline) {
                        val remaining = (deadline - System.currentTimeMillis()).toInt()
                        if (remaining <= 0) break
                        socket.soTimeout = remaining.coerceAtLeast(50)
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
                        if (message.type != DiscoveryMessage.RESPONSE_TYPE) continue
                        val host = message.host?.takeIf { it.isNotBlank() }
                            ?: responsePacket.address.hostAddress
                            ?: continue
                        results += message to host
                    }
                }
            } finally {
                runCatching { multicastLock?.release() }
            }
            results
        }

    private fun discoveryTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return targets.toList()
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    val unicast = address.address
                    if (unicast !is Inet4Address || unicast.isLoopbackAddress) continue
                    val prefix = address.networkPrefixLength.toInt()
                    if (prefix !in 1..30) continue
                    val broadcast = address.broadcast
                    if (broadcast != null) {
                        targets += broadcast
                    } else {
                        runCatching {
                            targets += computeBroadcast(unicast, prefix)
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            logger.warn("Failed enumerating broadcast targets: ${exception.message}")
        }
        return targets.toList()
    }

    private fun computeBroadcast(address: Inet4Address, prefixLength: Int): InetAddress {
        val ip = address.address
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        val value = ((ip[0].toInt() and 0xff) shl 24) or
            ((ip[1].toInt() and 0xff) shl 16) or
            ((ip[2].toInt() and 0xff) shl 8) or
            (ip[3].toInt() and 0xff)
        val broadcast = value or mask.inv()
        val bytes = byteArrayOf(
            ((broadcast ushr 24) and 0xff).toByte(),
            ((broadcast ushr 16) and 0xff).toByte(),
            ((broadcast ushr 8) and 0xff).toByte(),
            (broadcast and 0xff).toByte()
        )
        return InetAddress.getByAddress(bytes)
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
