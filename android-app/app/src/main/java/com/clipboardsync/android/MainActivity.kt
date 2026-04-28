package com.clipboardsync.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.clipboardsync.android.clipboard.ClipboardObserver
import com.clipboardsync.android.service.ForegroundSyncService
import com.clipboardsync.android.service.NotificationPermissionHelper
import com.clipboardsync.android.ui.ClipboardSyncApp
import com.clipboardsync.android.ui.ClipboardSyncViewModel
import com.clipboardsync.android.ui.ClipboardSyncViewModelFactory
import com.clipboardsync.android.ui.theme.ClipboardSyncTheme
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ClipboardSyncViewModel> {
        ClipboardSyncViewModelFactory((application as ClipboardSyncApplication).container.syncRepository)
    }
    private lateinit var clipboardObserver: ClipboardObserver
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val repository = (application as ClipboardSyncApplication).container.syncRepository
        if (granted) {
            if (viewModel.state.value.syncEnabled && viewModel.state.value.notificationEnabled) {
                ForegroundSyncService.sync(this)
            }
        } else {
            (application as ClipboardSyncApplication).container.logger.warn(
                "Notification permission denied; foreground notification may stay hidden"
            )
            repository.setNotificationEnabled(false)
        }
    }
    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            (application as ClipboardSyncApplication).container.logger.warn(
                "Image/media permission denied; automatic screenshot sync will not be able to read screenshots"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clipboardObserver = ClipboardObserver(this) {
            viewModel.onClipboardChanged()
        }
        enableEdgeToEdge()
        setContent {
            ClipboardSyncTheme {
                ClipboardSyncApp(
                    viewModel = viewModel,
                    onNotificationEnabledToggle = { enabled ->
                        viewModel.onNotificationEnabledChanged(enabled)
                        if (enabled) {
                            maybeRequestNotificationPermission()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onUiForegroundChanged(true)
        clipboardObserver.start()
        maybeRequestNotificationPermission()
        maybeRequestMediaPermission()
    }

    override fun onPause() {
        clipboardObserver.stop()
        viewModel.onUiForegroundChanged(false)
        super.onPause()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (!viewModel.state.value.notificationEnabled) {
            return
        }
        if (NotificationPermissionHelper.canShowNotifications(this)) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun maybeRequestMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        mediaPermissionLauncher.launch(permission)
    }
}
