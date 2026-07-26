package com.clipboardsync.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.ContentPasteSearch
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.clipboardsync.android.protocol.ContentType
import com.clipboardsync.android.protocol.TransferState
import com.clipboardsync.android.protocol.TransportKind
import com.clipboardsync.android.service.RecentItemUiModel
import com.clipboardsync.android.service.SavedDeviceUiModel
import com.clipboardsync.android.protocol.NearbyHostUiModel
import com.clipboardsync.android.service.SyncMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncApp(
    viewModel: ClipboardSyncViewModel,
    onScanPairingQr: () -> Unit = {},
    onNotificationEnabledToggle: (Boolean) -> Unit = viewModel::onNotificationEnabledChanged
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun announce(message: String, action: () -> Unit) {
        action()
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Clipboard Sync", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                StatusCard(
                    pairedDevice = state.pairedDeviceLabel,
                    connectionLabel = state.connectionLabel,
                    transport = state.transportKind,
                    syncEnabled = state.syncEnabled,
                    notificationEnabled = state.notificationEnabled,
                    autoScreenshotSyncEnabled = state.autoScreenshotSyncEnabled,
                    privacyPaused = state.privacyPaused,
                    onSyncEnabledChanged = viewModel::onSyncEnabledChanged,
                    onNotificationEnabledChanged = onNotificationEnabledToggle,
                    onAutoScreenshotSyncChanged = viewModel::onAutoScreenshotSyncChanged,
                    onPrivacyPausedChanged = viewModel::onPrivacyPausedChanged,
                    onReconnect = { announce("Reconnecting…", viewModel::onReconnect) },
                    onSyncSmart = { announce("Sync request sent", viewModel::onSyncSmart) }
                )
            }
            item {
                SmartSyncCard(
                    mode = state.syncMode,
                    allowText = state.allowTextSync,
                    allowUrl = state.allowUrlSync,
                    allowImage = state.allowImageSync,
                    maxImageSizeMb = state.maxImageSizeMb,
                    queuedOutboundCount = state.queuedOutboundCount,
                    deferredIncomingCount = state.deferredIncomingCount,
                    onModeChanged = viewModel::onSyncModeChanged,
                    onAllowTextChanged = viewModel::onAllowTextSyncChanged,
                    onAllowUrlChanged = viewModel::onAllowUrlSyncChanged,
                    onAllowImageChanged = viewModel::onAllowImageSyncChanged,
                    onMaxImageSizeChanged = viewModel::onMaxImageSizeChanged
                )
            }
            item { GuidanceCard(state.guidance) }
            item {
                PairingCard(
                    payload = state.manualPairingPayload,
                    nearbyHosts = state.nearbyHosts,
                    nearbyScanInProgress = state.nearbyScanInProgress,
                    onPayloadChanged = viewModel::onManualPayloadChanged,
                    onScanPairingQr = onScanPairingQr,
                    onFindNearby = { announce("Scanning for nearby PCs…", viewModel::onFindNearbyHosts) },
                    onPairNearby = { encodedPayload -> announce("Pairing with selected PC") { viewModel.onPairNearbyHost(encodedPayload) } },
                    onPair = { announce("Pairing with pasted payload") { viewModel.onPair(state.manualPairingPayload) } }
                )
            }
            item {
                SavedDevicesCard(
                    devices = state.savedDevices,
                    onScan = { announce("Scanning saved devices…", viewModel::onScanSavedDevices) },
                    onSelect = { deviceId -> announce("Connecting to saved device…") { viewModel.onSelectSavedDevice(deviceId) } }
                )
            }
            item { LastItemCard(state.lastSyncedItem) }
            item { SectionTitle("Recent History") }
            items(state.recentItems, key = { it.eventId }) { item ->
                RecentItemCard(
                    item = item,
                    onResend = { announce("History item queued") { viewModel.onResendRecent(item.eventId) } },
                    onRestore = { announce("Copied selected item here") { viewModel.onCopyRecentToClipboard(item.eventId) } },
                    onApplyDeferred = { announce("Deferred item applied") { viewModel.onApplyDeferredIncoming(item.eventId) } }
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("Diagnostics")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { announce("Diagnostics cleared", viewModel::onClearLogs) }) { Text("Clear") }
                        TextButton(onClick = { announce("Debug report copied", viewModel::onCopyDebugReport) }) { Text("Copy report") }
                    }
                }
            }
            items(state.logs, key = { it.timestampUtc + it.message }) { log ->
                FlatCard(background = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("${log.level}  ${log.timestampUtc}", style = MaterialTheme.typography.labelMedium)
                    Text(log.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartSyncCard(
    mode: SyncMode,
    allowText: Boolean,
    allowUrl: Boolean,
    allowImage: Boolean,
    maxImageSizeMb: Int,
    queuedOutboundCount: Int,
    deferredIncomingCount: Int,
    onModeChanged: (SyncMode) -> Unit,
    onAllowTextChanged: (Boolean) -> Unit,
    onAllowUrlChanged: (Boolean) -> Unit,
    onAllowImageChanged: (Boolean) -> Unit,
    onMaxImageSizeChanged: (Int) -> Unit
) {
    FlatCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionTitle("Smart Sync")
                Text("Mode: ${mode.label}", style = MaterialTheme.typography.bodyMedium)
                Text(mode.description(), style = MaterialTheme.typography.bodySmall)
                Text("Queued: $queuedOutboundCount  Deferred: $deferredIncomingCount", style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SyncMode.entries, key = { it.name }) { option ->
                FilterChip(
                    selected = option == mode,
                    onClick = { onModeChanged(option) },
                    label = { Text(option.label) }
                )
            }
        }

        SettingRow("Text", "Allow plain text clipboard sends.", allowText, onAllowTextChanged)
        SettingRow("URLs", "Allow links and web addresses.", allowUrl, onAllowUrlChanged)
        SettingRow("Images", "Allow image transfers.", allowImage, onAllowImageChanged)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Image Limit", style = MaterialTheme.typography.labelLarge)
                Text("$maxImageSizeMb MB maximum", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onMaxImageSizeChanged(maxImageSizeMb - 5) }) { Text("-") }
                TextButton(onClick = { onMaxImageSizeChanged(maxImageSizeMb + 5) }) { Text("+") }
            }
        }
    }
}

