package com.clipboardsync.android.service

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.util.Base64
import com.clipboardsync.android.NotificationSyncActivity
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
import com.clipboardsync.android.pairing.TrustedPeer
import com.clipboardsync.android.protocol.ClipboardEvent
import com.clipboardsync.android.protocol.ContentType
import com.clipboardsync.android.protocol.NearbyHostUiModel
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

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

enum class SyncMode(val label: String) {
    MIRROR("Mirror"),
    MANUAL("Manual"),
    ASK("Ask"),
    RECEIVE_ONLY("Receive only"),
    SEND_ONLY("Send only")
}

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
    val nearbyHosts: List<NearbyHostUiModel> = emptyList(),
    val nearbyScanInProgress: Boolean = false,
    val manualPairingPayload: String = "",
    val autoScreenshotSyncEnabled: Boolean = false,
    val privacyPaused: Boolean = false,
    val syncMode: SyncMode = SyncMode.MIRROR,
    val allowTextSync: Boolean = true,
    val allowUrlSync: Boolean = true,
    val allowImageSync: Boolean = true,
    val maxImageSizeMb: Int = 25,
    val queuedOutboundCount: Int = 0,
    val deferredIncomingCount: Int = 0,
    val guidance: String = "TEXT/URL/image clipboard auto-sync when mirroring. Sync now sends the current clipboard (not gallery screenshots). Keep notification on; if HyperOS blocks background clipboard, tap Sync."
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
            privacyPaused = isPrivacyPaused(),
            syncMode = syncMode(),
            allowTextSync = allowTextSync(),
            allowUrlSync = allowUrlSync(),
            allowImageSync = allowImageSync(),
            maxImageSizeMb = maxImageSizeMb()
        )
    )
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val outboundQueue = ArrayDeque<PendingEvent>()
    private val pendingByEventId = ConcurrentHashMap<String, PendingEvent>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()
    private val recentPayloads = ConcurrentHashMap<String, NormalizedClipboard>()
    private val deferredIncoming = ConcurrentHashMap<String, NormalizedClipboard>()
    private var uiForeground = false
    /** True while NotificationSyncActivity (or similar) holds clipboard focus. */
    private var clipboardAccessSession = false
    private var serviceActive = false
    private var lastLocalClipboardAt = Instant.EPOCH
    private var lastRemoteAppliedAt = Instant.EPOCH
    private var lastImageQueuedAt = Instant.EPOCH
    private var lastQueuedLocalHash: String? = null
    private var reconnectJob: Job? = null
    private var discoveryConnectJob: Job? = null
    private var nearbyScanJob: Job? = null
    private var flushJob: Job? = null
    private var clipboardChangeJob: Job? = null
    private var clipboardPollJob: Job? = null
    private var availablePeerIds: Set<String> = emptySet()
    private var screenshotObserver: ContentObserver? = null
    private var lastSyncedScreenshotId: Long = prefs.getLong(KEY_LAST_SCREENSHOT_ID, -1L)
    private var lastClipboardActivityLaunchAt = 0L

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
            // Must use collect (not collectLatest): image sync is many envelopes
            // (offer + begin + N chunks + complete). collectLatest cancels in-flight
            // handlers and drops chunks, so images never apply / never ack.
            lanClient.incoming.collect { envelope ->
                handleIncomingEnvelope(envelope)
            }
        }
        nsdPeerDiscovery.start()
        startClipboardPolling()
    }

    fun onUiForegroundChanged(active: Boolean) {
        uiForeground = active
        logger.info("UI foreground changed: $active")
        if (active && isSyncEnabled()) {
            ensureConnected()
            startClipboardPolling()
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
            startClipboardPolling()
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

    fun setSyncMode(mode: SyncMode) {
        prefs.edit().putString(KEY_SYNC_MODE, mode.name).apply()
        _uiState.value = _uiState.value.copy(syncMode = mode)
        logger.info("Sync mode set to ${mode.label}")
    }

    fun setAllowTextSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_TEXT_SYNC, enabled).apply()
        refreshPolicyState()
    }

    fun setAllowUrlSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_URL_SYNC, enabled).apply()
        refreshPolicyState()
    }

    fun setAllowImageSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_IMAGE_SYNC, enabled).apply()
        refreshPolicyState()
    }

    fun setMaxImageSizeMb(value: Int) {
        prefs.edit().putInt(KEY_MAX_IMAGE_SIZE_MB, value.coerceIn(1, 200)).apply()
        refreshPolicyState()
    }

    fun reconnect() {
        logger.info("Manual reconnect requested")
        ensureConnected(force = true)
    }

    fun scanSavedDevices() {
        scope.launch {
            scanSavedPeers(connectFirstReachable = true)
        }
    }

    fun findNearbyHosts() {
        if (nearbyScanJob?.isActive == true) return
        nearbyScanJob = scope.launch {
            _uiState.value = _uiState.value.copy(nearbyScanInProgress = true, nearbyHosts = emptyList())
            val hosts = runCatching {
                lanPeerDiscovery.discoverPairableHosts()
            }.onFailure {
                logger.error("Nearby host scan failed", it)
            }.getOrDefault(emptyList())
            val savedIds = trustedDeviceRepository.getTrustedPeers().map { it.deviceId }.toSet()
            val models = hosts
                .filterNot { it.deviceId in savedIds }
                .map {
                    NearbyHostUiModel(
                        deviceId = it.deviceId,
                        displayName = it.displayName,
                        endpoint = "${it.host}:${it.port}",
                        encodedPayload = PairingCodeCodec.encode(it)
                    )
                }
            _uiState.value = _uiState.value.copy(
                nearbyHosts = models,
                nearbyScanInProgress = false
            )
            logger.info("Nearby scan found ${models.size} pairable host(s)")
        }
    }

    fun pairNearbyHost(encodedPayload: String) {
        pair(encodedPayload)
        _uiState.value = _uiState.value.copy(nearbyHosts = emptyList())
    }

    fun selectSavedDevice(deviceId: String) {
        trustedDeviceRepository.selectPeer(deviceId)
        lanClient.disconnect()
        refreshPairedState()
        ensureConnected(force = true)
    }

    fun syncCurrentClipboardNow(trigger: String = "manual-button") {
        scope.launch {
            syncCurrentClipboardNowAwait(trigger)
        }
    }

    suspend fun syncCurrentClipboardNowAwait(trigger: String = "manual-button"): Boolean {
        if (!canSendOutbound("Manual clipboard sync")) {
            return false
        }
        logger.info("Manual clipboard sync requested from $trigger")
        // Prefer a stable socket; only force-reconnect when not already ready.
        if (lanClient.state.value != LanConnectionState.READY) {
            ensureConnected(force = true)
        } else {
            ensureConnected(force = false)
        }

        repeat(3) { attempt ->
            val synced = syncCurrentClipboardWithRetryWindow(
                trigger = if (attempt == 0) trigger else "$trigger-retry-$attempt",
                forceResend = true,
                logUnavailableReason = attempt == 2
            )
            if (synced) {
                return true
            }
            delay(350)
        }

        // Last-ditch diagnostics so HyperOS clipboard blocks are visible in logcat.
        runCatching { clipboardNormalizer.normalizeCurrentClipboard(logSnapshot = true) }
        logger.warn("Manual clipboard sync failed after retries from $trigger")
        return false
    }

    suspend fun syncSmartNowAwait(trigger: String = "smart-sync"): Boolean {
        // v2: Sync never prefers screenshots. Clipboard only.
        return syncCurrentClipboardNowAwait(trigger)
    }

    suspend fun <T> withClipboardAccessSession(block: suspend () -> T): T {
        clipboardAccessSession = true
        logger.info("Clipboard access session started")
        return try {
            block()
        } finally {
            clipboardAccessSession = false
            logger.info("Clipboard access session ended")
        }
    }

    fun syncSharedClipNow(clip: ClipData, trigger: String = "android-share-sheet") {
        if (!canSendOutbound("Shared clipboard sync")) {
            return
        }

        scope.launch {
            logger.info("Shared clipboard sync requested from $trigger")
            ensureConnected(force = true)
            val normalized = runCatching {
                clipboardNormalizer.normalizeClip(clip)
            }.getOrElse {
                logger.error("Failed to normalize shared clipboard content from $trigger", it)
                return@launch
            }

            if (normalized == null) {
                logger.warn("Shared content from $trigger is unsupported or empty")
                return@launch
            }

            syncNormalizedIfNeeded(normalized, trigger, forceResend = true)
        }
    }

    fun syncSmartNow(trigger: String = "smart-sync") {
        scope.launch {
            syncSmartNowAwait(trigger)
        }
    }

    fun syncLatestScreenshotNow(trigger: String = "manual-screenshot") {
        if (!canSendOutbound("Screenshot sync")) {
            return
        }

        scope.launch {
            ensureConnected(force = true)
            syncLatestScreenshotIfNeeded(trigger = trigger, force = true)
        }
    }

    fun resendRecent(eventId: String) {
        if (!canSendOutbound("Resend")) {
            return
        }

        val pending = pendingByEventId[eventId] ?: recentPayloads[eventId]?.let { PendingEvent(it) }
        if (pending == null) {
            logger.warn("Recent item $eventId is no longer available for resend")
            return
        }
        outboundQueue.addLast(pending)
        pendingByEventId[eventId] = pending
        logger.info("Requeued clipboard event $eventId")
        updateQueueCounts()
        flushQueue()
    }

    fun copyRecentToClipboard(eventId: String) {
        val recent = recentPayloads[eventId] ?: run {
            logger.warn("Recent item $eventId is no longer available to restore")
            return
        }

        scope.launch {
            val applied = clipboardApplyUseCase.applyRemoteClip(recent.event, recent.imageBytes)
            if (applied) {
                lastQueuedLocalHash = recent.event.contentHashSha256
                loopGuard.markRemoteApplied(recent.event.contentHashSha256)
                logger.info("Restored recent item $eventId to Android clipboard")
            }
        }
    }

    fun applyDeferredIncoming(eventId: String) {
        val deferred = deferredIncoming.remove(eventId) ?: run {
            logger.warn("Deferred item $eventId is no longer available")
            updateQueueCounts()
            return
        }

        scope.launch {
            val applied = clipboardApplyUseCase.applyRemoteClip(deferred.event, deferred.imageBytes)
            if (applied) {
                loopGuard.markRemoteApplied(deferred.event.contentHashSha256)
                loopGuard.rememberSeenEvent(deferred.event.eventId)
                updateRecentStatus(eventId, TransferState.ACKED, "Applied")
                logger.info("Applied deferred incoming item $eventId")
            }
            updateQueueCounts()
        }
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
        val hasFocus = uiForeground || clipboardAccessSession
        logger.info(
            "Clipboard change detected (uiForeground=$uiForeground serviceActive=$serviceActive " +
                "accessSession=$clipboardAccessSession mode=${syncMode()})"
        )
        if ((!hasFocus && !serviceActive) || !canMirrorClipboardChange()) {
            logger.info("Clipboard change ignored (not mirroring or inactive)")
            return
        }
        // Claim local ownership immediately so a racing remote offer cannot overwrite
        // the user's fresh copy before we finish reading/sending it.
        if (!lastRemoteAppliedAt.isAfter(Instant.now().minusMillis(400L))) {
            lastLocalClipboardAt = Instant.now()
        }
        clipboardChangeJob?.cancel()
        clipboardChangeJob = scope.launch {
            // Background: try a direct read first (rarely works), then wake a brief
            // transparent activity so HyperOS grants clipboard focus.
            if (!hasFocus && serviceActive) {
                delay(80)
                val backgroundRead = readCurrentClipboard(
                    trigger = "clipboard-change-bg",
                    logUnavailableReason = false,
                    logSnapshot = false
                )
                if (backgroundRead != null &&
                    !loopGuard.shouldSuppressLocal(backgroundRead.event.contentHashSha256) &&
                    backgroundRead.event.contentHashSha256 != lastQueuedLocalHash
                ) {
                    logger.info("Background clipboard read succeeded without activity")
                    syncNormalizedIfNeeded(backgroundRead, "clipboard-change-bg", forceResend = false)
                    return@launch
                }
                launchClipboardOnlySyncActivity()
                return@launch
            }

            delay(CLIPBOARD_CHANGE_DEBOUNCE_MS)
            val normalized = readCurrentClipboard(
                trigger = "clipboard-change",
                logUnavailableReason = true,
                logSnapshot = false
            )
            if (normalized == null) {
                logger.warn("Foreground clipboard change did not produce a sendable item")
                return@launch
            }
            // Only ignore the echo of a clip we ourselves just applied from remote.
            if (loopGuard.shouldSuppressLocal(normalized.event.contentHashSha256) ||
                normalized.event.contentHashSha256 == lastQueuedLocalHash
            ) {
                logger.info("Clipboard change ignored (echo of recent remote/local item)")
                return@launch
            }
            syncNormalizedIfNeeded(normalized, "clipboard-change", forceResend = false)
        }
    }

    fun startClipboardPolling() {
        if (clipboardPollJob?.isActive == true) return
        clipboardPollJob = scope.launch {
            var lastHash: String? = lastQueuedLocalHash
            while (isActive && isSyncEnabled() && canMirrorClipboardChange()) {
                delay(CLIPBOARD_POLL_INTERVAL_MS)
                if (!uiForeground && !serviceActive) continue
                // Background reads are blocked by Android; only poll while UI is open.
                if (!uiForeground) continue
                val normalized = readCurrentClipboard(
                    trigger = "clipboard-poll",
                    logUnavailableReason = false,
                    logSnapshot = false
                ) ?: continue
                val hash = normalized.event.contentHashSha256
                if (hash == lastHash ||
                    hash == lastQueuedLocalHash ||
                    loopGuard.shouldSuppressLocal(hash)
                ) {
                    lastHash = hash
                    continue
                }
                lastHash = hash
                logger.info("Clipboard poll detected new content (${normalized.event.contentType})")
                syncNormalizedIfNeeded(normalized, "clipboard-poll", forceResend = false)
            }
        }
    }

    fun stopClipboardPolling() {
        clipboardPollJob?.cancel()
        clipboardPollJob = null
    }

    private fun isSyncEnabled(): Boolean = prefs.getBoolean(KEY_SYNC_ENABLED, true)

    private fun isNotificationEnabled(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)

    private fun isAutoScreenshotSyncEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO_SCREENSHOT_SYNC_ENABLED, false)

    private fun isPrivacyPaused(): Boolean = prefs.getBoolean(KEY_PRIVACY_PAUSED, false)

    private fun syncMode(): SyncMode {
        val saved = prefs.getString(KEY_SYNC_MODE, SyncMode.MIRROR.name)
        return runCatching { SyncMode.valueOf(saved ?: SyncMode.MIRROR.name) }.getOrDefault(SyncMode.MIRROR)
    }

    private fun allowTextSync(): Boolean = prefs.getBoolean(KEY_ALLOW_TEXT_SYNC, true)

    private fun allowUrlSync(): Boolean = prefs.getBoolean(KEY_ALLOW_URL_SYNC, true)

    private fun allowImageSync(): Boolean = prefs.getBoolean(KEY_ALLOW_IMAGE_SYNC, true)

    private fun maxImageSizeMb(): Int = prefs.getInt(KEY_MAX_IMAGE_SIZE_MB, 25).coerceIn(1, 200)

    private fun refreshPolicyState() {
        _uiState.value = _uiState.value.copy(
            syncMode = syncMode(),
            allowTextSync = allowTextSync(),
            allowUrlSync = allowUrlSync(),
            allowImageSync = allowImageSync(),
            maxImageSizeMb = maxImageSizeMb()
        )
        logger.info("Sync rules updated")
    }

    private fun refreshPairedState() {
        val peer = trustedDeviceRepository.getTrustedPeer()
        _uiState.value = _uiState.value.copy(
            pairedDeviceLabel = peer?.displayName ?: "Not paired",
            syncEnabled = isSyncEnabled(),
            notificationEnabled = isNotificationEnabled(),
            autoScreenshotSyncEnabled = isAutoScreenshotSyncEnabled(),
            privacyPaused = isPrivacyPaused(),
            syncMode = syncMode(),
            allowTextSync = allowTextSync(),
            allowUrlSync = allowUrlSync(),
            allowImageSync = allowImageSync(),
            maxImageSizeMb = maxImageSizeMb(),
            savedDevices = buildSavedDeviceModels()
        )
    }

    private fun ensureConnected(force: Boolean = false) {
        val peers = trustedDeviceRepository.getTrustedPeers()
        if (peers.isEmpty()) {
            logger.warn("Connect skipped because no trusted peer is configured")
            return
        }
        if (!force && lanClient.state.value in listOf(
                LanConnectionState.CONNECTING,
                LanConnectionState.CONNECTED,
                LanConnectionState.READY
            )
        ) {
            return
        }
        if (!isSyncEnabled()) {
            return
        }

        logger.info("Searching ${peers.size} saved peer(s) for autoconnect (force=$force)")
        if (discoveryConnectJob?.isActive == true && !force) {
            return
        }
        discoveryConnectJob?.cancel()
        discoveryConnectJob = scope.launch {
            connectFirstReachablePeer(peers, force)
        }
    }

    private suspend fun connectFirstReachablePeer(peers: List<TrustedPeer>, force: Boolean) {
        val selectedId = trustedDeviceRepository.getTrustedPeer()?.deviceId
        val ordered = peers.sortedWith(
            compareByDescending<TrustedPeer> { it.deviceId == selectedId }
                .thenBy { it.displayName }
        )

        // Prefer NSD hits first for speed.
        for (peer in ordered) {
            val nsd = nsdPeerDiscovery.knownHostFor(peer.serviceName) ?: continue
            val effective = peer.copy(host = nsd.host, port = nsd.port)
            trustedDeviceRepository.updateEndpoint(effective)
            availablePeerIds = availablePeerIds + effective.deviceId
            if (tryConnectPeer(effective, force)) {
                refreshPairedState()
                return
            }
        }

        val discovered = lanPeerDiscovery.discoverTrustedPeers(ordered, timeoutMillis = 1_800)
        availablePeerIds = discovered.map { it.deviceId }.toSet()
        discovered.forEach { trustedDeviceRepository.updateEndpoint(it) }
        refreshPairedState()

        val preferred = discovered.firstOrNull { it.deviceId == selectedId } ?: discovered.firstOrNull()
        if (preferred != null) {
            tryConnectPeer(preferred, force = force)
            return
        }

        if (force) {
            for (peer in ordered) {
                if (tryConnectPeer(peer, force = false) || tryConnectPeer(peer, force = true)) {
                    return
                }
            }
        }

        logger.warn("No reachable saved peers found")
        scheduleReconnect()
    }

    private fun tryConnectPeer(peer: TrustedPeer, force: Boolean): Boolean {
        if (!isSyncEnabled()) return false
        val state = lanClient.state.value
        val selected = trustedDeviceRepository.getTrustedPeer()
        // Never tear down a healthy READY socket just because discovery fired again.
        if (state == LanConnectionState.READY &&
            selected?.deviceId == peer.deviceId &&
            selected.host == peer.host &&
            selected.port == peer.port
        ) {
            return true
        }
        if (!force && state in listOf(LanConnectionState.CONNECTING, LanConnectionState.CONNECTED, LanConnectionState.READY)) {
            return state == LanConnectionState.READY
        }
        if (force && state == LanConnectionState.READY && selected?.deviceId == peer.deviceId) {
            logger.info("Already connected to ${peer.displayName}; skipping reconnect")
            return true
        }
        trustedDeviceRepository.selectPeer(peer.deviceId)
        logger.info("Connecting to saved peer ${peer.displayName} at ${peer.host}:${peer.port}")
        lanClient.connect(peer, localDeviceIdentityStore.deviceId)
        return true
    }

    private suspend fun scanSavedPeers(connectFirstReachable: Boolean) {
        val peers = trustedDeviceRepository.getTrustedPeers()
        if (peers.isEmpty()) {
            logger.warn("No saved devices to scan")
            refreshPairedState()
            return
        }

        val discovered = lanPeerDiscovery.discoverTrustedPeers(peers, timeoutMillis = 1_800)
        availablePeerIds = discovered.map { it.deviceId }.toSet()
        discovered.forEach { trustedDeviceRepository.updateEndpoint(it) }
        refreshPairedState()

        if (connectFirstReachable && discovered.isNotEmpty() && isSyncEnabled()) {
            val selectedId = trustedDeviceRepository.getTrustedPeer()?.deviceId
            val target = discovered.firstOrNull { it.deviceId == selectedId } ?: discovered.first()
            tryConnectPeer(target, force = false)
        }
    }

    private fun enqueueOutbound(normalized: NormalizedClipboard) {
        val pending = PendingEvent(normalized)
        outboundQueue.addLast(pending)
        pendingByEventId[normalized.event.eventId] = pending
        loopGuard.rememberSeenEvent(normalized.event.eventId)
        updateQueueCounts()
    }

    private fun flushQueue() {
        if (lanClient.state.value != LanConnectionState.READY) {
            if (outboundQueue.isNotEmpty() && isSyncEnabled()) {
                ensureConnected()
            }
            return
        }
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (outboundQueue.isNotEmpty() && lanClient.state.value == LanConnectionState.READY) {
                val pending = outboundQueue.removeFirst()
                updateQueueCounts()
                sendPending(pending)
            }
        }
    }

    private suspend fun sendPending(pending: PendingEvent) {
        pending.attempts += 1
        pending.lastAttemptUtc = Instant.now()
        logger.info("Sending clipboard event ${pending.normalized.event.eventId}, attempt ${pending.attempts}")
        val sent = lanClient.sendClipboardEvent(
            pending.normalized.event.copy(transferState = TransferState.AWAITING_ACK),
            pending.normalized.imageBytes
        )
        if (!sent) {
            logger.warn("LAN send was interrupted for ${pending.normalized.event.eventId}; it will retry after reconnect")
            outboundQueue.addFirst(pending)
            updateQueueCounts()
            lanClient.disconnect()
            scheduleReconnect()
            return
        }
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
                updateQueueCounts()
                return@launch
            }
            logger.warn("Retrying event $eventId after ack timeout")
            outboundQueue.addLast(pending)
            updateQueueCounts()
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
        } else if ((state == LanConnectionState.FAILED || state == LanConnectionState.DISCONNECTED) &&
            isSyncEnabled() &&
            (uiForeground || serviceActive) &&
            trustedDeviceRepository.getTrustedPeers().isNotEmpty()
        ) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = 5_000L
            while (isActive &&
                isSyncEnabled() &&
                (uiForeground || serviceActive) &&
                lanClient.state.value != LanConnectionState.READY &&
                trustedDeviceRepository.getTrustedPeers().isNotEmpty()
            ) {
                delay(delayMs)
                if (lanClient.state.value == LanConnectionState.READY) {
                    break
                }
                logger.info("Periodic multi-peer search while disconnected")
                ensureConnected(force = false)
                if (lanClient.state.value != LanConnectionState.READY) {
                    ensureConnected(force = true)
                }
                if (lanClient.state.value == LanConnectionState.READY) {
                    break
                }
                delayMs = min(delayMs + 5_000L, 30_000L)
            }
        }
    }

    private fun launchClipboardOnlySyncActivity() {
        val now = System.currentTimeMillis()
        if (now - lastClipboardActivityLaunchAt < CLIPBOARD_ACTIVITY_COOLDOWN_MS) {
            logger.info("Clipboard sync activity launch skipped (cooldown)")
            return
        }
        lastClipboardActivityLaunchAt = now
        logger.info("Launching clipboard-only sync activity because background clipboard read was blocked")
        // Prefer PendingIntent / full-screen wake — direct startActivity is often blocked
        // by Android BAL / HyperOS "background popup" restrictions.
        val launched = SyncNotificationHelper.launchClipboardSyncActivity(appContext)
        if (!launched) {
            logger.warn(
                "Could not bring clipboard sync activity to foreground. " +
                    "On HyperOS/Xiaomi enable: App info → Other permissions → " +
                    "Display pop-up windows while running in background"
            )
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
            "clipboard_reject" -> handleReject(envelope)
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
        val incoming = incomingTransfers.remove(descriptor.transferId) ?: run {
            logger.warn("transfer_complete for unknown transfer ${descriptor.transferId}")
            return
        }
        val bytes = incoming.output.toByteArray()
        logger.info(
            "Completing image transfer ${descriptor.transferId}: " +
                "${bytes.size}/${descriptor.totalBytes} bytes"
        )
        val checksum = CryptoUtils.sha256Hex(bytes)
        if (!checksum.equals(descriptor.checksumSha256, ignoreCase = true)) {
            logger.warn(
                "Checksum mismatch for transfer ${descriptor.transferId} " +
                    "(got $checksum expected ${descriptor.checksumSha256}, bytes=${bytes.size})"
            )
            lanClient.sendEnvelope(
                ProtocolEnvelope(
                    type = "clipboard_reject",
                    event = incoming.event,
                    reason = "Checksum mismatch"
                )
            )
            return
        }
        applyRemoteEvent(incoming.event, bytes)
    }

    private fun handleAck(envelope: ProtocolEnvelope) {
        val eventId = envelope.event?.eventId ?: return
        pendingByEventId.remove(eventId)
        updateQueueCounts()
        val status = envelope.status ?: "Acked"
        val transferState = if (status == "deferred") TransferState.DEFERRED else TransferState.ACKED
        updateRecentStatus(eventId, transferState, status.replaceFirstChar { it.uppercase() })
        logger.info("Event $eventId acked")
    }

    private fun handleReject(envelope: ProtocolEnvelope) {
        val eventId = envelope.event?.eventId
        val reason = envelope.reason ?: "Rejected"
        logger.warn("Clipboard event rejected: $reason")
        if (eventId != null) {
            pendingByEventId.remove(eventId)
            updateQueueCounts()
            updateRecentStatus(eventId, TransferState.FAILED, reason)
        }
    }

    private suspend fun applyRemoteEvent(event: ClipboardEvent, imageBytes: ByteArray?) {
        if (syncMode() == SyncMode.SEND_ONLY) {
            logger.warn("Rejected incoming event ${event.eventId} because send-only mode is enabled")
            lanClient.sendEnvelope(
                ProtocolEnvelope(
                    type = "clipboard_reject",
                    event = event,
                    reason = "Receive disabled"
                )
            )
            return
        }

        // Echo of content we already sent/applied — ack without rewriting clipboard.
        if (loopGuard.shouldSuppressLocal(event.contentHashSha256) ||
            event.contentHashSha256 == lastQueuedLocalHash
        ) {
            logger.info("Ignoring remote echo for ${event.eventId}")
            loopGuard.rememberSeenEvent(event.eventId)
            lanClient.sendEnvelope(
                ProtocolEnvelope(
                    type = "clipboard_ack",
                    event = event,
                    status = "applied"
                )
            )
            return
        }

        // Never overwrite a clipboard the user just copied on this phone.
        if (lastLocalClipboardAt.isAfter(Instant.now().minus(CONFLICT_WINDOW_MILLIS, ChronoUnit.MILLIS))) {
            logger.info("Skipping remote event ${event.eventId}; local clipboard changed recently")
            loopGuard.rememberSeenEvent(event.eventId)
            lanClient.sendEnvelope(
                ProtocolEnvelope(
                    type = "clipboard_ack",
                    event = event,
                    status = "skipped_local_newer"
                )
            )
            return
        }

        // Mark before writing so the clipboard listener/poller cannot bounce it back.
        lastQueuedLocalHash = event.contentHashSha256
        lastRemoteAppliedAt = Instant.now()
        loopGuard.markRemoteApplied(event.contentHashSha256)
        loopGuard.rememberSeenEvent(event.eventId)

        val applied = clipboardApplyUseCase.applyRemoteClip(event, imageBytes)
        if (applied) {
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
        } else {
            logger.warn("Failed to apply remote event ${event.eventId} to clipboard")
            lanClient.sendEnvelope(
                ProtocolEnvelope(
                    type = "clipboard_reject",
                    event = event,
                    reason = "Apply failed"
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
        if (!forceResend && loopGuard.shouldSuppressLocal(hash)) {
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
        loopGuard.markRemoteApplied(hash)
        addRecent(normalized, "Android -> Windows", "Queued")
        logger.info("Queued Android clipboard event ${normalized.event.eventId} from $trigger")
        flushQueue()
    }

    private suspend fun syncCurrentClipboardWithRetryWindow(
        trigger: String,
        forceResend: Boolean = false,
        logUnavailableReason: Boolean = false
    ): Boolean {
        val initial = readCurrentClipboard(
            trigger = trigger,
            logUnavailableReason = logUnavailableReason,
            logSnapshot = forceResend || trigger == "foreground-resume"
        ) ?: return false

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

        // Manual Sync must always attempt a send, even if the hash matches the last
        // queued item — Linux may never have received it (HyperOS / reconnect races).
        syncNormalizedIfNeeded(chosen, trigger, forceResend)
        return true
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
        if (!canSendOutbound("Outbound sync")) {
            return
        }

        val rejection = policyRejectionReason(normalized)
        if (rejection != null) {
            logger.warn("Skipped ${normalized.event.eventId} from $trigger: $rejection")
            addRecent(
                normalized.copy(event = normalized.event.copy(transferState = TransferState.FAILED)),
                "Android -> Windows",
                "Filtered"
            )
            return
        }

        val hash = normalized.event.contentHashSha256
        if (!forceResend && loopGuard.shouldSuppressLocal(hash)) {
            logger.info("Suppressed clipboard echo for ${normalized.event.eventId}")
            return
        }
        if (!forceResend && lastQueuedLocalHash == hash) {
            logger.info("Skipped unchanged Android clipboard on $trigger")
            return
        }
        // After an image Sync, HyperOS often exposes PNG-as-text / a sibling text item.
        // Do not let that clobber the image transfer on Linux. Manual Sync always wins.
        if (!forceResend &&
            (normalized.event.contentType == ContentType.TEXT || normalized.event.contentType == ContentType.URL)
        ) {
            val msSinceImage = java.time.Duration.between(lastImageQueuedAt, Instant.now()).toMillis()
            if (msSinceImage in 0..4_000L) {
                logger.warn(
                    "Skipping ${normalized.event.contentType} from $trigger; image was queued ${msSinceImage}ms ago"
                )
                return
            }
        }
        if (syncMode() == SyncMode.ASK && !forceResend) {
            addRecent(normalized, "Android -> Windows", "Staged")
            logger.info("Staged ${normalized.event.eventId} from $trigger; resend it from history to send")
            return
        }

        enqueueOutbound(normalized)
        lastQueuedLocalHash = hash
        lastLocalClipboardAt = Instant.now()
        if (normalized.event.contentType == ContentType.IMAGE) {
            lastImageQueuedAt = Instant.now()
        }
        // Prevent peer echo of this outbound item from being re-applied/re-queued.
        loopGuard.markRemoteApplied(hash)
        addRecent(normalized, "Android -> Windows", "Queued")
        logger.info("Queued Android clipboard event ${normalized.event.eventId} (${normalized.event.contentType}) from $trigger")
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
        recentPayloads[normalized.event.eventId] = normalized
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

    private fun canSendOutbound(action: String): Boolean {
        if (!isSyncEnabled()) {
            logger.warn("$action skipped because sync is disabled")
            return false
        }
        if (isPrivacyPaused()) {
            logger.warn("$action skipped because privacy pause is enabled")
            return false
        }
        if (syncMode() == SyncMode.RECEIVE_ONLY) {
            logger.warn("$action skipped because receive-only mode is enabled")
            return false
        }
        return true
    }

    private fun canMirrorClipboardChange(): Boolean {
        return isSyncEnabled() && !isPrivacyPaused() && syncMode() == SyncMode.MIRROR
    }

    private fun policyRejectionReason(normalized: NormalizedClipboard): String? {
        return when (normalized.event.contentType) {
            ContentType.TEXT -> if (allowTextSync()) null else "text sync is disabled"
            ContentType.URL -> if (allowUrlSync()) null else "URL sync is disabled"
            ContentType.IMAGE -> when {
                !allowImageSync() -> "image sync is disabled"
                normalized.event.payloadSizeBytes > maxImageSizeMb() * 1024L * 1024L -> "image is larger than ${maxImageSizeMb()} MB"
                else -> null
            }
            ContentType.MIXED_UNSUPPORTED -> "mixed clipboard content is unsupported"
        }
    }

    private fun updateQueueCounts() {
        _uiState.value = _uiState.value.copy(
            queuedOutboundCount = outboundQueue.size + pendingByEventId.size,
            deferredIncomingCount = deferredIncoming.size
        )
    }

    private companion object {
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_AUTO_SCREENSHOT_SYNC_ENABLED = "auto_screenshot_sync_enabled"
        private const val KEY_PRIVACY_PAUSED = "privacy_paused"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_ALLOW_TEXT_SYNC = "allow_text_sync"
        private const val KEY_ALLOW_URL_SYNC = "allow_url_sync"
        private const val KEY_ALLOW_IMAGE_SYNC = "allow_image_sync"
        private const val KEY_MAX_IMAGE_SIZE_MB = "max_image_size_mb"
        private const val KEY_LAST_SCREENSHOT_ID = "last_screenshot_id"
        private const val CONFLICT_WINDOW_MILLIS = 2_500L
        private const val CLIPBOARD_CHANGE_DEBOUNCE_MS = 250L
        private const val CLIPBOARD_REFRESH_RETRY_COUNT = 4
        private const val CLIPBOARD_REFRESH_RETRY_DELAY_MS = 300L
        private const val SMART_SYNC_SCREENSHOT_MAX_AGE_MILLIS = 5 * 60 * 1000L
        private const val CLIPBOARD_ACTIVITY_COOLDOWN_MS = 700L
        private const val CLIPBOARD_POLL_INTERVAL_MS = 1_200L
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
