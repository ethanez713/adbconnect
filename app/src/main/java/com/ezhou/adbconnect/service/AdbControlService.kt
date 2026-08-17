package com.ezhou.adbconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ezhou.adbconnect.R
import com.ezhou.adbconnect.SecureSettingsWriter
import com.ezhou.adbconnect.TokenStore
import com.ezhou.adbconnect.net.findAdbTlsConnectPort
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private const val TAG = "AdbControlService"
private const val CHANNEL_ID = "adbconnect_service"
private const val NOTIFICATION_ID = 1

/**
 * Foreground service holding a plain TCP listener on [LISTEN_PORT]. It has to be a foreground
 * service, not a plain background one: Android kills a bare background service's process on a
 * whim, and the entire point is that the socket is still there to answer when wireless debugging
 * has silently died and nothing else can reach the phone.
 *
 * Hand-rolled HTTP/1.1 GET handling only -- no server library. One accept thread, one short-lived
 * thread per connection, closed after a single response.
 */
class AdbControlService : Service() {

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        runCatching { serverSocket?.close() }
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_channel_name))
            .setContentText("Listening on port $LISTEN_PORT")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun startServer() {
        running = true
        thread(name = "adbconnect-accept") {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LISTEN_PORT))
                }
            } catch (e: IOException) {
                Log.e(TAG, "failed to bind $LISTEN_PORT", e)
                return@thread
            }
            serverSocket = socket
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed", e)
                    break
                }
                thread(name = "adbconnect-conn") { handleConnection(applicationContext, client) }
            }
        }
    }

    companion object {
        const val LISTEN_PORT = 47821

        /** Read-only status. Port resolution is skipped when debugging is already off -- there is
         * nothing to find, so scanning for up to 8s would only be a needless delay. */
        suspend fun currentStatus(context: Context, resolvePort: Boolean): JSONObject {
            val granted = SecureSettingsWriter.isPermissionGranted(context)
            val enabled = SecureSettingsWriter.isWirelessDebuggingEnabled(context)
            val port = if (resolvePort && enabled) findAdbTlsConnectPort(context) else null
            return statusJson(enabled, port, granted)
        }

        /** Same path the HTTP `/enable` endpoint and the in-app button both call. */
        suspend fun enableAndResolve(context: Context): JSONObject {
            val granted = SecureSettingsWriter.isPermissionGranted(context)
            if (!granted) {
                return statusJson(SecureSettingsWriter.isWirelessDebuggingEnabled(context), null, false)
            }
            SecureSettingsWriter.enableWirelessDebugging(context)
            val port = findAdbTlsConnectPort(context)
            val enabled = SecureSettingsWriter.isWirelessDebuggingEnabled(context)
            return statusJson(enabled, port, true)
        }

        private fun statusJson(enabled: Boolean, port: Int?, granted: Boolean): JSONObject =
            JSONObject().apply {
                put("ok", true)
                put("debuggingEnabled", enabled)
                put("port", port ?: JSONObject.NULL)
                put("model", Build.MODEL)
                put("permissionGranted", granted)
                if (!granted) put("error", "WRITE_SECURE_SETTINGS not granted")
            }
    }
}

private fun handleConnection(context: Context, socket: Socket) {
    socket.use { s ->
        try {
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                writeResponse(s.getOutputStream(), 400, errorJson("bad request"))
                return
            }

            val method = parts[0]
            val rawPath = parts[1]
            val pathParts = rawPath.split("?", limit = 2)
            val path = pathParts[0]
            val params = parseQuery(pathParts.getOrNull(1))

            if (method != "GET" || (path != "/status" && path != "/enable")) {
                writeResponse(s.getOutputStream(), 404, errorJson("not found"))
                return
            }

            val token = params["token"]
            if (token == null || token != TokenStore.getOrCreate(context)) {
                writeResponse(s.getOutputStream(), 401, errorJson("bad token"))
                return
            }

            val body = runBlocking {
                if (path == "/enable") {
                    AdbControlService.enableAndResolve(context)
                } else {
                    AdbControlService.currentStatus(context, resolvePort = true)
                }
            }
            writeResponse(s.getOutputStream(), 200, body)
        } catch (e: IOException) {
            Log.w(TAG, "connection error", e)
        }
    }
}

private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    return query.split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) return@mapNotNull null
        val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
        val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        key to value
    }.toMap()
}

private fun errorJson(message: String): JSONObject = JSONObject().apply {
    put("ok", false)
    put("error", message)
}

private fun writeResponse(output: OutputStream, status: Int, body: JSONObject) {
    val statusText = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        else -> "Not Found"
    }
    val bytes = body.toString().toByteArray(Charsets.UTF_8)
    val header = buildString {
        append("HTTP/1.1 $status $statusText\r\n")
        append("Content-Type: application/json\r\n")
        append("Content-Length: ${bytes.size}\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
    output.write(header.toByteArray(Charsets.US_ASCII))
    output.write(bytes)
    output.flush()
}
