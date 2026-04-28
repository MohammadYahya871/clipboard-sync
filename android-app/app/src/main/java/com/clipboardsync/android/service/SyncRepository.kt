package com.clipboardsync.android.service

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.ContentObserver
import android.util.Base64
import com.clipboardsync.android.clipboard.ClipboardApplyUseCase
import com.clipboardsync.android.clipboard.ClipboardNormalizer
import com.clipboardsync.android.clipboard.ImageCacheStore
import com.clipboardsync.android.clipboard.LoopGuard
import com.clipboardsync.android.clipboard.ScreenshotRepository
import com.clipboardsync.android.protocol.ImageMetadata
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.diagnostics.LogEntry
import com.clipboardsync.android.pairing.LocalDeviceIdentityStore
import com.clipboardsync.android.pairing.PairingCodeCodec
import com.clipboardsync.android.pairing.TrustedDeviceRepository
import com.clipboardsync.android.protocol.ClipboardEvent
import com.clipboardsync.android.protocol.ContentType
import com.clipboardsync.android.protocol.NormalizedClipboard
import com.clipboardsync.android.protocol.ProtocolEnvelope
import com.clipboardsync.android.protocol.TransferState
import com.clipboardsync.android.protocol.TransportKind
import com.clipboardsync.android.storage.CryptoUtils
import com.clipboardsync.android.transport.LanClient
import com.clipboardsync.android.transport.LanConnectionState
import com.clipboardsync.android.transport.LanPeerDiscovery
import com.clipboardsync.android.transport.NsdPeerDiscovery
import com.clipboardsync.android.transport.TransportSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class RecentItemUiModel(
    val eventId: String,
    val contentType: ContentType,
    val previewText: String,
    val previewUri: String? = null,
    val payloadSizeBytes: Long,
    val syncedAtUtc: String,
    val directionLabel: String,
    val transferState: TransferState,
    val status: String
)

data class SavedDeviceUiModel(
    val deviceId: String,
    val displayName: String,
    val endpoint: String,
    val selected: Boolean,
    val available: Boolean,
    val connected: Boolean
)

data class SyncUiState(
    val syncEnabled: Boolean = true,
    val notificationEnabled: Boolean = true,
    val pairedDeviceLabel: String = "Not paired",
    val connectionLabel: String = "Disconnected",
    val transportKind: TransportKind = TransportKind.NONE,
    val lastSyncedItem: RecentItemUiModel? = null,
    val recentItems: List<RecentItemUiModel> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val savedDevices: List<SavedDeviceUiModel> = emptyList(),
    val manualPairingPayload: String = "",
    val autoScreenshotSyncEnabled: Boolean = true,
    val privacyPaused: Boolean = false,
    val guidance: String = "Add the Clipboard Sync Quick Settings tile for the fastest one-tap send. The smart sync action prefers a fresh latest screenshot by copying it into Android clipboard and sending it to Windows; otherwise it falls back to the current clipboard. The notification now mainly keeps the background link alive."
)

private data class PendingEvent(
    val normalized: NormalizedClipboard,
    var attempts: Int = 0,
    var lastAttemptUtc: Instant = Instant.now()
)

private data class IncomingTransfer(
    val event: ClipboardEvent,
    val transferId: String,
    val output: ByteArrayOutputStream = ByteArrayOutputStream()
)

