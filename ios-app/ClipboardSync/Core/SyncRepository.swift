import Combine
import Foundation
import UIKit
import UserNotifications

struct RecentItemUiModel: Identifiable, Equatable {
    var id: String { eventId }
    var eventId: String
    var contentType: ContentType
    var previewText: String
    var previewUri: String?
    var payloadSizeBytes: Int64
    var syncedAtUtc: String
    var directionLabel: String
    var transferState: TransferState
    var status: String
}

struct SavedDeviceUiModel: Identifiable, Equatable {
    var id: String { deviceId }
    var deviceId: String
    var displayName: String
    var endpoint: String
    var selected: Bool
    var available: Bool
    var connected: Bool
}

struct SyncUiState: Equatable {
    var syncEnabled = true
    var notificationEnabled = true
    var pairedDeviceLabel = "Not paired"
    var connectionLabel = "Disconnected"
    var transportKind: TransportKind = .none
    var lastSyncedItem: RecentItemUiModel?
    var recentItems: [RecentItemUiModel] = []
    var logs: [LogEntry] = []
    var savedDevices: [SavedDeviceUiModel] = []
    var manualPairingPayload = ""
    var autoScreenshotSyncEnabled = true
    var privacyPaused = false
    var guidance = "Use Sync Now, Shortcuts, or the Share Extension for reliable iOS sends. iOS does not allow hidden clipboard monitoring, so background sync is intentionally user-initiated."
}

private struct PendingEvent {
    var normalized: NormalizedClipboard
    var attempts = 0
    var lastAttemptUtc = Date()
}

private struct IncomingTransfer {
    var event: ClipboardEvent
    var transferId: String
    var data = Data()
}

@MainActor
final class SyncRepository: ObservableObject {
    @Published private(set) var uiState = SyncUiState()

    let logger: AppLogger
    private let store: TrustedDeviceStore
    private let imageCache: ImageCacheStore
    private let normalizer: ClipboardNormalizer
    private let applyUseCase: ClipboardApplyUseCase
    private let lanClient: LanClient
    private let discovery: PeerDiscovery
    private let screenshots = ScreenshotRepository()
    private let loopGuard = LoopGuard()
    private let defaults = UserDefaults.standard

    private var pendingByEventId: [String: PendingEvent] = [:]
    private var outboundQueue: [PendingEvent] = []
    private var incomingTransfers: [String: IncomingTransfer] = [:]
    private var lastLocalClipboardAt = Date.distantPast
    private var lastQueuedLocalHash: String?
    private var availablePeerIds = Set<String>()
    private var cancellables: Set<AnyCancellable> = []

    private let conflictWindow: TimeInterval = 1.5

    init(
        logger: AppLogger = AppLogger(),
        store: TrustedDeviceStore = TrustedDeviceStore(),
        imageCache: ImageCacheStore = ImageCacheStore()
    ) {
        self.logger = logger
        self.store = store
        self.imageCache = imageCache
        self.normalizer = ClipboardNormalizer(store: store, imageCache: imageCache)
        self.applyUseCase = ClipboardApplyUseCase(imageCache: imageCache)
        self.lanClient = LanClient(logger: logger)
        self.discovery = PeerDiscovery(logger: logger)

        imageCache.cleanup()
        loadPreferences()
        refreshPairedState()
        discovery.startBonjour()
        lanClient.onEnvelope = { [weak self] envelope in
            Task { @MainActor in await self?.handleIncomingEnvelope(envelope) }
        }
        lanClient.$state.sink { [weak self] state in
            Task { @MainActor in self?.handleLanStateChange(state) }
        }.store(in: &cancellables)
        logger.$entries.sink { [weak self] entries in
            Task { @MainActor in self?.uiState.logs = entries }
        }.store(in: &cancellables)
    }

    func onAppForegrounded() {
        logger.info("iOS app foregrounded")
        drainShareInbox()
        if uiState.syncEnabled {
            ensureConnected()
        }
    }

