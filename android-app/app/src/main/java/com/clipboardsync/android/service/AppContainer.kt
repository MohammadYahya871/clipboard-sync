package com.clipboardsync.android.service

import android.app.Application
import com.clipboardsync.android.clipboard.ClipboardApplyUseCase
import com.clipboardsync.android.clipboard.ClipboardNormalizer
import com.clipboardsync.android.clipboard.ImageCacheStore
import com.clipboardsync.android.clipboard.LoopGuard
import com.clipboardsync.android.clipboard.ScreenshotRepository
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.pairing.LocalDeviceIdentityStore
import com.clipboardsync.android.pairing.TrustedDeviceRepository
import com.clipboardsync.android.transport.LanClient
import com.clipboardsync.android.transport.LanPeerDiscovery
import com.clipboardsync.android.transport.NsdPeerDiscovery
import com.clipboardsync.android.transport.TransportSelector

class AppContainer(
    app: Application
) {
    val logger = AppLogger()
    private val localDeviceIdentityStore = LocalDeviceIdentityStore(app)
    private val trustedDeviceRepository = TrustedDeviceRepository(app, logger)
    private val imageCacheStore = ImageCacheStore(app, logger)
    private val screenshotRepository = ScreenshotRepository(app, logger)
    private val clipboardApplyUseCase = ClipboardApplyUseCase(app, imageCacheStore, logger)
    private val clipboardNormalizer = ClipboardNormalizer(app, localDeviceIdentityStore, imageCacheStore, logger)
    private val lanClient = LanClient(logger)
    private val lanPeerDiscovery = LanPeerDiscovery(logger)
    private val nsdPeerDiscovery = NsdPeerDiscovery(app, logger)
    private val transportSelector = TransportSelector()
    private val loopGuard = LoopGuard()

    val syncRepository = SyncRepository(
        app = app,
        logger = logger,
        localDeviceIdentityStore = localDeviceIdentityStore,
        trustedDeviceRepository = trustedDeviceRepository,
        imageCacheStore = imageCacheStore,
        screenshotRepository = screenshotRepository,
        clipboardNormalizer = clipboardNormalizer,
        clipboardApplyUseCase = clipboardApplyUseCase,
        lanClient = lanClient,
        lanPeerDiscovery = lanPeerDiscovery,
        nsdPeerDiscovery = nsdPeerDiscovery,
        transportSelector = transportSelector,
        loopGuard = loopGuard
    )
}
