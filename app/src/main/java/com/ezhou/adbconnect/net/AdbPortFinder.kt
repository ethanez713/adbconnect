package com.ezhou.adbconnect.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AdbPortFinder"
private const val SERVICE_TYPE = "_adb-tls-connect._tcp"

/**
 * adbd does not start advertising the instant `adb_wifi_enabled` flips -- there is a real gap
 * while it spins its TLS listener up. A single NsdManager read taken right after enabling
 * reliably finds nothing, so this keeps discovery running for the whole timeout instead of
 * sampling once, and returns null only once nothing showed up in that window.
 */
suspend fun findAdbTlsConnectPort(context: Context, timeoutMs: Long = 8000): Int? {
    val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null

    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            lateinit var discoveryListener: NsdManager.DiscoveryListener

            fun finish(port: Int?) {
                if (cont.isActive) cont.resume(port)
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    finish(serviceInfo.port)
                }
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceType.trimEnd('.').endsWith(SERVICE_TYPE)) return
                    runCatching { nsdManager.resolveService(service, resolveListener) }
                }

                override fun onServiceLost(service: NsdServiceInfo) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "discovery start failed: $errorCode")
                    if (cont.isActive) cont.resume(null)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            cont.invokeOnCancellation {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            }

            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }
}
