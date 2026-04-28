package com.clipboardsync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationSyncActivity : ComponentActivity() {
    private var startedSync = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (startedSync) {
            return
        }

        startedSync = true
        val repository = (application as ClipboardSyncApplication).container.syncRepository
        repository.syncSmartNow("foreground-sync-activity")

        lifecycleScope.launch {
            delay(450)
            finish()
        }
    }
}
