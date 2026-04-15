package com.clipboardsync.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Patterns
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.pairing.LocalDeviceIdentityStore
import com.clipboardsync.android.protocol.ClipboardEvent
import com.clipboardsync.android.protocol.ContentType
import com.clipboardsync.android.protocol.ImageMetadata
import com.clipboardsync.android.protocol.NormalizedClipboard
import com.clipboardsync.android.protocol.TransferState
import com.clipboardsync.android.storage.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClipboardNormalizer(
    private val context: Context,
    private val localDeviceIdentityStore: LocalDeviceIdentityStore,
    private val imageCacheStore: ImageCacheStore,
    private val logger: AppLogger
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    suspend fun normalizeCurrentClipboard(): NormalizedClipboard? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return normalizeClip(clip)
    }

    suspend fun normalizeClip(clip: ClipData): NormalizedClipboard? = withContext(Dispatchers.IO) {
        val item = clip.getItemAt(0)
        val sourceDeviceId = localDeviceIdentityStore.deviceId

        item.text?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
            val normalizedText = text.replace("\r\n", "\n")
            val hash = CryptoUtils.sha256Hex(normalizedText)
            val type = if (Patterns.WEB_URL.matcher(normalizedText.trim()).matches()) ContentType.URL else ContentType.TEXT
            return@withContext NormalizedClipboard(
                event = ClipboardEvent(
                    eventId = CryptoUtils.uuidV7(),
                    sourceDeviceId = sourceDeviceId,
                    contentType = type,
                    mimeType = "text/plain",
                    payloadSizeBytes = normalizedText.toByteArray(Charsets.UTF_8).size.toLong(),
                    contentHashSha256 = hash,
                    dedupeKey = "$sourceDeviceId:$hash",
                    transferState = TransferState.QUEUED,
                    textPayload = normalizedText
                ),
                previewText = normalizedText.take(120)
            )
        }

        val uri = item.uri
        if (uri != null && isImageUri(uri)) {
            val cached = imageCacheStore.cacheClipboardImage(uri)
            if (cached != null) {
                val (image, bytes) = cached
                return@withContext NormalizedClipboard(
                    event = ClipboardEvent(
                        eventId = CryptoUtils.uuidV7(),
                        sourceDeviceId = sourceDeviceId,
                        contentType = ContentType.IMAGE,
                        mimeType = "image/png",
                        payloadSizeBytes = image.byteSize,
                        contentHashSha256 = image.checksumSha256,
                        dedupeKey = "$sourceDeviceId:${image.checksumSha256}",
                        transferState = TransferState.QUEUED,
                        image = ImageMetadata(
                            width = image.width,
                            height = image.height,
                            byteSize = image.byteSize,
                            checksumSha256 = image.checksumSha256,
                            encoding = "png",
                            transferId = CryptoUtils.uuidV7()
                        )
                    ),
                    imageBytes = bytes,
                    previewText = "Image ${image.width}x${image.height}",
                    previewUri = image.uri.toString()
                )
            }
        }

        logger.warn("Clipboard entry is unsupported or empty")
        null
    }

    private fun isImageUri(uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri) ?: return false
        return type.startsWith("image/")
    }
}
