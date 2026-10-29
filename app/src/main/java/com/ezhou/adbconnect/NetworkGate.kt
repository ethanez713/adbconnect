package com.ezhou.adbconnect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

private const val PREFS = "network_gate"
private const val KEY_ALLOWED = "allowed_ssids"

internal enum class GateAction { ENABLE, DISABLE, NONE }

internal fun gateAction(
    onWifi: Boolean,
    ssid: String?,
    allowed: Set<String>,
    debuggingOn: Boolean,
): GateAction = when {
    !onWifi -> if (debuggingOn) GateAction.DISABLE else GateAction.NONE
    ssid == null -> GateAction.NONE
    ssid in allowed -> if (debuggingOn) GateAction.NONE else GateAction.ENABLE
    debuggingOn -> GateAction.DISABLE
    else -> GateAction.NONE
}

internal fun normalizeSsid(raw: String?): String? {
    if (raw == null) return null
    val stripped = raw.removeSurrounding("\"")
    if (stripped.isBlank() || stripped == WifiManager.UNKNOWN_SSID) return null
    return stripped
}

// Android 17 resumes debugging on every join while the setting reads 1, and the system prompt is
// that resume hitting an untrusted network.
object NetworkGate {
    private val lock = Any()
    private val connected = mutableSetOf<Network>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastSsid: String? = null
    private var haveLastSsid = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun allowedNetworks(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED, emptySet()).orEmpty().toSet()

    fun allow(context: Context, ssid: String) {
        val name = normalizeSsid(ssid) ?: return
        val store = prefs(context)
        val current = store.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()
        if (store.contains(KEY_ALLOWED) && name in current) return
        store.edit().putStringSet(KEY_ALLOWED, HashSet(current).apply { add(name) }).apply()
    }

    fun remove(context: Context, ssid: String) {
        val store = prefs(context)
        val current = store.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()
        val name = normalizeSsid(ssid) ?: ssid
        store.edit().putStringSet(KEY_ALLOWED, HashSet(current).apply { remove(name) }).apply()
    }

    fun canReadSsid(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine || !background) return false
        return context.getSystemService(LocationManager::class.java).isLocationEnabled
    }

    fun currentSsid(context: Context): String? {
        val app = context.applicationContext
        if (!isOnWifi(app)) return null
        @Suppress("DEPRECATION")
        val raw = app.getSystemService(WifiManager::class.java).connectionInfo?.ssid
        return normalizeSsid(raw)
    }

    fun apply(context: Context, onWifi: Boolean, ssid: String?) {
        val app = context.applicationContext
        val name = normalizeSsid(ssid)
        seedIfUnset(app, name)
        val action = gateAction(
            onWifi,
            name,
            allowedNetworks(app),
            SecureSettingsWriter.isWirelessDebuggingEnabled(app),
        )
        if (action == GateAction.NONE) return
        thread(name = "network-gate") {
            val wrote = when (action) {
                GateAction.ENABLE -> SecureSettingsWriter.enableWirelessDebugging(app)
                GateAction.DISABLE -> SecureSettingsWriter.disableWirelessDebugging(app)
                GateAction.NONE -> false
            }
            if (!wrote) return@thread
            Metrics.record(
                app,
                if (action == GateAction.ENABLE) MetricEvent.NETWORK_AUTO_ENABLE
                else MetricEvent.NETWORK_AUTO_DISABLE,
            )
        }
    }

    fun register(context: Context) {
        val app = context.applicationContext
        val cb = synchronized(lock) {
            if (callback != null) return
            connected.clear()
            haveLastSsid = false
            lastSsid = null
            newCallback(app).also { callback = it }
        }
        app.getSystemService(ConnectivityManager::class.java)
            .registerNetworkCallback(wifiRequest(), cb)
    }

    fun unregister(context: Context) {
        val cb = synchronized(lock) {
            val current = callback ?: return
            callback = null
            connected.clear()
            haveLastSsid = false
            lastSsid = null
            current
        }
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
            .unregisterNetworkCallback(cb)
    }

    private fun seedIfUnset(context: Context, ssid: String?) {
        if (ssid == null) return
        val store = prefs(context)
        if (store.contains(KEY_ALLOWED)) return
        store.edit().putStringSet(KEY_ALLOWED, hashSetOf(ssid)).apply()
    }

    private fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun wifiRequest(): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

    private fun newCallback(app: Context): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            newLocationCallback(app)
        } else {
            newLegacyCallback(app)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun newLocationCallback(app: Context): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback(
            ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
        ) {
            override fun onAvailable(network: Network) {
                noteConnected(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                noteConnected(network)
                onWifiCapabilities(app, (caps.transportInfo as? WifiInfo)?.ssid)
            }

            override fun onLost(network: Network) {
                onWifiLost(app, network)
            }
        }

    private fun newLegacyCallback(app: Context): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                noteConnected(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                noteConnected(network)
                @Suppress("DEPRECATION")
                val raw = app.getSystemService(WifiManager::class.java).connectionInfo?.ssid
                onWifiCapabilities(app, raw)
            }

            override fun onLost(network: Network) {
                onWifiLost(app, network)
            }
        }

    private fun noteConnected(network: Network) {
        synchronized(lock) { connected.add(network) }
    }

    private fun onWifiCapabilities(context: Context, raw: String?) {
        val ssid = normalizeSsid(raw)
        synchronized(lock) {
            if (haveLastSsid && ssid == lastSsid) return
            haveLastSsid = true
            lastSsid = ssid
        }
        apply(context, onWifi = true, ssid = ssid)
    }

    // registerNetworkCallback reports each Network separately, so a roam delivers onLost for the
    // old one while the new one is still up. That is not Wi-Fi loss.
    private fun onWifiLost(context: Context, network: Network) {
        synchronized(lock) {
            connected.remove(network)
            if (connected.isNotEmpty()) return
            haveLastSsid = false
            lastSsid = null
        }
        apply(context, onWifi = false, ssid = null)
    }
}