@Composable
private fun StatusCard(
    pairedDevice: String,
    connectionLabel: String,
    transport: TransportKind,
    syncEnabled: Boolean,
    notificationEnabled: Boolean,
    autoScreenshotSyncEnabled: Boolean,
    privacyPaused: Boolean,
    onSyncEnabledChanged: (Boolean) -> Unit,
    onNotificationEnabledChanged: (Boolean) -> Unit,
    onAutoScreenshotSyncChanged: (Boolean) -> Unit,
    onPrivacyPausedChanged: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onSyncSmart: () -> Unit
) {
    FlatCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(connectionHealth(syncEnabled, pairedDevice, connectionLabel), style = MaterialTheme.typography.labelLarge)
                Text(pairedDevice, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Connection: $connectionLabel", style = MaterialTheme.typography.bodyMedium)
                Text("Transport: ${transport.label()}", style = MaterialTheme.typography.bodyMedium)
                Text(readinessSummary(syncEnabled, pairedDevice), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = syncEnabled, onCheckedChange = onSyncEnabledChanged)
        }

        SettingRow(
            title = "Keep Notification Active",
            description = "Required for phone→laptop auto-sync while the app is hidden. On HyperOS also enable: App info → Other permissions → Display pop-up windows while running in background.",
            checked = notificationEnabled,
            onCheckedChange = onNotificationEnabledChanged
        )
        SettingRow(
            title = "Auto-sync Screenshots",
            description = "Off by default (v2). Leave off unless you want gallery screenshots auto-pushed. Sync now never sends screenshots.",
            checked = autoScreenshotSyncEnabled,
            onCheckedChange = onAutoScreenshotSyncChanged
        )
        SettingRow(
            title = "Privacy Pause",
            description = "Temporarily stops outbound clipboard and screenshot sync.",
            checked = privacyPaused,
            onCheckedChange = onPrivacyPausedChanged
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = onReconnect, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Sync, contentDescription = null)
                Text("Reconnect", modifier = Modifier.padding(start = 8.dp))
            }
            Button(onClick = onSyncSmart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.ContentPasteSearch, contentDescription = null)
                Text("Sync now", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun PairingCard(
    payload: String,
    nearbyHosts: List<NearbyHostUiModel>,
    nearbyScanInProgress: Boolean,
    onPayloadChanged: (String) -> Unit,
    onScanPairingQr: () -> Unit,
    onFindNearby: () -> Unit,
    onPairNearby: (String) -> Unit,
    onPair: () -> Unit
) {
    FlatCard {
        SectionTitle("Pair with PC")
        Text(
            "Scan the QR on your Linux or Windows app, or find a nearby PC that is accepting new pairing. Paste is only a fallback.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(onClick = onScanPairingQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Text("Scan QR", modifier = Modifier.padding(start = 8.dp))
            }
            Button(onClick = onFindNearby, modifier = Modifier.weight(1f), enabled = !nearbyScanInProgress) {
                Icon(Icons.Outlined.BluetoothSearching, contentDescription = null)
                Text(if (nearbyScanInProgress) "Scanning…" else "Find nearby", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (nearbyHosts.isNotEmpty()) {
            Text("Tap a PC to pair:", style = MaterialTheme.typography.labelLarge)
            nearbyHosts.forEach { host ->
                Button(
                    onClick = { onPairNearby(host.encodedPayload) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                        Text(host.displayName)
                        Text(host.endpoint, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        OutlinedTextField(
            value = payload,
            onValueChange = onPayloadChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Manual pairing payload (fallback)") },
            minLines = 2
        )
        Button(onClick = onPair, modifier = Modifier.fillMaxWidth(), enabled = payload.isNotBlank()) {
            Text("Pair with pasted payload")
        }
    }
}

@Composable
private fun SavedDevicesCard(
    devices: List<SavedDeviceUiModel>,
    onScan: () -> Unit,
    onSelect: (String) -> Unit
) {
    FlatCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Saved Devices")
            TextButton(onClick = onScan) {
                Icon(Icons.Outlined.BluetoothSearching, contentDescription = null)
                Text("Scan", modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (devices.isEmpty()) {
            Text("No saved PCs yet. Pair once; the app keeps searching all saved devices automatically.")
        } else {
            devices.forEach { device ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (device.selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(device.endpoint, style = MaterialTheme.typography.bodySmall)
                            Text(device.statusLabel(), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { onSelect(device.deviceId) }) {
                            Text(if (device.selected) "Reconnect" else "Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceCard(text: String) {
    FlatCard(background = MaterialTheme.colorScheme.surfaceVariant) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.BluetoothSearching, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LastItemCard(item: RecentItemUiModel?) {
    FlatCard {
        SectionTitle("Last Synced Item")
        if (item == null) {
            Text("No clipboard item synced yet.")
        } else {
            RecentItemBody(item)
        }
    }
}

@Composable
private fun RecentItemCard(
    item: RecentItemUiModel,
    onResend: () -> Unit,
    onRestore: () -> Unit,
    onApplyDeferred: () -> Unit
) {
    FlatCard(background = MaterialTheme.colorScheme.surfaceVariant) {
        RecentItemBody(item)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRestore) {
                Text("Copy here")
            }
            TextButton(onClick = onResend) {
                Text("Send")
            }
            if (item.transferState == TransferState.DEFERRED || item.status == "Deferred") {
                TextButton(onClick = onApplyDeferred) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun RecentItemBody(item: RecentItemUiModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = when (item.contentType) {
                ContentType.TEXT -> Icons.Outlined.ContentPasteSearch
                ContentType.URL -> Icons.Outlined.Link
                ContentType.IMAGE -> Icons.Outlined.Photo
                ContentType.MIXED_UNSUPPORTED -> Icons.Outlined.Memory
            },
            contentDescription = null
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.directionLabel, style = MaterialTheme.typography.labelLarge)
            Text(item.previewText, style = MaterialTheme.typography.bodyLarge)
            if (item.previewUri != null) {
                AsyncImage(
                    model = item.previewUri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Text("Payload: ${item.payloadSizeBytes} bytes", style = MaterialTheme.typography.bodySmall)
            Text("State: ${item.transferState} / ${item.status}", style = MaterialTheme.typography.bodySmall)
            Text(item.syncedAtUtc, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FlatCard(
    background: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun TransportKind.label(): String = when (this) {
    TransportKind.LAN -> "Wi-Fi / LAN"
    TransportKind.BLE_FALLBACK -> "Bluetooth fallback"
    TransportKind.NONE -> "No active transport"
}

private fun SavedDeviceUiModel.statusLabel(): String = when {
    connected -> "Connected"
    available -> "Available"
    selected -> "Selected"
    else -> "Saved"
}

private fun SyncMode.description(): String = when (this) {
    SyncMode.MIRROR -> "Automatically mirrors supported clipboard changes when Android allows it."
    SyncMode.MANUAL -> "Only sends from Sync now, notification actions, Quick Settings, or sharing."
    SyncMode.ASK -> "Stages detected items in history so you can choose what to send."
    SyncMode.RECEIVE_ONLY -> "Receives incoming items but does not send this phone's clipboard."
    SyncMode.SEND_ONLY -> "Sends outbound items but does not write incoming items to this clipboard."
}

private fun connectionHealth(syncEnabled: Boolean, pairedDevice: String, connectionLabel: String): String = when {
    !syncEnabled -> "Sync paused"
    pairedDevice.contains("not paired", ignoreCase = true) -> "Pairing needed"
    connectionLabel.contains("connected", ignoreCase = true) -> "Connected"
    connectionLabel.contains("connecting", ignoreCase = true) ||
        connectionLabel.contains("searching", ignoreCase = true) -> "Searching"
    else -> "Paired but offline"
}

private fun readinessSummary(syncEnabled: Boolean, pairedDevice: String): String {
    val pairing = if (pairedDevice.contains("not paired", ignoreCase = true)) {
        "Pair a PC to start."
    } else {
        "Trusted device saved."
    }
    val enabled = if (syncEnabled) "Sync is enabled." else "Sync is paused."
    return "$pairing $enabled Keep notification active for hidden sync."
}
