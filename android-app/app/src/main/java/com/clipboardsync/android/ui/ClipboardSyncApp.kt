package com.clipboardsync.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncApp(
    viewModel: ClipboardSyncViewModel,
    onNotificationEnabledToggle: (Boolean) -> Unit = viewModel::onNotificationEnabledChanged
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clipboard Sync") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    pairedDevice = state.pairedDeviceLabel,
                    connectionLabel = state.connectionLabel,
                    transport = state.transportKind,
                    syncEnabled = state.syncEnabled,
                    notificationEnabled = state.notificationEnabled,
                    onSyncEnabledChanged = viewModel::onSyncEnabledChanged,
                    onNotificationEnabledChanged = onNotificationEnabledToggle,
                    onReconnect = viewModel::onReconnect,
                    onSyncCurrentClipboard = viewModel::onSyncCurrentClipboard
                )
            }
            item {
                GuidanceCard(state.guidance)
            }
            item {
                PairingCard(
                    payload = state.manualPairingPayload,
                    onPayloadChanged = viewModel::onManualPayloadChanged,
                    onPair = { viewModel.onPair(state.manualPairingPayload) }
                )
            }
            item {
                LastItemCard(state.lastSyncedItem)
            }
            item {
                SectionTitle("Recent sync history")
            }
            items(state.recentItems, key = { it.eventId }) { item ->
                RecentItemCard(item)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionTitle("Diagnostics")
                    Button(onClick = viewModel::onClearLogs) {
                        Text("Clear")
                    }
                }
            }
            items(state.logs, key = { it.timestampUtc + it.message }) { log ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${log.level}  ${log.timestampUtc}", style = MaterialTheme.typography.labelMedium)
                        Text(log.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
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
    onSyncEnabledChanged: (Boolean) -> Unit,
    onNotificationEnabledChanged: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onSyncCurrentClipboard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Paired device", style = MaterialTheme.typography.labelLarge)
                    Text(pairedDevice, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Switch(checked = syncEnabled, onCheckedChange = onSyncEnabledChanged)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep notification active", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Shows the pinned Sync now notification. Turning it off stops the foreground service while the app is hidden.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = notificationEnabled, onCheckedChange = onNotificationEnabledChanged)
            }
            Text("Connection: $connectionLabel")
            Text(
                "Transport: ${
                    when (transport) {
                        TransportKind.LAN -> "Wi-Fi / LAN"
                        TransportKind.BLE_FALLBACK -> "Bluetooth fallback"
                        TransportKind.NONE -> "No active transport"
                    }
                }"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null)
                    Text("Reconnect", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = onSyncCurrentClipboard,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.ContentPasteSearch, contentDescription = null)
                    Text("Sync clipboard", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun GuidanceCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.BluetoothSearching, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PairingCard(
    payload: String,
    onPayloadChanged: (String) -> Unit,
    onPair: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("Manual pairing")
            Text("Paste the pairing payload shown by the Windows app. This contains the server address, certificate fingerprint, and temporary pairing secret.")
            OutlinedTextField(
                value = payload,
                onValueChange = onPayloadChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing payload") }
            )
            Button(onClick = onPair) {
                Text("Pair device")
            }
        }
    }
}

@Composable
private fun LastItemCard(item: RecentItemUiModel?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("Last synced item")
            if (item == null) {
                Text("No clipboard item synced yet.")
            } else {
                RecentItemBody(item)
            }
        }
    }
}

@Composable
private fun RecentItemCard(item: RecentItemUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            RecentItemBody(item)
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Text("Payload: ${item.payloadSizeBytes} bytes")
            Text("State: ${item.transferState} / ${item.status}")
            Text(item.syncedAtUtc, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
