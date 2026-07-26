import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

struct ContentView: View {
    @ObservedObject var repository: SyncRepository
    @State private var showingScanner = false
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var actionFeedback = "Ready"

    var body: some View {
        NavigationStack {
            List {
                StatusSection(
                    state: repository.uiState,
                    actionFeedback: actionFeedback,
                    onSyncEnabledChanged: repository.setSyncEnabled,
                    onNotificationEnabledChanged: repository.setNotificationEnabled,
                    onAutoScreenshotSyncChanged: repository.setAutoScreenshotSyncEnabled,
                    onPrivacyPausedChanged: repository.setPrivacyPaused,
                    onReconnect: {
                        actionFeedback = "Reconnecting..."
                        repository.reconnect(force: true)
                    },
                    onSyncSmart: {
                        actionFeedback = "Sync request sent."
                        repository.syncSmartNow()
                    },
                    onSyncScreenshot: {
                        actionFeedback = "Latest screenshot sync requested."
                        repository.syncLatestScreenshotNow()
                    },
                    selectedPhotoItem: $selectedPhotoItem
                )

                Section {
                    Label(readinessSummary(repository.uiState), systemImage: "checklist")
                    Label(repository.uiState.guidance, systemImage: "info.circle")
                }

                PairingSection(
                    payload: repository.uiState.manualPairingPayload,
                    onPayloadChanged: repository.updateManualPairingPayload,
                    onScanPairingQr: { showingScanner = true },
                    onPair: {
                        actionFeedback = "Pairing with pasted payload."
                        repository.pair(encodedPayload: repository.uiState.manualPairingPayload)
                    }
                )

                SavedDevicesSection(
                    devices: repository.uiState.savedDevices,
                    onScan: repository.scanSavedDevices,
                    onSelect: repository.selectSavedDevice
                )

                Section("Last Synced Item") {
                    if let item = repository.uiState.lastSyncedItem {
                        RecentItemRow(item: item, onResend: {
                            actionFeedback = "History item queued."
                            repository.resendRecent(eventId: item.eventId)
                        })
                    } else {
                        Text("No clipboard item synced yet.")
                    }
                }

                Section("Recent History") {
                    ForEach(repository.uiState.recentItems) { item in
                        RecentItemRow(item: item, onResend: {
                            actionFeedback = "History item queued."
                            repository.resendRecent(eventId: item.eventId)
                        })
                    }
                }

                Section("Diagnostics") {
                    DisclosureGroup("Connection and transfer events") {
                        HStack {
                            Button("Clear") {
                                actionFeedback = "Diagnostics cleared."
                                repository.clearLogs()
                            }
                            Spacer()
                            Button("Copy report") {
                                actionFeedback = "Debug report copied."
                                repository.copyDebugReport()
                            }
                        }
                        ForEach(repository.uiState.logs) { log in
                            VStack(alignment: .leading, spacing: 4) {
                                Text("\(log.level.rawValue)  \(log.timestampUtc)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Text(log.message)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Clipboard Sync")
            .sheet(isPresented: $showingScanner) {
                QRScannerView { payload in
                    showingScanner = false
                    repository.updateManualPairingPayload(payload)
                    repository.pair(encodedPayload: payload)
                    actionFeedback = "Paired with scanned QR."
                }
            }
            .onChange(of: selectedPhotoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        await MainActor.run {
                            repository.syncImage(image, trigger: "photo-picker")
                            actionFeedback = "Photo sync requested."
                            selectedPhotoItem = nil
                        }
                    }
                }
            }
        }
    }
}

private struct StatusSection: View {
    var state: SyncUiState
    var actionFeedback: String
    var onSyncEnabledChanged: (Bool) -> Void
    var onNotificationEnabledChanged: (Bool) -> Void
    var onAutoScreenshotSyncChanged: (Bool) -> Void
    var onPrivacyPausedChanged: (Bool) -> Void
    var onReconnect: () -> Void
    var onSyncSmart: () -> Void
    var onSyncScreenshot: () -> Void
    @Binding var selectedPhotoItem: PhotosPickerItem?

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(connectionHealth(state))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(state.pairedDeviceLabel)
                    .font(.headline)
                Text("Connection: \(state.connectionLabel)")
                Text("Transport: \(state.transportKind.label)")
                Text(actionFeedback)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Toggle("Sync Enabled", isOn: Binding(get: { state.syncEnabled }, set: onSyncEnabledChanged))
            Toggle("Keep Reminder Active", isOn: Binding(get: { state.notificationEnabled }, set: onNotificationEnabledChanged))
            Toggle("Latest Screenshot Action", isOn: Binding(get: { state.autoScreenshotSyncEnabled }, set: onAutoScreenshotSyncChanged))
            Toggle("Privacy Pause", isOn: Binding(get: { state.privacyPaused }, set: onPrivacyPausedChanged))

            HStack {
                Button(action: onReconnect) {
                    Label("Reconnect", systemImage: "arrow.clockwise")
                }
                Spacer()
                Button(action: onSyncSmart) {
                    Label("Sync now", systemImage: "doc.on.clipboard")
                }
            }
            Button(action: onSyncScreenshot) {
                Label("Sync Latest Screenshot", systemImage: "photo")
            }
            PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                Label("Sync Photo", systemImage: "photo.on.rectangle")
            }
        }
    }
}

