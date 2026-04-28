package com.clipboardsync.android.clipboard

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.clipboardsync.android.diagnostics.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScreenshotCandidate(
    val uri: Uri,
    val id: Long,
    val dateAddedSeconds: Long,
    val relativePath: String?,
    val displayName: String?
)

class ScreenshotRepository(
    private val context: Context,
    private val logger: AppLogger
) {
    suspend fun latestScreenshot(maxAgeMillis: Long = 10 * 60 * 1000L): ScreenshotCandidate? = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val minDateAdded = (System.currentTimeMillis() - maxAgeMillis) / 1000L
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val args = arrayOf(minDateAdded.toString())

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateAdded = cursor.getLong(dateColumn)
                    val relativePath = cursor.getString(pathColumn)
                    val displayName = cursor.getString(nameColumn)
                    if (looksLikeScreenshot(relativePath, displayName)) {
                        return@withContext ScreenshotCandidate(
                            uri = Uri.withAppendedPath(collection, id.toString()),
                            id = id,
                            dateAddedSeconds = dateAdded,
                            relativePath = relativePath,
                            displayName = displayName
                        )
                    }
                }
            }
            null
        }.onFailure {
            logger.error("Failed to query latest screenshot", it)
        }.getOrNull()
    }

    fun observe(scope: CoroutineScope, onScreenshotChanged: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var pendingJob: Job? = null

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                pendingJob?.cancel()
                pendingJob = scope.launch {
                    delay(600)
                    onScreenshotChanged()
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        logger.info("Screenshot observer registered")
        return observer
    }

    fun stopObserving(observer: ContentObserver?) {
        observer ?: return
        runCatching {
            context.contentResolver.unregisterContentObserver(observer)
        }.onSuccess {
            logger.info("Screenshot observer unregistered")
        }
    }

    private fun looksLikeScreenshot(relativePath: String?, displayName: String?): Boolean {
        val combined = "${relativePath.orEmpty()}/${displayName.orEmpty()}".lowercase()
        return "screenshot" in combined || "screen_shot" in combined || "screenshots" in combined
    }
}
