package com.clipboardsync.android.service

import android.app.Application
import android.content.Context
import android.util.Base64
import com.clipboardsync.android.clipboard.ClipboardApplyUseCase
import com.clipboardsync.android.clipboard.ClipboardNormalizer
import com.clipboardsync.android.clipboard.ImageCacheStore
import com.clipboardsync.android.clipboard.LoopGuard
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

data class SyncUiState(
    val syncEnabled: Boolean = true,
    val notificationEnabled: Boolean = true,
    val pairedDeviceLabel: String = "Not paired",
    val connectionLabel: String = "Disconnected",
    val transportKind: TransportKind = TransportKind.NONE,
    val lastSyncedItem: RecentItemUiModel? = null,
    val recentItems: List<RecentItemUiModel> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val manualPairingPayload: String = "",
    val guidance: String = "Android clipboard reads are only reliable while this app is visible or foreground-active. Copy in another app, then return here to push the current clipboard to Windows. Keep the notification enabled if you want the pinned Sync now shortcut and background inbound sync."
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
    private val clipboardNormalizer: ClipboardNormalizer,
    private val clipboardApplyUseCase: ClipboardApplyUseCase,
    private val lanClient: LanClient,
    private val nsdPeerDiscovery: NsdPeerDiscovery,
    private val transportSelector: TransportSelector,
    private val loopGuard: LoopGuard
) {
    private val appContext = app.applicationContext
    private val prefs = app.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow(
        SyncUiState(
            syncEnabled = isSyncEnabled(),
            notificationEnabled = isNotificationEnabled()
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

    init {
        imageCacheStore.cleanup()
        refreshPairedState()
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
                syncCurrentClipboardIfNeeded("foreground-resume")
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

    fun reconnect() {
        logger.info("Manual reconnect requested")
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
            syncCurrentClipboardIfNeeded(
                trigger = trigger,
                forceResend = true,
                logUnavailableReason = true
            )
        }
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
        if (!uiForeground || !isSyncEnabled()) {
            return
        }
        scope.launch {
            syncCurrentClipboardIfNeeded("clipboard-change")
        }
    }

    private fun isSyncEnabled(): Boolean = prefs.getBoolean(KEY_SYNC_ENABLED, true)

    private fun isNotificationEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)

    private fun refreshPairedState() {
        val peer = trustedDeviceRepository.getTrustedPeer()
        _uiState.value = _uiState.value.copy(
            pairedDeviceLabel = peer?.displayName ?: "Not paired",
            syncEnabled = isSyncEnabled(),
            notificationEnabled = isNotificationEnabled()
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
        val discovered = nsdPeerDiscovery.knownHostFor(peer.serviceName)
        val effectivePeer = if (discovered != null) {
            peer.copy(host = discovered.host, port = discovered.port)
        } else {
            peer
        }
        lanClient.connect(effectivePeer, localDeviceIdentityStore.deviceId)
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
            transportKind = transport
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
        logUnavailableReason: Boolean = false
    ) {
        val normalized = runCatching {
            clipboardNormalizer.normalizeCurrentClipboard()
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
        private const val CONFLICT_WINDOW_MILLIS = 1_500L
    }
}
