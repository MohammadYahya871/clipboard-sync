package com.clipboardsync.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.protocol.ClipboardEvent
import com.clipboardsync.android.protocol.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClipboardApplyUseCase(
    private val context: Context,
    private val imageCacheStore: ImageCacheStore,
    private val logger: AppLogger
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    suspend fun applyRemoteClip(event: ClipboardEvent, imageBytes: ByteArray?): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            when (event.contentType) {
                ContentType.TEXT, ContentType.URL -> {
                    val text = event.textPayload ?: return@runCatching false
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Clipboard Sync", text))
                    true
                }

                ContentType.IMAGE -> {
                    val bytes = imageBytes ?: return@runCatching false
                    val transferId = event.image?.transferId ?: event.eventId
                    val cached = imageCacheStore.cacheIncomingBytes(bytes, transferId) ?: return@runCatching false
                    val clip = ClipData.newUri(context.contentResolver, "Clipboard Sync image", cached.uri)
                    clipboardManager.setPrimaryClip(clip)
                    true
                }

                ContentType.MIXED_UNSUPPORTED -> false
            }
        }.onFailure {
            logger.error("Failed to apply remote clipboard event ${event.eventId}", it)
        }.getOrDefault(false)
    }
}

