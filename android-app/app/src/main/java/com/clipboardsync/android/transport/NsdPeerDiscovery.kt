package com.clipboardsync.android.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.clipboardsync.android.diagnostics.AppLogger
import com.clipboardsync.android.protocol.ResolvedPeerEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NsdPeerDiscovery(
    context: Context,
    private val logger: AppLogger
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _services = MutableStateFlow<List<ResolvedPeerEndpoint>>(emptyList())
    val services = _services.asStateFlow()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                logger.info("NSD started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _services.value = _services.value.filterNot { it.serviceName == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                logger.info("NSD stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger.warn("NSD start failed $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger.warn("NSD stop failed $errorCode")
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
            discoveryListener = null
        }
    }

    fun knownHostFor(serviceName: String): ResolvedPeerEndpoint? {
        return _services.value.firstOrNull { it.serviceName == serviceName }
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                logger.warn("Failed to resolve ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress ?: return
                val endpoint = ResolvedPeerEndpoint(resolved.serviceName, host, resolved.port)
                _services.value = (_services.value.filterNot { it.serviceName == endpoint.serviceName } + endpoint).sortedBy { it.serviceName }
                logger.info("Resolved ${endpoint.serviceName} to ${endpoint.host}:${endpoint.port}")
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_clipboardsync._tcp."
    }
}

