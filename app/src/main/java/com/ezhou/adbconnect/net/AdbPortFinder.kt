package com.ezhou.adbconnect.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AdbPortFinder"
private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
private const val NO_PORT = -1

/**
 * adbd does not start advertising the instant `adb_wifi_enabled` flips -- there is a real gap
 * while it spins its TLS listener up. A single NsdManager read taken right after enabling
 * reliably finds nothing, so this keeps discovery running for the whole timeout instead of
 * sampling once, and returns null only once nothing showed up in that window.
 *
 * Discovery runs on a thread the caller is willing to abandon. `discoverServices` is a binder
 * call into the system's NSD daemon and is not guaranteed to return; wrapping it in
 * `withTimeoutOrNull` bought nothing, because cancelling a coroutine cannot unblock a thread
 * stuck below it -- only declining to wait for that thread can.
 */
suspend fun findAdbTlsConnectPort(context: Context, timeoutMs: Long = 8000): Int? {
    val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
    val found = ArrayBlockingQueue<Int>(1)

    thread(name = "adb-port-finder", isDaemon = true) {
        val settled = CountDownLatch(1)
        lateinit var discoveryListener: NsdManager.DiscoveryListener

        fun finish(port: Int) {
            found.offer(port)
            settled.countDown()
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
                finish(NO_PORT)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            Log.w(TAG, "discoverServices threw", it)
            finish(NO_PORT)
            return@thread
        }

        settled.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
    }

    val port = withContext(Dispatchers.IO) { found.poll(timeoutMs, TimeUnit.MILLISECONDS) }
    return port?.takeIf { it != NO_PORT }
}