private struct PairingSection: View {
    var payload: String
    var onPayloadChanged: (String) -> Void
    var onScanPairingQr: () -> Void
    var onPair: () -> Void

    var body: some View {
        Section("Pair with Windows") {
            Text("Scan the QR code shown on the desktop app. Paste the pairing payload only if scanning is not available.")
            HStack {
                Button(action: onScanPairingQr) {
                    Label("Scan QR", systemImage: "qrcode.viewfinder")
                }
                Spacer()
                Button("Pair", action: onPair)
                    .disabled(payload.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            TextEditor(text: Binding(get: { payload }, set: onPayloadChanged))
                .frame(minHeight: 84)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
    }
}

private func connectionHealth(_ state: SyncUiState) -> String {
    if !state.syncEnabled { return "Sync paused" }
    if state.pairedDeviceLabel.localizedCaseInsensitiveContains("not paired") { return "Pairing needed" }
    if state.connectionLabel.localizedCaseInsensitiveContains("connected") { return "Connected" }
    if state.connectionLabel.localizedCaseInsensitiveContains("connecting") ||
        state.connectionLabel.localizedCaseInsensitiveContains("searching") {
        return "Searching"
    }
    return "Paired but offline"
}

private func readinessSummary(_ state: SyncUiState) -> String {
    let pairing = state.pairedDeviceLabel.localizedCaseInsensitiveContains("not paired")
        ? "Pair a desktop to start."
        : "Trusted device saved."
    let enabled = state.syncEnabled ? "Sync is enabled." : "Sync is paused."
    return "\(pairing) \(enabled) iOS sends are intentionally user-initiated."
}

private struct SavedDevicesSection: View {
    var devices: [SavedDeviceUiModel]
    var onScan: () -> Void
    var onSelect: (String) -> Void

    var body: some View {
        Section {
            if devices.isEmpty {
                Text("No saved Windows devices yet. Pair once, then reconnect from this list.")
            } else {
                ForEach(devices) { device in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(device.displayName).font(.headline)
                            Text(device.endpoint).font(.caption).foregroundStyle(.secondary)
                            Text(statusLabel(device)).font(.caption)
                        }
                        Spacer()
                        Button(device.selected ? "Reconnect" : "Connect") {
                            onSelect(device.deviceId)
                        }
                    }
                }
            }
        } header: {
            HStack {
                Text("Saved Devices")
                Spacer()
                Button(action: onScan) {
                    Label("Scan", systemImage: "dot.radiowaves.left.and.right")
                }
            }
        }
    }

    private func statusLabel(_ device: SavedDeviceUiModel) -> String {
        if device.connected { return "Connected" }
        if device.available { return "Available" }
        if device.selected { return "Selected" }
        return "Saved"
    }
}

private struct RecentItemRow: View {
    var item: RecentItemUiModel
    var onResend: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                Image(systemName: iconName)
                    .foregroundStyle(.accent)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.directionLabel).font(.headline)
                    Text(item.previewText).lineLimit(3)
                    Text("Payload: \(item.payloadSizeBytes) bytes")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text("State: \(item.transferState.rawValue) / \(item.status)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(item.syncedAtUtc)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Button("Resend", action: onResend)
                .font(.caption)
        }
    }

    private var iconName: String {
        switch item.contentType {
        case .text: return "doc.on.clipboard"
        case .url: return "link"
        case .image: return "photo"
        case .mixedUnsupported: return "questionmark.square"
        }
    }
}
