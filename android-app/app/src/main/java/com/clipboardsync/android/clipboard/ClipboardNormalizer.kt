package com.clipboardsync.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    suspend fun normalizeCurrentClipboard(logSnapshot: Boolean = false): NormalizedClipboard? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        if (logSnapshot) {
            logger.info("Clipboard snapshot: ${describeClip(clip)}")
        }
        return normalizeClip(clip)
    }

    suspend fun normalizeClip(clip: ClipData): NormalizedClipboard? = withContext(Dispatchers.IO) {
        val sourceDeviceId = localDeviceIdentityStore.deviceId
        val clipMimeTypes = buildList {
            val description = clip.description
            if (description != null) {
                for (index in 0 until description.mimeTypeCount) {
                    add(description.getMimeType(index))
                }
            }
        }
        var attemptedImageDecode = false
        var firstTextCandidate: NormalizedClipboard? = null

        for (itemIndex in 0 until clip.itemCount) {
            val item = clip.getItemAt(itemIndex)
            val rawText = item.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val candidateUris = extractCandidateUris(item, rawText)
            var itemAttemptedImageDecode = false

            for (uri in candidateUris) {
                if (shouldTryImageFirst(uri, clipMimeTypes)) {
                    attemptedImageDecode = true
                    itemAttemptedImageDecode = true
                    logger.info(
                        "Attempting to normalize clipboard image URI from item $itemIndex: $uri (${clipMimeTypes.joinToString()})"
                    )
                    normalizeImageUri(uri, sourceDeviceId)?.let { return@withContext it }
                }
            }
            if (itemAttemptedImageDecode) {
                logger.warn("Clipboard image candidates from item $itemIndex could not be decoded")
            }

            rawText?.let { text ->
                if (itemAttemptedImageDecode && looksLikeLocalContentUri(text)) {
                    logger.warn("Clipboard item $itemIndex contains a local URI string that could not be decoded as an image")
                } else if (firstTextCandidate == null) {
                    val normalizedText = text.replace("\r\n", "\n")
                    val hash = CryptoUtils.sha256Hex(normalizedText)
                    val type = if (Patterns.WEB_URL.matcher(normalizedText.trim()).matches()) ContentType.URL else ContentType.TEXT
                    firstTextCandidate = NormalizedClipboard(
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
            }

            candidateUris.firstOrNull()?.let { uri ->
                normalizeImageUri(uri, sourceDeviceId)?.let { return@withContext it }
            }
        }

        if (attemptedImageDecode) {
            logger.warn("Clipboard image URI could not be decoded and will fall back to text representations")
        }

        firstTextCandidate?.let { return@withContext it }

        logger.warn("Clipboard entry is unsupported or empty")
        null
    }

    private suspend fun normalizeImageUri(
        uri: Uri,
        sourceDeviceId: String
    ): NormalizedClipboard? {
        val cached = imageCacheStore.cacheClipboardImage(uri) ?: return null
        val (image, bytes) = cached
        return NormalizedClipboard(
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

    private fun shouldTryImageFirst(uri: Uri, clipMimeTypes: List<String>): Boolean {
        if (clipMimeTypes.any { it.startsWith("image/") }) {
            return true
        }

        val resolverType = runCatching {
            context.contentResolver.getType(uri)
        }.getOrNull()

        if (resolverType?.startsWith("image/") == true) {
            return true
        }

        return uri.scheme == "content" || uri.scheme == "file" || uri.scheme == "android.resource"
    }

    private fun extractCandidateUris(item: ClipData.Item, rawText: String?): List<Uri> {
        val uniqueUris = linkedMapOf<String, Uri>()

        fun add(uri: Uri?) {
            if (uri == null) return
            uniqueUris.putIfAbsent(uri.toString(), uri)
        }

        add(item.uri)
        add(item.intent?.data)
        add(extractStreamUri(item.intent))
        item.intent?.clipData?.let { nestedClip ->
            for (index in 0 until nestedClip.itemCount) {
                val nestedItem = nestedClip.getItemAt(index)
                add(nestedItem.uri)
                add(nestedItem.intent?.data)
                add(extractStreamUri(nestedItem.intent))
            }
        }
        add(parseLocalUri(rawText))

        return uniqueUris.values.toList()
    }

    private fun parseLocalUri(text: String?): Uri? {
        val candidate = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = Uri.parse(candidate)
        return uri.takeIf { looksLikeLocalUri(it) }
    }

    private fun looksLikeLocalContentUri(text: String): Boolean {
        return parseLocalUri(text) != null
    }

    private fun looksLikeLocalUri(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> true
            else -> false
        }
    }

    private fun extractStreamUri(intent: Intent?): Uri? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun describeClip(clip: ClipData): String {
        val mimeTypes = buildList {
            val description = clip.description
            if (description != null) {
                for (index in 0 until description.mimeTypeCount) {
                    add(description.getMimeType(index))
                }
            }
        }
        val itemSummaries = buildList {
            for (itemIndex in 0 until clip.itemCount) {
                val item = clip.getItemAt(itemIndex)
                val textSummary = item.text?.toString()?.replace("\n", "\\n")?.take(80)
                val intent = item.intent
                add(
                    "item[$itemIndex]{text=${textSummary ?: "-"},uri=${item.uri ?: "-"},intentData=${intent?.data ?: "-"},stream=${extractStreamUri(intent) ?: "-"}}"
                )
            }
        }
        return "items=${clip.itemCount}, mimeTypes=${if (mimeTypes.isEmpty()) "-" else mimeTypes.joinToString()}, ${itemSummaries.joinToString("; ")}"
    }
}
