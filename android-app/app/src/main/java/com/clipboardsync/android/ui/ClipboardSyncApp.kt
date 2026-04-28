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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.clipboardsync.android.protocol.ContentType
import com.clipboardsync.android.protocol.TransportKind
import com.clipboardsync.android.service.RecentItemUiModel
import com.clipboardsync.android.service.SavedDeviceUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncApp(
    viewModel: ClipboardSyncViewModel,
    onScanPairingQr: () -> Unit = {},
    onNotificationEnabledToggle: (Boolean) -> Unit = viewModel::onNotificationEnabledChanged
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    onReconnect = viewModel::onReconnect,
                    onSyncSmart = viewModel::onSyncSmart
                )
            }
            item { GuidanceCard(state.guidance) }
            item {
                PairingCard(
                    payload = state.manualPairingPayload,
                    onPayloadChanged = viewModel::onManualPayloadChanged,
                    onScanPairingQr = onScanPairingQr,
                    onPair = { viewModel.onPair(state.manualPairingPayload) }
                )
            }
            item {
                SavedDevicesCard(
                    devices = state.savedDevices,
                    onScan = viewModel::onScanSavedDevices,
                    onSelect = viewModel::onSelectSavedDevice
                )
            }
            item { LastItemCard(state.lastSyncedItem) }
            item { SectionTitle("Recent History") }
            items(state.recentItems, key = { it.eventId }) { item ->
                RecentItemCard(item, onResend = { viewModel.onResendRecent(item.eventId) })
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("Diagnostics")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::onClearLogs) { Text("Clear") }
                        TextButton(onClick = viewModel::onCopyDebugReport) { Text("Copy report") }
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
                Text("Paired Device", style = MaterialTheme.typography.labelLarge)
                Text(pairedDevice, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Connection: $connectionLabel", style = MaterialTheme.typography.bodyMedium)
                Text("Transport: ${transport.label()}", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = syncEnabled, onCheckedChange = onSyncEnabledChanged)
        }

        SettingRow(
            title = "Keep Notification Active",
            description = "Keeps the background link alive while the app is hidden.",
            checked = notificationEnabled,
            onCheckedChange = onNotificationEnabledChanged
        )
        SettingRow(
            title = "Auto-sync Screenshots",
            description = "Sends new screenshots from MediaStore.",
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
    onPayloadChanged: (String) -> Unit,
    onScanPairingQr: () -> Unit,
    onPair: () -> Unit
) {
    FlatCard {
        SectionTitle("Pair with Windows")
        Text(
            "Scan the QR code shown on the Windows app. If your camera struggles, raise the PC screen brightness or paste the payload.",
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
            Button(onClick = onPair, modifier = Modifier.weight(1f)) {
                Text("Pair")
            }
        }
        OutlinedTextField(
            value = payload,
            onValueChange = onPayloadChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Manual pairing payload") },
            minLines = 2
        )
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
            Text("No saved Windows devices yet. Pair once, then reconnect from this list.")
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
private fun RecentItemCard(item: RecentItemUiModel, onResend: () -> Unit) {
    FlatCard(background = MaterialTheme.colorScheme.surfaceVariant) {
        RecentItemBody(item)
        TextButton(onClick = onResend) {
            Text("Resend")
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
