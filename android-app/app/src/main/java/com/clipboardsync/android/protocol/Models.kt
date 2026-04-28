package com.clipboardsync.android.protocol

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
enum class ContentType {
    TEXT,
    URL,
    IMAGE,
    MIXED_UNSUPPORTED
}

@Serializable
enum class TransferState {
    QUEUED,
    SENDING,
    AWAITING_ACK,
    ACKED,
    FAILED,
    DEFERRED
}

@Serializable
enum class TransportKind {
    NONE,
    LAN,
    BLE_FALLBACK
}

@Serializable
data class ImageMetadata(
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val checksumSha256: String,
    val encoding: String,
    val transferId: String? = null
)

@Serializable
data class ClipboardEvent(
    val eventId: String,
    val sourceDeviceId: String,
    val originatedAtUtc: String = Instant.now().toString(),
    val contentType: ContentType,
    val mimeType: String,
    val payloadSizeBytes: Long,
    val contentHashSha256: String,
    val dedupeKey: String,
    val transferState: TransferState,
    val textPayload: String? = null,
    val image: ImageMetadata? = null
)

@Serializable
data class TransferDescriptor(
    val transferId: String,
    val eventId: String,
    val totalChunks: Int,
    val totalBytes: Long,
    val checksumSha256: String
)

@Serializable
data class TransferChunk(
    val transferId: String,
    val chunkIndex: Int,
    val base64Payload: String
)

@Serializable
data class ProtocolEnvelope(
    val type: String,
    val timestampUtc: String = Instant.now().toString(),
    val sessionId: String? = null,
    val deviceId: String? = null,
    val challenge: String? = null,
    val response: String? = null,
    val status: String? = null,
    val reason: String? = null,
    val event: ClipboardEvent? = null,
    val transfer: TransferDescriptor? = null,
    val chunk: TransferChunk? = null
)

@Serializable
data class PairingPayload(
    val deviceId: String,
    val displayName: String,
    val serviceName: String,
    val host: String,
    val port: Int,
    val pairingCode: String,
    val certificateSha256: String
)

@Serializable
data class DiscoveryMessage(
    val type: String,
    val deviceId: String? = null,
    val displayName: String? = null,
    val serviceName: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val certificateSha256: String? = null
) {
    companion object {
        const val DISCOVER_TYPE = "clipboard_sync_discover"
        const val RESPONSE_TYPE = "clipboard_sync_device"
    }
}

data class NormalizedClipboard(
    val event: ClipboardEvent,
    val imageBytes: ByteArray? = null,
    val previewText: String,
    val previewUri: String? = null,
    val fromRemote: Boolean = false
)

data class ResolvedPeerEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int
)

