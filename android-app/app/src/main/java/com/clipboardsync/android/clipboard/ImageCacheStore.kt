package com.clipboardsync.android.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.storage.CryptoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

data class CachedImage(
    val file: File,
    val uri: Uri,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val checksumSha256: String
)

class ImageCacheStore(
    private val context: Context,
    private val logger: AppLogger
) {
    private val cacheDir = File(context.cacheDir, "clipboard-images").apply { mkdirs() }

    suspend fun cacheIncomingBytes(bytes: ByteArray, transferId: String): CachedImage? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$transferId.png")
        runCatching {
            file.outputStream().use { it.write(bytes) }
            buildCachedImage(file)
        }.onFailure {
            logger.error("Failed to cache incoming image", it)
        }.getOrNull()
    }

    suspend fun cacheClipboardImage(uri: Uri): Pair<CachedImage, ByteArray>? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = context.contentResolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return@runCatching null

            val targetFile = File(cacheDir, "${Instant.now().toEpochMilli()}-${CryptoUtils.uuidV7()}.png")
            val bytes = writeBitmapPng(bitmap, targetFile)
            buildCachedImage(targetFile)?.let { it to bytes }
        }.onFailure {
            logger.error("Failed to cache clipboard image URI $uri", it)
        }.getOrNull()
    }

    fun fileUri(file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun cleanup(maxAgeMillis: Long = 24L * 60L * 60L * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        cacheDir.listFiles().orEmpty()
            .filter { it.lastModified() < cutoff }
            .forEach {
                if (it.delete()) {
                    logger.info("Deleted stale cache image ${it.name}")
                }
            }
    }

    private fun buildCachedImage(file: File): CachedImage? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }
        return CachedImage(
            file = file,
            uri = fileUri(file),
            width = options.outWidth,
            height = options.outHeight,
            byteSize = file.length(),
            checksumSha256 = CryptoUtils.sha256Hex(file.readBytes())
        )
    }

    private fun writeBitmapPng(bitmap: Bitmap, file: File): ByteArray {
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return file.readBytes()
    }
}

