package com.clipboardsync.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Brief transparent activity used to obtain clipboard focus on HyperOS/Android
 * when the app is otherwise backgrounded.
 */
class NotificationSyncActivity : ComponentActivity() {
    private var startedSync = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (startedSync) {
            return
        }

        startedSync = true
        val repository = (application as ClipboardSyncApplication).container.syncRepository
        lifecycleScope.launch {
            // HyperOS often needs several hundred ms after resume before clipboard is readable.
            delay(400)
            repository.withClipboardAccessSession {
                when (intent?.action) {
                    ACTION_SYNC_SMART ->
                        repository.syncSmartNowAwait("foreground-sync-activity")
                    else ->
                        repository.syncCurrentClipboardNowAwait("clipboard-only-activity")
                }
                delay(500)
            }
            finish()
        }
    }

    companion object {
        const val ACTION_SYNC_CLIPBOARD_ONLY = "com.clipboardsync.android.action.SYNC_CLIPBOARD_ONLY"
        const val ACTION_SYNC_SMART = "com.clipboardsync.android.action.SYNC_SMART"
    }
}