class SyncRepository(
    app: Application,
    private val logger: AppLogger,
    private val localDeviceIdentityStore: LocalDeviceIdentityStore,
    private val trustedDeviceRepository: TrustedDeviceRepository,
    private val imageCacheStore: ImageCacheStore,
    private val screenshotRepository: ScreenshotRepository,
    private val clipboardNormalizer: ClipboardNormalizer,
    private val clipboardApplyUseCase: ClipboardApplyUseCase,
    private val lanClient: LanClient,
    private val lanPeerDiscovery: LanPeerDiscovery,
    private val nsdPeerDiscovery: NsdPeerDiscovery,
    private val transportSelector: TransportSelector,
    private val loopGuard: LoopGuard
) {
    private val appContext = app.applicationContext
    private val clipboardManager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val prefs = app.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(
        SyncUiState(
            syncEnabled = isSyncEnabled(),
            notificationEnabled = isNotificationEnabled(),
            autoScreenshotSyncEnabled = isAutoScreenshotSyncEnabled(),
            privacyPaused = isPrivacyPaused()
        )
    )
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val outboundQueue = ArrayDeque<PendingEvent>()
    private val pendingByEventId = ConcurrentHashMap<String, PendingEvent>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()
    private var uiForeground = false
    private var serviceActive = false
    private var lastLocalClipboardAt = Instant.EPOCH
    private var lastQueuedLocalHash: String? = null
    private var reconnectJob: Job? = null
    private var discoveryConnectJob: Job? = null
    private var availablePeerIds: Set<String> = emptySet()
    private var screenshotObserver: ContentObserver? = null
    private var lastSyncedScreenshotId: Long = prefs.getLong(KEY_LAST_SCREENSHOT_ID, -1L)

    init {
        imageCacheStore.cleanup()
        refreshPairedState()
        updateScreenshotObserver()
        scope.launch {
            logger.entries.collectLatest { entries ->
                _uiState.value = _uiState.value.copy(logs = entries)
            }
        }
        scope.launch {
            lanClient.state.collectLatest { state ->
                handleLanStateChange(state)
            }
        }
        scope.launch {
            lanClient.incoming.collectLatest { envelope ->
                handleIncomingEnvelope(envelope)
            }
        }
        nsdPeerDiscovery.start()
    }

    fun onUiForegroundChanged(active: Boolean) {
        uiForeground = active
        logger.info("UI foreground changed: $active")
        if (active && isSyncEnabled()) {
            ensureConnected()
            if (isNotificationEnabled() && NotificationPermissionHelper.canShowNotifications(appContext)) {
                ForegroundSyncService.sync(appContext)
            }
            scope.launch {
                syncCurrentClipboardWithRetryWindow("foreground-resume")
            }
        }
    }

    fun setServiceActive(active: Boolean) {
        serviceActive = active
        logger.info("Service active changed: $active")
        if (active && isSyncEnabled()) {
            ensureConnected()
        } else if (!active && !uiForeground) {
            lanClient.disconnect()
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
        _uiState.value = _uiState.value.copy(syncEnabled = enabled, notificationEnabled = isNotificationEnabled())
        if (enabled) {
            ensureConnected(force = true)
            if (isNotificationEnabled() && NotificationPermissionHelper.canShowNotifications(appContext)) {
                ForegroundSyncService.sync(appContext)
            }
        } else {
            lanClient.disconnect()
            ForegroundSyncService.stop(appContext)
        }
        updateScreenshotObserver()
    }

    fun setNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
        _uiState.value = _uiState.value.copy(notificationEnabled = enabled, syncEnabled = isSyncEnabled())
        if (enabled) {
            logger.info("Foreground notification enabled")
            if (!NotificationPermissionHelper.canShowNotifications(appContext)) {
                logger.warn("Foreground notification requested, but notification permission/settings are not enabled yet")
            } else if (isSyncEnabled()) {
                ForegroundSyncService.sync(appContext)
            }
        } else {
            logger.info("Foreground notification disabled")
            ForegroundSyncService.stop(appContext)
        }
    }

    fun setAutoScreenshotSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SCREENSHOT_SYNC_ENABLED, enabled).apply()
        _uiState.value = _uiState.value.copy(autoScreenshotSyncEnabled = enabled)
        logger.info("Auto screenshot sync set to $enabled")
        updateScreenshotObserver()
    }

    fun setPrivacyPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVACY_PAUSED, paused).apply()
        _uiState.value = _uiState.value.copy(privacyPaused = paused)
        logger.warn(if (paused) "Privacy pause enabled; outbound sync paused" else "Privacy pause disabled; outbound sync resumed")
    }

    fun reconnect() {
        logger.info("Manual reconnect requested")
        ensureConnected(force = true)
    }

    fun scanSavedDevices() {
        scope.launch {
            scanSavedPeers(connectSelected = false)
        }
    }

    fun selectSavedDevice(deviceId: String) {
        trustedDeviceRepository.selectPeer(deviceId)
        lanClient.disconnect()
        refreshPairedState()
        ensureConnected(force = true)
    }

    fun syncCurrentClipboardNow(trigger: String = "manual-button") {
        if (!isSyncEnabled()) {
            logger.warn("Manual clipboard sync skipped because sync is disabled")
            return
        }

        scope.launch {
            logger.info("Manual clipboard sync requested from $trigger")
            ensureConnected(force = true)
            syncCurrentClipboardWithRetryWindow(
                trigger = trigger,
                forceResend = true,
                logUnavailableReason = true
            )
        }
    }

    fun syncSmartNow(trigger: String = "smart-sync") {
        if (!isSyncEnabled() || isPrivacyPaused()) {
            logger.warn("Smart sync skipped because sync is disabled or privacy paused")
            return
        }

        scope.launch {
            ensureConnected(force = true)
            val screenshotSynced = syncLatestScreenshotToClipboardIfAvailable("$trigger-screenshot")
            if (!screenshotSynced) {
                syncCurrentClipboardWithRetryWindow(
                    trigger = "$trigger-clipboard",
                    forceResend = true,
                    logUnavailableReason = true
                )
            }
        }
    }

    fun syncLatestScreenshotNow(trigger: String = "manual-screenshot") {
        if (!isSyncEnabled() || isPrivacyPaused()) {
            logger.warn("Screenshot sync skipped because sync is disabled or privacy paused")
            return
        }

        scope.launch {
            ensureConnected(force = true)
            syncLatestScreenshotIfNeeded(trigger = trigger, force = true)
        }
    }

    fun resendRecent(eventId: String) {
        val pending = pendingByEventId[eventId]
        if (pending == null) {
            logger.warn("Only queued or pending items can be resent in this preview build")
            return
        }
        outboundQueue.addLast(pending)
        logger.info("Requeued clipboard event $eventId")
        flushQueue()
    }

    fun copyDebugReport() {
        val report = buildString {
            appendLine("Clipboard Sync Android Debug Report")
            appendLine("Connection: ${uiState.value.connectionLabel}")
            appendLine("Selected device: ${uiState.value.pairedDeviceLabel}")
            appendLine("Transport: ${uiState.value.transportKind}")
            appendLine("Saved devices: ${uiState.value.savedDevices.joinToString { "${it.displayName} ${it.endpoint}" }}")
            appendLine("Recent logs:")
            uiState.value.logs.take(30).forEach {
                appendLine("${it.timestampUtc} [${it.level}] ${it.message}")
            }
        }
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Clipboard Sync debug report", report))
        logger.info("Copied debug report to clipboard")
    }

    fun clearLogs() {
        logger.clear()
    }

    fun updateManualPairingPayload(payload: String) {
        _uiState.value = _uiState.value.copy(manualPairingPayload = payload)
    }

    fun pair(encodedPayload: String) {
        runCatching {
            PairingCodeCodec.decode(encodedPayload)
        }.onSuccess { payload ->
            trustedDeviceRepository.savePairingPayload(payload)
            _uiState.value = _uiState.value.copy(manualPairingPayload = "")
            refreshPairedState()
            ensureConnected(force = true)
        }.onFailure {
            logger.error("Invalid pairing payload", it)
        }
    }

    fun onClipboardChanged() {
        if (!uiForeground || !isSyncEnabled() || isPrivacyPaused()) {
            return
        }
        scope.launch {
            syncCurrentClipboardIfNeeded("clipboard-change")
        }
    }

    private fun isSyncEnabled(): Boolean = prefs.getBoolean(KEY_SYNC_ENABLED, true)

    private fun isNotificationEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)

    private fun isAutoScreenshotSyncEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SCREENSHOT_SYNC_ENABLED, true)

    private fun isPrivacyPaused(): Boolean = prefs.getBoolean(KEY_PRIVACY_PAUSED, false)

    private fun refreshPairedState() {
        val peer = trustedDeviceRepository.getTrustedPeer()
        _uiState.value = _uiState.value.copy(
            pairedDeviceLabel = peer?.displayName ?: "Not paired",
            syncEnabled = isSyncEnabled(),
            notificationEnabled = isNotificationEnabled(),
            autoScreenshotSyncEnabled = isAutoScreenshotSyncEnabled(),
            privacyPaused = isPrivacyPaused(),
            savedDevices = buildSavedDeviceModels()
        )
    }

    private fun ensureConnected(force: Boolean = false) {
        val peer = trustedDeviceRepository.getTrustedPeer() ?: run {
            logger.warn("Connect skipped because no trusted peer is configured")
            return
        }
        if (!force && lanClient.state.value in listOf(LanConnectionState.CONNECTING, LanConnectionState.CONNECTED, LanConnectionState.READY)) {
            return
        }
        val nsdDiscovered = nsdPeerDiscovery.knownHostFor(peer.serviceName)
        if (nsdDiscovered != null) {
            val effectivePeer = peer.copy(host = nsdDiscovered.host, port = nsdDiscovered.port)
            trustedDeviceRepository.updateEndpoint(effectivePeer)
            lanClient.connect(effectivePeer, localDeviceIdentityStore.deviceId)
            return
        }

        if (force) {
            lanClient.connect(peer, localDeviceIdentityStore.deviceId)
        } else {
            logger.info("Searching for saved peer ${peer.displayName} before autoconnect")
        }
        discoverAndConnectSavedPeer(peer, force)
    }

    private fun discoverAndConnectSavedPeer(peer: com.clipboardsync.android.pairing.TrustedPeer, force: Boolean) {
        if (discoveryConnectJob?.isActive == true) return
        discoveryConnectJob = scope.launch {
            val discovered = lanPeerDiscovery.discoverTrustedPeer(peer) ?: return@launch
            trustedDeviceRepository.updateEndpoint(discovered)
            val shouldReconnect = force ||
                lanClient.state.value == LanConnectionState.FAILED ||
                lanClient.state.value == LanConnectionState.DISCONNECTED ||
                discovered.host != peer.host ||
                discovered.port != peer.port
            if (shouldReconnect && isSyncEnabled()) {
                logger.info("Autoconnecting to saved peer ${discovered.displayName} at ${discovered.host}:${discovered.port}")
                lanClient.connect(discovered, localDeviceIdentityStore.deviceId)
            }
        }
    }

    private suspend fun scanSavedPeers(connectSelected: Boolean) {
        val peers = trustedDeviceRepository.getTrustedPeers()
        if (peers.isEmpty()) {
            logger.warn("No saved devices to scan")
            refreshPairedState()
            return
        }

        val discovered = peers.mapNotNull { lanPeerDiscovery.discoverTrustedPeer(it, timeoutMillis = 700) }
        availablePeerIds = discovered.map { it.deviceId }.toSet()
        discovered.forEach { trustedDeviceRepository.updateEndpoint(it) }
        refreshPairedState()

        if (connectSelected) {
            trustedDeviceRepository.getTrustedPeer()?.let { selected ->
                discovered.firstOrNull { it.deviceId == selected.deviceId }?.let {
                    lanClient.connect(it, localDeviceIdentityStore.deviceId)
                }
            }
        }
    }

    private fun enqueueOutbound(normalized: NormalizedClipboard) {
        val pending = PendingEvent(normalized)
        outboundQueue.addLast(pending)
        pendingByEventId[normalized.event.eventId] = pending
        loopGuard.rememberSeenEvent(normalized.event.eventId)
    }

    private fun flushQueue() {
        if (lanClient.state.value != LanConnectionState.READY) return
        while (outboundQueue.isNotEmpty()) {
            val pending = outboundQueue.removeFirst()
            sendPending(pending)
        }
    }

    private fun sendPending(pending: PendingEvent) {
        pending.attempts += 1
        pending.lastAttemptUtc = Instant.now()
        logger.info("Sending clipboard event ${pending.normalized.event.eventId}, attempt ${pending.attempts}")
        lanClient.sendClipboardEvent(
            pending.normalized.event.copy(transferState = TransferState.AWAITING_ACK),
            pending.normalized.imageBytes
        )
        scheduleAckTimeout(pending.normalized.event.eventId)
    }

    private fun scheduleAckTimeout(eventId: String) {
        scope.launch {
            delay(5_000)
            val pending = pendingByEventId[eventId] ?: return@launch
            if (pending.attempts >= 3) {
                logger.warn("Event $eventId failed after retries")
                updateRecentStatus(eventId, TransferState.FAILED, "Failed")
                pendingByEventId.remove(eventId)
                return@launch
            }
            logger.warn("Retrying event $eventId after ack timeout")
            outboundQueue.addLast(pending)
            flushQueue()
        }
    }

    private fun handleLanStateChange(state: LanConnectionState) {
        val transport = transportSelector.select(
            lanReady = state == LanConnectionState.READY,
            bleAvailable = false
        )
        val label = when (state) {
            LanConnectionState.DISCONNECTED -> "Disconnected"
            LanConnectionState.CONNECTING -> "Connecting"
            LanConnectionState.CONNECTED -> "Authenticating"
            LanConnectionState.READY -> "Connected"
            LanConnectionState.FAILED -> "Connection failed"
        }
        _uiState.value = _uiState.value.copy(
            connectionLabel = label,
            transportKind = transport,
            savedDevices = buildSavedDeviceModels()
        )
        if (state == LanConnectionState.READY) {
            reconnectJob?.cancel()
            flushQueue()
        } else if (state == LanConnectionState.FAILED && isSyncEnabled()) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(3_000)
            ensureConnected(force = true)
        }
    }

    private suspend fun handleIncomingEnvelope(envelope: ProtocolEnvelope) {
        when (envelope.type) {
            "auth_challenge" -> handleAuthChallenge(envelope)
            "peer_status" -> logger.info("Peer reported status ${envelope.status}")
            "clipboard_offer" -> handleClipboardOffer(envelope.event)
            "transfer_begin" -> handleTransferBegin(envelope)
            "transfer_chunk" -> handleTransferChunk(envelope)
            "transfer_complete" -> handleTransferComplete(envelope)
            "clipboard_ack" -> handleAck(envelope)
            "clipboard_reject" -> logger.warn("Clipboard event rejected: ${envelope.reason}")
            "ping" -> lanClient.sendEnvelope(ProtocolEnvelope(type = "pong"))
        }
    }

    private fun handleAuthChallenge(envelope: ProtocolEnvelope) {
        val peer = trustedDeviceRepository.getTrustedPeer() ?: return
        val challenge = envelope.challenge ?: return
        val sessionId = envelope.sessionId ?: CryptoUtils.uuidV7()
        val response = CryptoUtils.hmacSha256Base64(
            secret = peer.pairingCode,
            message = "$challenge:$sessionId:${localDeviceIdentityStore.deviceId}"
        )
        lanClient.sendEnvelope(
            ProtocolEnvelope(
                type = "auth_response",
                sessionId = sessionId,
                deviceId = localDeviceIdentityStore.deviceId,
                response = response
            )
        )
    }

    private suspend fun handleClipboardOffer(event: ClipboardEvent?) {
        val clipboardEvent = event ?: return
        if (loopGuard.hasSeenEvent(clipboardEvent.eventId)) {
            logger.info("Ignoring already seen event ${clipboardEvent.eventId}")
            return
        }
        when (clipboardEvent.contentType) {
            ContentType.TEXT, ContentType.URL -> applyRemoteEvent(clipboardEvent, imageBytes = null)
            ContentType.IMAGE -> {
                val transferId = clipboardEvent.image?.transferId ?: clipboardEvent.eventId
                incomingTransfers[transferId] = IncomingTransfer(
                    event = clipboardEvent,
                    transferId = transferId
                )
                logger.info("Prepared incoming image transfer $transferId")
            }

            ContentType.MIXED_UNSUPPORTED -> {
                lanClient.sendEnvelope(
                    ProtocolEnvelope(
                        type = "clipboard_reject",
                        event = clipboardEvent,
                        reason = "Unsupported content type"
                    )
                )
            }
        }
    }

    private fun handleTransferBegin(envelope: ProtocolEnvelope) {
        val descriptor = envelope.transfer ?: return
        if (incomingTransfers.containsKey(descriptor.transferId)) {
            logger.info("Incoming transfer ${descriptor.transferId} started")
        }
    }

    private fun handleTransferChunk(envelope: ProtocolEnvelope) {
        val chunk = envelope.chunk ?: return
        val incoming = incomingTransfers[chunk.transferId] ?: return
        incoming.output.write(Base64.decode(chunk.base64Payload, Base64.NO_WRAP))
    }

    private suspend fun handleTransferComplete(envelope: ProtocolEnvelope) {
        val descriptor = envelope.transfer ?: return
        val incoming = incomingTransfers.remove(descriptor.transferId) ?: return
        val bytes = incoming.output.toByteArray()
        val checksum = CryptoUtils.sha256Hex(bytes)
        if (!checksum.equals(descriptor.checksumSha256, ignoreCase = true)) {
            logger.warn("Checksum mismatch for transfer ${descriptor.transferId}")
            return
        }
        applyRemoteEvent(incoming.event, bytes)
    }

    private fun handleAck(envelope: ProtocolEnvelope) {
        val eventId = envelope.event?.eventId ?: return
        pendingByEventId.remove(eventId)
        updateRecentStatus(eventId, TransferState.ACKED, envelope.status ?: "Acked")
        logger.info("Event $eventId acked")
    }

    private suspend fun applyRemoteEvent(event: ClipboardEvent, imageBytes: ByteArray?) {
        if (lastLocalClipboardAt.isAfter(Instant.now().minus(CONFLICT_WINDOW_MILLIS, ChronoUnit.MILLIS))) {
            logger.warn("Deferred remote event ${event.eventId} because local clipboard changed recently")
            addRecent(
                NormalizedClipboard(
                    event = event.copy(transferState = TransferState.DEFERRED),
                    imageBytes = imageBytes,
                    previewText = event.textPayload ?: "Deferred image",
                    fromRemote = true
                ),
                "Windows -> Android",
                "Deferred"
            )
            lanClient.sendEnvelope(
                ProtocolEnvelope(
                    type = "clipboard_ack",
                    event = event,
                    status = "deferred"
                )
            )
            return
        }

        val applied = clipboardApplyUseCase.applyRemoteClip(event, imageBytes)
        if (applied) {
            lastQueuedLocalHash = event.contentHashSha256
            loopGuard.markRemoteApplied(event.contentHashSha256)
            loopGuard.rememberSeenEvent(event.eventId)
            addRecent(
                NormalizedClipboard(
                    event = event.copy(transferState = TransferState.ACKED),
                    imageBytes = imageBytes,
                    previewText = event.textPayload ?: "Image ${event.image?.width}x${event.image?.height}",
                    fromRemote = true
                ),
                "Windows -> Android",
                "Applied"
            )
            lanClient.sendEnvelope(
                ProtocolEnvelope(
                    type = "clipboard_ack",
                    event = event,
                    status = "applied"
                )
            )
        }
    }

    private suspend fun syncCurrentClipboardIfNeeded(
        trigger: String,
        forceResend: Boolean = false,
        logUnavailableReason: Boolean = false,
        logSnapshot: Boolean = false
    ) {
        if (isPrivacyPaused()) {
            logger.warn("Skipped clipboard sync because privacy pause is enabled")
            return
        }

        val normalized = runCatching {
            clipboardNormalizer.normalizeCurrentClipboard(logSnapshot = logSnapshot)
        }.getOrElse {
            logger.error("Failed to read clipboard for $trigger", it)
            return
        }
        if (normalized == null) {
            if (logUnavailableReason) {
                val message = if (uiForeground) {
                    "Manual sync could not find a supported clipboard item to send"
                } else {
                    "Manual sync could not read clipboard while the app was hidden. Android may block background clipboard reads."
                }
                logger.warn(message)
            }
            return
        }
        val hash = normalized.event.contentHashSha256
        if (loopGuard.shouldSuppressLocal(hash)) {
            logger.info("Suppressed clipboard echo for ${normalized.event.eventId}")
            return
        }
        if (!forceResend && lastQueuedLocalHash == hash) {
            logger.info("Skipped unchanged Android clipboard on $trigger")
            return
        }

        enqueueOutbound(normalized)
        lastQueuedLocalHash = hash
        lastLocalClipboardAt = Instant.now()
        addRecent(normalized, "Android -> Windows", "Queued")
        logger.info("Queued Android clipboard event ${normalized.event.eventId} from $trigger")
        flushQueue()
    }

    private suspend fun syncCurrentClipboardWithRetryWindow(
        trigger: String,
        forceResend: Boolean = false,
        logUnavailableReason: Boolean = false
    ) {
        val initial = readCurrentClipboard(
            trigger = trigger,
            logUnavailableReason = logUnavailableReason,
            logSnapshot = forceResend || trigger == "foreground-resume"
        ) ?: return

        var chosen = initial
        if (chosen.event.contentHashSha256 == lastQueuedLocalHash) {
            logger.info("Clipboard matches the last queued item on $trigger; waiting briefly for a fresher clipboard value")
            repeat(CLIPBOARD_REFRESH_RETRY_COUNT) { attempt ->
                delay(CLIPBOARD_REFRESH_RETRY_DELAY_MS)
                val retried = readCurrentClipboard(
                    trigger = "$trigger-retry-${attempt + 1}",
                    logUnavailableReason = false,
                    logSnapshot = attempt == CLIPBOARD_REFRESH_RETRY_COUNT - 1
                ) ?: return@repeat

                if (retried.event.contentHashSha256 != chosen.event.contentHashSha256 ||
                    retried.event.contentType != chosen.event.contentType ||
                    retried.previewUri != chosen.previewUri
                ) {
                    chosen = retried
                    logger.info("Detected a newer clipboard candidate on retry ${attempt + 1} for $trigger")
                    return@repeat
                }
            }
        }

        if (forceResend && chosen.event.contentHashSha256 == lastQueuedLocalHash) {
            logger.warn("Manual sync skipped because the clipboard still matches the previously queued item after retries")
            return
        }

        syncNormalizedIfNeeded(chosen, trigger, forceResend)
    }

    private suspend fun readCurrentClipboard(
        trigger: String,
        logUnavailableReason: Boolean,
        logSnapshot: Boolean
    ): NormalizedClipboard? {
        val normalized = runCatching {
            clipboardNormalizer.normalizeCurrentClipboard(logSnapshot = logSnapshot)
        }.getOrElse {
            logger.error("Failed to read clipboard for $trigger", it)
            return null
        }
        if (normalized == null && logUnavailableReason) {
            val message = if (uiForeground) {
                "Manual sync could not find a supported clipboard item to send"
            } else {
                "Manual sync could not read clipboard while the app was hidden. Android may block background clipboard reads."
            }
            logger.warn(message)
        }
        return normalized
    }

    private fun syncNormalizedIfNeeded(
        normalized: NormalizedClipboard,
        trigger: String,
        forceResend: Boolean
    ) {
        if (isPrivacyPaused()) {
            logger.warn("Skipped outbound sync because privacy pause is enabled")
            return
        }

        val hash = normalized.event.contentHashSha256
        if (loopGuard.shouldSuppressLocal(hash)) {
            logger.info("Suppressed clipboard echo for ${normalized.event.eventId}")
            return
        }
        if (!forceResend && lastQueuedLocalHash == hash) {
            logger.info("Skipped unchanged Android clipboard on $trigger")
            return
        }

        enqueueOutbound(normalized)
        lastQueuedLocalHash = hash
        lastLocalClipboardAt = Instant.now()
        addRecent(normalized, "Android -> Windows", "Queued")
        logger.info("Queued Android clipboard event ${normalized.event.eventId} from $trigger")
        flushQueue()
    }

    private fun updateScreenshotObserver() {
        screenshotRepository.stopObserving(screenshotObserver)
        screenshotObserver = null

        if (!isAutoScreenshotSyncEnabled() || !isSyncEnabled()) {
            return
        }

        screenshotObserver = screenshotRepository.observe(scope) {
            if (!isPrivacyPaused()) {
                scope.launch {
                    syncLatestScreenshotIfNeeded("media-observer")
                }
            }
        }
    }

    private suspend fun syncLatestScreenshotIfNeeded(trigger: String, force: Boolean = false) {
        val screenshot = screenshotRepository.latestScreenshot() ?: run {
            logger.warn("No recent screenshot found for $trigger")
            return
        }
        if (!force && screenshot.id == lastSyncedScreenshotId) {
            logger.info("Skipped already synced screenshot ${screenshot.displayName}")
            return
        }

        val cached = imageCacheStore.cacheClipboardImage(screenshot.uri) ?: run {
            logger.warn("Failed to cache screenshot ${screenshot.uri}")
            return
        }
        val (image, bytes) = cached
        val sourceDeviceId = localDeviceIdentityStore.deviceId
        val normalized = NormalizedClipboard(
            event = ClipboardEvent(
                eventId = CryptoUtils.uuidV7(),
                sourceDeviceId = sourceDeviceId,
                contentType = ContentType.IMAGE,
                mimeType = "image/png",
                payloadSizeBytes = image.byteSize,
                contentHashSha256 = image.checksumSha256,
                dedupeKey = "$sourceDeviceId:${image.checksumSha256}",
                transferState = TransferState.QUEUED,
                image = ImageMetadata(
                    width = image.width,
                    height = image.height,
                    byteSize = image.byteSize,
                    checksumSha256 = image.checksumSha256,
                    encoding = "png",
                    transferId = CryptoUtils.uuidV7()
                )
            ),
            imageBytes = bytes,
            previewText = "Screenshot ${image.width}x${image.height}",
            previewUri = image.uri.toString()
        )

        lastSyncedScreenshotId = screenshot.id
        prefs.edit().putLong(KEY_LAST_SCREENSHOT_ID, screenshot.id).apply()
        logger.info("Queued latest screenshot from $trigger: ${screenshot.displayName}")
        syncNormalizedIfNeeded(normalized, trigger, forceResend = true)
    }

    private suspend fun syncLatestScreenshotToClipboardIfAvailable(trigger: String): Boolean {
        val screenshot = screenshotRepository.latestScreenshot(maxAgeMillis = SMART_SYNC_SCREENSHOT_MAX_AGE_MILLIS) ?: return false
        val cached = imageCacheStore.cacheClipboardImage(screenshot.uri) ?: run {
            logger.warn("Failed to cache screenshot ${screenshot.uri} for smart sync")
            return false
        }
        val (image, bytes) = cached
        clipboardManager.setPrimaryClip(
            ClipData.newUri(
                appContext.contentResolver,
                "Latest screenshot",
                image.uri
            )
        )

        val sourceDeviceId = localDeviceIdentityStore.deviceId
        val normalized = NormalizedClipboard(
            event = ClipboardEvent(
                eventId = CryptoUtils.uuidV7(),
                sourceDeviceId = sourceDeviceId,
                contentType = ContentType.IMAGE,
                mimeType = "image/png",
                payloadSizeBytes = image.byteSize,
                contentHashSha256 = image.checksumSha256,
                dedupeKey = "$sourceDeviceId:${image.checksumSha256}",
                transferState = TransferState.QUEUED,
                image = ImageMetadata(
                    width = image.width,
                    height = image.height,
                    byteSize = image.byteSize,
                    checksumSha256 = image.checksumSha256,
                    encoding = "png",
                    transferId = CryptoUtils.uuidV7()
                )
            ),
            imageBytes = bytes,
            previewText = "Screenshot ${image.width}x${image.height}",
            previewUri = image.uri.toString()
        )

        lastSyncedScreenshotId = screenshot.id
        prefs.edit().putLong(KEY_LAST_SCREENSHOT_ID, screenshot.id).apply()
        logger.info("Copied latest screenshot to clipboard and queued it from $trigger: ${screenshot.displayName}")
        syncNormalizedIfNeeded(normalized, trigger, forceResend = true)
        return true
    }

    private fun addRecent(normalized: NormalizedClipboard, direction: String, status: String) {
        val model = RecentItemUiModel(
            eventId = normalized.event.eventId,
            contentType = normalized.event.contentType,
            previewText = normalized.previewText,
            previewUri = normalized.previewUri,
            payloadSizeBytes = normalized.event.payloadSizeBytes,
            syncedAtUtc = Instant.now().toString(),
            directionLabel = direction,
            transferState = normalized.event.transferState,
            status = status
        )
        val next = (listOf(model) + _uiState.value.recentItems).take(20)
        _uiState.value = _uiState.value.copy(
            recentItems = next,
            lastSyncedItem = next.firstOrNull()
        )
    }

    private fun updateRecentStatus(eventId: String, transferState: TransferState, status: String) {
        val updated = _uiState.value.recentItems.map {
            if (it.eventId == eventId) it.copy(transferState = transferState, status = status) else it
        }
        _uiState.value = _uiState.value.copy(
            recentItems = updated,
            lastSyncedItem = updated.firstOrNull()
        )
    }

    private companion object {
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_AUTO_SCREENSHOT_SYNC_ENABLED = "auto_screenshot_sync_enabled"
        private const val KEY_PRIVACY_PAUSED = "privacy_paused"
        private const val KEY_LAST_SCREENSHOT_ID = "last_screenshot_id"
        private const val CONFLICT_WINDOW_MILLIS = 1_500L
        private const val CLIPBOARD_REFRESH_RETRY_COUNT = 4
        private const val CLIPBOARD_REFRESH_RETRY_DELAY_MS = 300L
        private const val SMART_SYNC_SCREENSHOT_MAX_AGE_MILLIS = 5 * 60 * 1000L
    }

    private fun buildSavedDeviceModels(): List<SavedDeviceUiModel> {
        val selected = trustedDeviceRepository.getTrustedPeer()
        val connected = lanClient.state.value == LanConnectionState.READY
        return trustedDeviceRepository.getTrustedPeers().map { peer ->
            SavedDeviceUiModel(
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                endpoint = "${peer.host}:${peer.port}",
                selected = peer.deviceId == selected?.deviceId,
                available = peer.deviceId in availablePeerIds || (peer.deviceId == selected?.deviceId && connected),
                connected = peer.deviceId == selected?.deviceId && connected
            )
        }.sortedWith(compareByDescending<SavedDeviceUiModel> { it.connected }.thenByDescending { it.available }.thenBy { it.displayName })
    }
}
