package com.clipboardsync.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

class ShareToClipboardSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        finish()
    }

    private fun handleShareIntent(intent: Intent) {
        val clip = intent.toShareClip() ?: run {
            Toast.makeText(this, "Clipboard Sync could not read this shared item", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)

        val repository = (application as ClipboardSyncApplication).container.syncRepository
        repository.syncSharedClipNow(clip)

        Toast.makeText(this, "Shared to Clipboard Sync", Toast.LENGTH_SHORT).show()
    }

    private fun Intent.toShareClip(): ClipData? {
        val mimeType = type ?: "text/plain"
        val existingClip = clipData
        if (existingClip != null && existingClip.itemCount > 0) {
            return existingClip
        }

        return when (action) {
            Intent.ACTION_SEND -> {
                val text = getStringExtra(Intent.EXTRA_TEXT)
                    ?: getStringExtra(Intent.EXTRA_SUBJECT)
                if (!text.isNullOrBlank()) {
                    ClipData.newPlainText("Clipboard Sync shared text", text)
                } else {
                    getStreamUri()?.let { uri ->
                        ClipData.newUri(contentResolver, "Clipboard Sync shared item", uri)
                            .withMimeType(mimeType)
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getStreamUris()
                if (uris.isEmpty()) {
                    null
                } else {
                    val clip = ClipData.newUri(contentResolver, "Clipboard Sync shared item", uris.first())
                        .withMimeType(mimeType)
                    uris.drop(1).forEach { uri ->
                        clip.addItem(contentResolver, ClipData.Item(uri))
                    }
                    clip
                }
            }

            else -> null
        }
    }

    private fun ClipData.withMimeType(mimeType: String): ClipData {
        return ClipData(description.label, arrayOf(mimeType), getItemAt(0)).also { copy ->
            for (index in 1 until itemCount) {
                copy.addItem(contentResolver, getItemAt(index))
            }
        }
    }

    private fun Intent.getStreamUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun Intent.getStreamUris(): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}
