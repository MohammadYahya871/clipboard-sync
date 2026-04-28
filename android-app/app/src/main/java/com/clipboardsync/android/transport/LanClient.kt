package com.clipboardsync.android.transport

import android.util.Base64
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.pairing.TrustedPeer
import com.clipboardsync.android.protocol.ClipboardEvent
import com.clipboardsync.android.protocol.ProtocolEnvelope
import com.clipboardsync.android.protocol.ProtocolJson
import com.clipboardsync.android.protocol.TransferChunk
import com.clipboardsync.android.protocol.TransferDescriptor
import com.clipboardsync.android.storage.CryptoUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

enum class LanConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    READY,
    FAILED
}

class LanClient(
    private val logger: AppLogger
) {
    private val _state = MutableStateFlow(LanConnectionState.DISCONNECTED)
    val state = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ProtocolEnvelope>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incoming = _incoming.asSharedFlow()

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    fun connect(peer: TrustedPeer, localDeviceId: String) {
        disconnect()
        _state.value = LanConnectionState.CONNECTING
        val trustManager = FingerprintTrustManager(peer.certificateSha256)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        val okHttp = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url("wss://${peer.host}:${peer.port}/ws")
            .build()
        client = okHttp
        webSocket = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = LanConnectionState.CONNECTED
                logger.info("LAN WebSocket opened to ${peer.host}:${peer.port}")
                sendEnvelope(
                    ProtocolEnvelope(
                        type = "hello",
                        deviceId = localDeviceId,
                        sessionId = CryptoUtils.uuidV7()
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    ProtocolJson.codec.decodeFromString<ProtocolEnvelope>(text)
                }.onSuccess {
                    if (it.type == "peer_status" && it.status == "ready") {
                        _state.value = LanConnectionState.READY
                    }
                    _incoming.tryEmit(it)
                }.onFailure {
                    logger.error("Failed to decode envelope", it)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = LanConnectionState.DISCONNECTED
                logger.warn("LAN socket closing: $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = LanConnectionState.FAILED
                logger.error("LAN socket failure", t)
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "disconnect")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
        _state.value = LanConnectionState.DISCONNECTED
    }

    fun sendEnvelope(envelope: ProtocolEnvelope): Boolean {
        val encoded = ProtocolJson.codec.encodeToString(envelope)
        return webSocket?.send(encoded) == true
    }

    suspend fun sendClipboardEvent(event: ClipboardEvent, imageBytes: ByteArray?): Boolean {
        if (!sendEnvelope(ProtocolEnvelope(type = "clipboard_offer", event = event))) {
            logger.warn("Failed to queue clipboard offer ${event.eventId}")
            return false
        }
        if (event.image != null && imageBytes != null) {
            val chunkSize = 16 * 1024
            val totalChunks = (imageBytes.size + chunkSize - 1) / chunkSize
            val transferId = event.image.transferId ?: event.eventId
            if (!sendEnvelope(
                ProtocolEnvelope(
                    type = "transfer_begin",
                    transfer = TransferDescriptor(
                        transferId = transferId,
                        eventId = event.eventId,
                        totalChunks = totalChunks,
                        totalBytes = imageBytes.size.toLong(),
                        checksumSha256 = event.image.checksumSha256
                    )
                )
            )) {
                logger.warn("Failed to queue transfer begin for ${event.eventId}")
                return false
            }
            for (index in 0 until totalChunks) {
                val start = index * chunkSize
                val end = minOf(imageBytes.size, start + chunkSize)
                val base64 = Base64.encodeToString(imageBytes.copyOfRange(start, end), Base64.NO_WRAP)
                if (!waitForQueueCapacity()) {
                    logger.warn("Timed out waiting for LAN socket send queue while sending ${event.eventId}")
                    return false
                }
                if (!sendEnvelope(
                    ProtocolEnvelope(
                        type = "transfer_chunk",
                        chunk = TransferChunk(
                            transferId = transferId,
                            chunkIndex = index,
                            base64Payload = base64
                        )
                    )
                )) {
                    logger.warn("Failed to queue transfer chunk $index/$totalChunks for ${event.eventId}")
                    return false
                }
            }
            if (!sendEnvelope(
                ProtocolEnvelope(
                    type = "transfer_complete",
                    transfer = TransferDescriptor(
                        transferId = transferId,
                        eventId = event.eventId,
                        totalChunks = totalChunks,
                        totalBytes = imageBytes.size.toLong(),
                        checksumSha256 = event.image.checksumSha256
                    )
                )
            )) {
                logger.warn("Failed to queue transfer complete for ${event.eventId}")
                return false
            }
        }
        return true
    }

    private suspend fun waitForQueueCapacity(): Boolean {
        repeat(200) {
            val queuedBytes = webSocket?.queueSize() ?: return false
            if (queuedBytes < MAX_SOCKET_QUEUE_BYTES) {
                return true
            }
            delay(25)
        }
        return false
    }

    private class FingerprintTrustManager(
        private val expectedSha256: String
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Missing certificate chain")
            val actual = CryptoUtils.sha256Hex(leaf.encoded)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                throw CertificateException("Certificate fingerprint mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        private const val MAX_SOCKET_QUEUE_BYTES = 8L * 1024L * 1024L
    }
}