    func setSyncEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: "sync_enabled")
        uiState.syncEnabled = enabled
        if enabled {
            ensureConnected(force: true)
        } else {
            lanClient.disconnect()
        }
    }

    func setNotificationEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: "notification_enabled")
        uiState.notificationEnabled = enabled
        if enabled {
            requestReminderNotification()
        } else {
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["clipboard-sync-ios-limits"])
        }
    }

    func setAutoScreenshotSyncEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: "auto_screenshot_sync_enabled")
        uiState.autoScreenshotSyncEnabled = enabled
        logger.info("Latest screenshot action set to \(enabled)")
    }

    func setPrivacyPaused(_ paused: Bool) {
        defaults.set(paused, forKey: "privacy_paused")
        uiState.privacyPaused = paused
        logger.warn(paused ? "Privacy pause enabled; outbound sync paused" : "Privacy pause disabled; outbound sync resumed")
    }

    func updateManualPairingPayload(_ payload: String) {
        uiState.manualPairingPayload = payload
    }

    func pair(encodedPayload: String) {
        do {
            let payload = try PairingCodeCodec.decode(encodedPayload)
            store.savePairingPayload(payload)
            uiState.manualPairingPayload = ""
            refreshPairedState()
            ensureConnected(force: true)
            logger.info("Saved trusted peer \(payload.displayName)")
        } catch {
            logger.error("Invalid pairing payload", error)
        }
    }

    func reconnect(force: Bool = true) {
        logger.info("Manual reconnect requested")
        ensureConnected(force: force)
    }

    func scanSavedDevices() {
        Task {
            let peers = store.trustedPeers()
            guard !peers.isEmpty else {
                logger.warn("No saved devices to scan")
                return
            }
            var found = Set<String>()
            for peer in peers {
                if let discovered = await discovery.discoverTrustedPeer(peer) {
                    store.updateEndpoint(peer, host: discovered.host, port: discovered.port)
                    found.insert(peer.deviceId)
                }
            }
            availablePeerIds = found
            refreshPairedState()
        }
    }

    func selectSavedDevice(deviceId: String) {
        store.selectPeer(deviceId: deviceId)
        lanClient.disconnect()
        refreshPairedState()
        ensureConnected(force: true)
    }

    func syncSmartNow(trigger: String = "smart-sync") {
        guard uiState.syncEnabled, !uiState.privacyPaused else {
            logger.warn("Smart sync skipped because sync is disabled or privacy paused")
            return
        }
        Task {
            ensureConnected(force: true)
            if uiState.autoScreenshotSyncEnabled, await syncLatestScreenshotIfAvailable(trigger: "\(trigger)-screenshot") {
                return
            }
            syncCurrentClipboardNow(trigger: "\(trigger)-clipboard")
        }
    }

    func syncCurrentClipboardNow(trigger: String = "manual-button") {
        guard uiState.syncEnabled, !uiState.privacyPaused else {
            logger.warn("Clipboard sync skipped because sync is disabled or privacy paused")
            return
        }
        guard let normalized = normalizer.normalizeCurrentPasteboard() else {
            logger.warn("Manual sync could not find a supported pasteboard item to send")
            return
        }
        syncNormalizedIfNeeded(normalized, trigger: trigger, forceResend: true)
    }

    func syncLatestScreenshotNow(trigger: String = "manual-screenshot") {
        Task {
            _ = await syncLatestScreenshotIfAvailable(trigger: trigger, force: true)
        }
    }

    func syncImage(_ image: UIImage, trigger: String = "photo-picker") {
        guard uiState.syncEnabled, !uiState.privacyPaused else {
            logger.warn("Image sync skipped because sync is disabled or privacy paused")
            return
        }
        guard let normalized = normalizer.normalizeImage(image, previewUri: nil) else {
            logger.warn("Selected image could not be normalized")
            return
        }
        syncNormalizedIfNeeded(normalized, trigger: trigger, forceResend: true)
    }

    func resendRecent(eventId: String) {
        guard let pending = pendingByEventId[eventId] else {
            logger.warn("Only queued or pending items can be resent in this preview build")
            return
        }
        outboundQueue.append(pending)
        logger.info("Requeued clipboard event \(eventId)")
        flushQueue()
    }

    func copyDebugReport() {
        let report = """
        Clipboard Sync iOS Debug Report
        Connection: \(uiState.connectionLabel)
        Selected device: \(uiState.pairedDeviceLabel)
        Transport: \(uiState.transportKind.rawValue)
        Saved devices: \(uiState.savedDevices.map { "\($0.displayName) \($0.endpoint)" }.joined(separator: ", "))
        Recent logs:
        \(uiState.logs.prefix(30).map { "\($0.timestampUtc) [\($0.level.rawValue)] \($0.message)" }.joined(separator: "\n"))
        """
        UIPasteboard.general.string = report
        logger.info("Copied debug report to pasteboard")
    }

    func clearLogs() {
        logger.clear()
    }

    private func ensureConnected(force: Bool = false) {
        guard let peer = store.selectedPeer() else {
            logger.warn("Connect skipped because no trusted peer is configured")
            return
        }
        if !force, [.connecting, .connected, .ready].contains(lanClient.state) {
            return
        }
        if let resolved = discovery.knownHost(for: peer.serviceName) {
            store.updateEndpoint(peer, host: resolved.host, port: resolved.port)
        }
        let effectivePeer = store.selectedPeer() ?? peer
        lanClient.connect(peer: effectivePeer, localDeviceId: store.localDeviceId)
    }

    private func syncNormalizedIfNeeded(_ normalized: NormalizedClipboard, trigger: String, forceResend: Bool) {
        let hash = normalized.event.contentHashSha256
        if loopGuard.shouldSuppressLocal(hash: hash) {
            logger.info("Suppressed pasteboard echo for \(normalized.event.eventId)")
            return
        }
        if !forceResend, lastQueuedLocalHash == hash {
            logger.info("Skipped unchanged iOS pasteboard on \(trigger)")
            return
        }

        var pending = PendingEvent(normalized: normalized)
        pending.normalized.event.transferState = .queued
        outboundQueue.append(pending)
        pendingByEventId[normalized.event.eventId] = pending
        loopGuard.rememberSeenEvent(normalized.event.eventId)
        lastQueuedLocalHash = hash
        lastLocalClipboardAt = Date()
        addRecent(normalized, direction: "iOS -> Windows", status: "Queued")
        logger.info("Queued iOS clipboard event \(normalized.event.eventId) from \(trigger)")
        flushQueue()
    }

    private func flushQueue() {
        guard lanClient.state == .ready else {
            if !outboundQueue.isEmpty, uiState.syncEnabled {
                ensureConnected()
            }
            return
        }
        Task {
            while !outboundQueue.isEmpty, lanClient.state == .ready {
                var pending = outboundQueue.removeFirst()
                pending.attempts += 1
                pending.lastAttemptUtc = Date()
                var event = pending.normalized.event
                event.transferState = .awaitingAck
                logger.info("Sending clipboard event \(event.eventId), attempt \(pending.attempts)")
                let sent = await lanClient.sendClipboardEvent(event, imageBytes: pending.normalized.imageBytes)
                if !sent {
                    outboundQueue.insert(pending, at: 0)
                    lanClient.disconnect()
                    return
                }
                pendingByEventId[event.eventId] = pending
                scheduleAckTimeout(eventId: event.eventId)
            }
        }
    }

    private func scheduleAckTimeout(eventId: String) {
        Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard var pending = pendingByEventId[eventId] else { return }
            if pending.attempts >= 3 {
                pendingByEventId.removeValue(forKey: eventId)
                updateRecentStatus(eventId: eventId, transferState: .failed, status: "Timed out")
                logger.warn("Event \(eventId) failed after retries")
            } else {
                outboundQueue.append(pending)
                logger.warn("Event \(eventId) timed out waiting for ack; retrying")
                flushQueue()
            }
        }
    }

    private func handleLanStateChange(_ state: LanConnectionState) {
        uiState.connectionLabel = state.rawValue.capitalized
        uiState.transportKind = state == .ready ? .lan : .none
        refreshPairedState()
        if state == .ready {
            flushQueue()
        }
    }

    private func handleIncomingEnvelope(_ envelope: ProtocolEnvelope) async {
        switch envelope.type {
        case "auth_challenge":
            handleAuthChallenge(envelope)
        case "peer_status":
            logger.info("Peer reported status \(envelope.status ?? "-")")
        case "clipboard_offer":
            await handleClipboardOffer(envelope.event)
        case "transfer_begin":
            logger.info("Incoming transfer \(envelope.transfer?.transferId ?? "-") started")
        case "transfer_chunk":
            handleTransferChunk(envelope.chunk)
        case "transfer_complete":
            await handleTransferComplete(envelope.transfer)
        case "clipboard_ack":
            handleAck(envelope)
        case "clipboard_reject":
            handleReject(envelope)
        case "ping":
            _ = lanClient.sendEnvelope(ProtocolEnvelope(type: "pong"))
        default:
            logger.warn("Ignored unknown envelope type \(envelope.type)")
        }
    }

    private func handleAuthChallenge(_ envelope: ProtocolEnvelope) {
        guard let peer = store.selectedPeer(), let challenge = envelope.challenge else { return }
        let sessionId = envelope.sessionId ?? CryptoUtils.uuidV7()
        let response = CryptoUtils.hmacSha256Base64(
            secret: peer.pairingCode,
            message: "\(challenge):\(sessionId):\(store.localDeviceId)"
        )
        _ = lanClient.sendEnvelope(ProtocolEnvelope(type: "auth_response", sessionId: sessionId, deviceId: store.localDeviceId, response: response))
    }

    private func handleClipboardOffer(_ event: ClipboardEvent?) async {
        guard let event else { return }
        if loopGuard.hasSeenEvent(event.eventId) {
            logger.info("Ignoring already seen event \(event.eventId)")
            return
        }
        switch event.contentType {
        case .text, .url:
            await applyRemoteEvent(event, imageBytes: nil)
        case .image:
            let transferId = event.image?.transferId ?? event.eventId
            incomingTransfers[transferId] = IncomingTransfer(event: event, transferId: transferId)
            logger.info("Prepared incoming image transfer \(transferId)")
        case .mixedUnsupported:
            _ = lanClient.sendEnvelope(ProtocolEnvelope(type: "clipboard_reject", reason: "Unsupported content type", event: event))
        }
    }

    private func handleTransferChunk(_ chunk: TransferChunk?) {
        guard let chunk, var incoming = incomingTransfers[chunk.transferId],
              let data = Data(base64Encoded: chunk.base64Payload) else { return }
        incoming.data.append(data)
        incomingTransfers[chunk.transferId] = incoming
    }

    private func handleTransferComplete(_ descriptor: TransferDescriptor?) async {
        guard let descriptor, let incoming = incomingTransfers.removeValue(forKey: descriptor.transferId) else { return }
        guard CryptoUtils.sha256Hex(incoming.data).caseInsensitiveCompare(descriptor.checksumSha256) == .orderedSame else {
            logger.warn("Checksum mismatch for transfer \(descriptor.transferId)")
            _ = lanClient.sendEnvelope(ProtocolEnvelope(type: "clipboard_reject", reason: "Checksum mismatch", event: incoming.event))
            return
        }
        await applyRemoteEvent(incoming.event, imageBytes: incoming.data)
    }

    private func applyRemoteEvent(_ event: ClipboardEvent, imageBytes: Data?) async {
        if lastLocalClipboardAt > Date().addingTimeInterval(-conflictWindow) {
            logger.warn("Deferred remote event \(event.eventId) because local pasteboard changed recently")
            addRecent(
                NormalizedClipboard(event: event, imageBytes: imageBytes, previewText: event.textPayload ?? "Deferred image", previewUri: nil, fromRemote: true),
                direction: "Windows -> iOS",
                status: "Deferred"
            )
            _ = lanClient.sendEnvelope(ProtocolEnvelope(type: "clipboard_ack", status: "deferred", event: event))
            return
        }

        if await applyUseCase.applyRemoteClip(event: event, imageBytes: imageBytes) {
            lastQueuedLocalHash = event.contentHashSha256
            loopGuard.markRemoteApplied(event.contentHashSha256)
            loopGuard.rememberSeenEvent(event.eventId)
            addRecent(
                NormalizedClipboard(event: event, imageBytes: imageBytes, previewText: event.textPayload ?? "Image \(event.image?.width ?? 0)x\(event.image?.height ?? 0)", previewUri: nil, fromRemote: true),
                direction: "Windows -> iOS",
                status: "Applied"
            )
            _ = lanClient.sendEnvelope(ProtocolEnvelope(type: "clipboard_ack", status: "applied", event: event))
        }
    }

    private func handleAck(_ envelope: ProtocolEnvelope) {
        guard let eventId = envelope.event?.eventId else { return }
        pendingByEventId.removeValue(forKey: eventId)
        let status = envelope.status ?? "Acked"
        updateRecentStatus(eventId: eventId, transferState: status == "deferred" ? .deferred : .acked, status: status.capitalized)
        logger.info("Event \(eventId) acked")
    }

    private func handleReject(_ envelope: ProtocolEnvelope) {
        let reason = envelope.reason ?? "Rejected"
        logger.warn("Clipboard event rejected: \(reason)")
        if let eventId = envelope.event?.eventId {
            pendingByEventId.removeValue(forKey: eventId)
            updateRecentStatus(eventId: eventId, transferState: .failed, status: reason)
        }
    }

    private func syncLatestScreenshotIfAvailable(trigger: String, force: Bool = false) async -> Bool {
        guard uiState.syncEnabled, !uiState.privacyPaused else {
            logger.warn("Screenshot sync skipped because sync is disabled or privacy paused")
            return false
        }
        guard let screenshot = await screenshots.latestScreenshot(maxAgeMillis: force ? 10 * 60 * 1000 : 5 * 60 * 1000),
              let normalized = normalizer.normalizeImage(screenshot.image, previewUri: nil) else {
            logger.warn("No recent screenshot found for \(trigger)")
            return false
        }
        logger.info("Queued latest screenshot from \(trigger): \(screenshot.displayName)")
        syncNormalizedIfNeeded(normalized, trigger: trigger, forceResend: true)
        return true
    }

    private func drainShareInbox() {
        guard let defaults = UserDefaults(suiteName: SharedAppGroup.identifier),
              let data = defaults.data(forKey: SharedAppGroup.inboxKey),
              let items = try? ProtocolJSON.decoder.decode([SharedShareItem].self, from: data),
              !items.isEmpty else {
            return
        }
        defaults.removeObject(forKey: SharedAppGroup.inboxKey)
        logger.info("Processing \(items.count) shared item(s)")
        for item in items {
            switch item.kind {
            case .text:
                if let text = item.text {
                    syncNormalizedIfNeeded(normalizer.normalizeText(text), trigger: "share-extension", forceResend: true)
                }
            case .url:
                if let text = item.text {
                    syncNormalizedIfNeeded(normalizer.normalizeText(text, forcedType: .url), trigger: "share-extension", forceResend: true)
                }
            case .image:
                if let base64 = item.base64Data, let data = Data(base64Encoded: base64), let image = UIImage(data: data),
                   let normalized = normalizer.normalizeImage(image, previewUri: nil) {
                    syncNormalizedIfNeeded(normalized, trigger: "share-extension", forceResend: true)
                }
            }
        }
    }

    private func addRecent(_ normalized: NormalizedClipboard, direction: String, status: String) {
        let item = RecentItemUiModel(
            eventId: normalized.event.eventId,
            contentType: normalized.event.contentType,
            previewText: normalized.previewText,
            previewUri: normalized.previewUri,
            payloadSizeBytes: normalized.event.payloadSizeBytes,
            syncedAtUtc: Date.utcNowString,
            directionLabel: direction,
            transferState: normalized.event.transferState,
            status: status
        )
        uiState.recentItems = Array(([item] + uiState.recentItems).prefix(20))
        uiState.lastSyncedItem = uiState.recentItems.first
    }

    private func updateRecentStatus(eventId: String, transferState: TransferState, status: String) {
        uiState.recentItems = uiState.recentItems.map {
            $0.eventId == eventId ? RecentItemUiModel(
                eventId: $0.eventId,
                contentType: $0.contentType,
                previewText: $0.previewText,
                previewUri: $0.previewUri,
                payloadSizeBytes: $0.payloadSizeBytes,
                syncedAtUtc: $0.syncedAtUtc,
                directionLabel: $0.directionLabel,
                transferState: transferState,
                status: status
            ) : $0
        }
        uiState.lastSyncedItem = uiState.recentItems.first
    }

    private func refreshPairedState() {
        let selected = store.selectedPeer()
        let connected = lanClient.state == .ready
        uiState.pairedDeviceLabel = selected?.displayName ?? "Not paired"
        uiState.savedDevices = store.trustedPeers().map { peer in
            SavedDeviceUiModel(
                deviceId: peer.deviceId,
                displayName: peer.displayName,
                endpoint: "\(peer.host):\(peer.port)",
                selected: peer.deviceId == selected?.deviceId,
                available: availablePeerIds.contains(peer.deviceId) || (peer.deviceId == selected?.deviceId && connected),
                connected: peer.deviceId == selected?.deviceId && connected
            )
        }.sorted {
            if $0.connected != $1.connected { return $0.connected && !$1.connected }
            if $0.available != $1.available { return $0.available && !$1.available }
            return $0.displayName < $1.displayName
        }
    }

    private func loadPreferences() {
        uiState.syncEnabled = defaults.object(forKey: "sync_enabled") as? Bool ?? true
        uiState.notificationEnabled = defaults.object(forKey: "notification_enabled") as? Bool ?? true
        uiState.autoScreenshotSyncEnabled = defaults.object(forKey: "auto_screenshot_sync_enabled") as? Bool ?? true
        uiState.privacyPaused = defaults.object(forKey: "privacy_paused") as? Bool ?? false
    }

    private func requestReminderNotification() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { [weak self] granted, error in
            Task { @MainActor in
                if let error {
                    self?.logger.error("Notification authorization failed", error)
                    return
                }
                guard granted else {
                    self?.logger.warn("Reminder notifications are disabled in iOS settings")
                    return
                }
                let content = UNMutableNotificationContent()
                content.title = "Clipboard Sync"
                content.body = "iOS requires user action for clipboard sends. Use Sync Now, Shortcuts, or Share."
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 2, repeats: false)
                let request = UNNotificationRequest(identifier: "clipboard-sync-ios-limits", content: content, trigger: trigger)
                UNUserNotificationCenter.current().add(request)
                self?.logger.info("iOS reminder notification scheduled")
            }
        }
    }
}

enum SharedAppGroup {
    static let identifier = "group.com.clipboardsync.ios"
    static let inboxKey = "shared_inbox"
}

enum SharedShareKind: String, Codable {
    case text
    case url
    case image
}

struct SharedShareItem: Codable {
    var kind: SharedShareKind
    var text: String?
    var base64Data: String?
}
