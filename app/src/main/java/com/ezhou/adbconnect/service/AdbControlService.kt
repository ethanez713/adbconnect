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
import com.ezhou.adbconnect.MetricEvent
import com.ezhou.adbconnect.Metrics
import com.ezhou.adbconnect.R
import com.ezhou.adbconnect.SecureSettingsWriter
import com.ezhou.adbconnect.TokenStore
import com.ezhou.adbconnect.net.findAdbTlsConnectPort
import com.ezhou.adbconnect.MAX_APK_BYTES
import com.ezhou.adbconnect.StageOutcome
import com.ezhou.adbconnect.stageAndPrompt
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private const val TAG = "AdbControlService"
private const val CHANNEL_ID = "adbconnect_service"
private const val NOTIFICATION_ID = 1

/** Comfortably past the port finder's own 8s budget, and inside the 15s the caller allows. */
private const val RESPONSE_BUDGET_MS = 12_000L

private val enableInFlight = AtomicBoolean(false)

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
        Metrics.record(applicationContext, MetricEvent.SERVICE_START)
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
                Metrics.record(applicationContext, MetricEvent.SERVICE_BIND_FAILED)
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

        /** Same path the HTTP `/enable` endpoint and the in-app button both call -- outcome
         * counters live here, but which caller triggered it is recorded at each call site instead,
         * since this function has no way to tell them apart. */
        suspend fun enableAndResolve(context: Context): JSONObject {
            val granted = SecureSettingsWriter.isPermissionGranted(context)
            if (!granted) {
                Metrics.record(context, MetricEvent.ENABLE_DENIED)
                return statusJson(SecureSettingsWriter.isWirelessDebuggingEnabled(context), null, false)
            }
            val wasEnabled = SecureSettingsWriter.isWirelessDebuggingEnabled(context)
            Metrics.record(context, if (wasEnabled) MetricEvent.ENABLE_ALREADY_ON else MetricEvent.ENABLE_FLIPPED)
            SecureSettingsWriter.enableWirelessDebugging(context)
            val port = findAdbTlsConnectPort(context)
            Metrics.record(context, if (port != null) MetricEvent.ENABLE_PORT_FOUND else MetricEvent.ENABLE_PORT_TIMEOUT)
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

private val installInFlight = AtomicBoolean(false)

private fun handleConnection(context: Context, socket: Socket) {
    socket.use { s ->
        try {
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val head = readRequestHead(input) ?: return
            val requestLine = head.firstOrNull() ?: return

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

            val known = (method == "GET" && (path == "/status" || path == "/enable")) ||
                (method == "POST" && path == "/install")
            if (!known) {
                writeResponse(s.getOutputStream(), 404, errorJson("not found"))
                return
            }

            val token = params["token"]
            if (token == null || !constantTimeEquals(token, TokenStore.getOrCreate(context))) {
                Metrics.record(context, MetricEvent.AUTH_REJECTED)
                writeResponse(s.getOutputStream(), 401, errorJson("bad token"))
                return
            }

            if (path == "/install") {
                handleInstall(context, s.getOutputStream(), input, head)
                return
            }

            val body = if (path == "/enable") {
                Metrics.record(context, MetricEvent.ENABLE_REMOTE)
                // A write parked on the consent dialog stays parked until it is tapped, so a
                // caller that retries would only stack up threads waiting on the same tap and
                // pay the full budget each time to learn nothing new.
                if (!enableInFlight.compareAndSet(false, true)) {
                    null
                } else {
                    answerWithin(RESPONSE_BUDGET_MS) {
                        try {
                            runBlocking { AdbControlService.enableAndResolve(context) }
                        } finally {
                            enableInFlight.set(false)
                        }
                    }
                }
            } else {
                Metrics.record(context, MetricEvent.STATUS_REMOTE)
                answerWithin(RESPONSE_BUDGET_MS) {
                    runBlocking { AdbControlService.currentStatus(context, resolvePort = true) }
                }
            }
            if (body == null) {
                Metrics.record(context, MetricEvent.REQUEST_STALLED)
                writeResponse(s.getOutputStream(), 503, stalledJson(context))
            } else {
                writeResponse(s.getOutputStream(), 200, body)
            }
        } catch (e: IOException) {
            Log.w(TAG, "connection error", e)
        } catch (e: Exception) {
            // Android's default handler kills the process on any uncaught throw, on any thread.
            // A service whose entire job is answering when nothing else can must not be
            // stoppable by one bad request, whatever is wrong with it.
            Log.w(TAG, "request failed", e)
        }
    }
}

/**
 * The one endpoint that takes a body: the PC streams an APK up when it cannot reach adb but can
 * still reach this listener. Everything dangerous about accepting a pushed executable is fenced by
 * [stageAndPrompt] -- the APK is only ever installed as a same-signed update to this package, and
 * the user still taps to confirm. Here we only bound it (size, one at a time) and get the bytes to
 * disk without letting a malformed or oversized upload take the process down.
 */
private fun handleInstall(context: Context, output: OutputStream, input: InputStream, head: List<String>) {
    Metrics.record(context, MetricEvent.INSTALL_REMOTE)
    val contentLength = head.drop(1)
        .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.toLongOrNull()
    if (contentLength == null || contentLength <= 0) {
        writeResponse(output, 400, errorJson("missing or invalid Content-Length"))
        return
    }
    if (contentLength > MAX_APK_BYTES) {
        Metrics.record(context, MetricEvent.INSTALL_REJECTED)
        writeResponse(output, 413, errorJson("APK exceeds ${MAX_APK_BYTES} bytes"))
        return
    }
    if (!installInFlight.compareAndSet(false, true)) {
        writeResponse(output, 503, errorJson("an install is already in progress"))
        return
    }
    val upload = File(context.cacheDir, "upload.apk")
    try {
        readBodyToFile(input, upload, contentLength)
        when (val outcome = stageAndPrompt(context, upload)) {
            is StageOutcome.Ok -> writeResponse(output, 200, JSONObject().apply {
                put("ok", true)
                put("staged", true)
                put("pendingUserAction", true)
                put("versionCode", outcome.versionCode)
                put("versionName", outcome.versionName ?: JSONObject.NULL)
                put("model", Build.MODEL)
            })
            is StageOutcome.Rejected -> {
                Metrics.record(context, MetricEvent.INSTALL_REJECTED)
                writeResponse(output, 400, errorJson(outcome.reason))
            }
        }
    } catch (e: IOException) {
        Metrics.record(context, MetricEvent.INSTALL_REJECTED)
        writeResponse(output, 400, errorJson("upload failed: ${e.message}"))
    } finally {
        upload.delete()
        installInFlight.set(false)
    }
}

/** Reads exactly [length] bytes -- the client's declared Content-Length -- into [dest], streamed so
 * a large APK never sits in memory. A stream that ends early is a truncated upload, not a short APK. */
private fun readBodyToFile(input: InputStream, dest: File, length: Long) {
    val buffer = ByteArray(8192)
    var remaining = length
    FileOutputStream(dest).use { out ->
        while (remaining > 0) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            if (read == -1) throw IOException("stream ended ${remaining} bytes short of Content-Length")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }
}

/** Request line plus header lines, read straight off the socket as bytes and stopped at the blank
 * line. A [BufferedReader] cannot do this job: it reads ahead past the header, swallowing the APK
 * bytes of a POST body into its char buffer where the raw stream can no longer reach them. */
private const val MAX_HEAD_BYTES = 16 * 1024

private fun readRequestHead(input: InputStream): List<String>? {
    val buf = ByteArrayOutputStream()
    var crlfState = 0 // advances through \r \n \r \n; 4 = blank line reached
    while (true) {
        val c = input.read()
        if (c == -1) return null
        buf.write(c)
        if (buf.size() > MAX_HEAD_BYTES) return null
        crlfState = when (c) {
            '\r'.code -> if (crlfState == 2) 3 else 1
            '\n'.code -> if (crlfState == 1) 2 else if (crlfState == 3) 4 else 0
            else -> 0
        }
        if (crlfState == 4) break
    }
    return buf.toString("US-ASCII").split("\r\n").filter { it.isNotEmpty() }
}

/**
 * A handler that never returns leaves the caller unable to tell a wedged phone from an absent one,
 * and burns the caller's whole timeout budget doing it. Both the settings write and NSD discovery
 * are binder calls into system services and neither is guaranteed to come back, so the answer is
 * produced on a thread this one is willing to abandon.
 */
private fun answerWithin(budgetMs: Long, work: () -> JSONObject): JSONObject? {
    val answer = ArrayBlockingQueue<JSONObject>(1)
    thread(name = "adbconnect-work", isDaemon = true) {
        runCatching(work)
            .onSuccess { answer.offer(it) }
            .onFailure { Log.w(TAG, "request handler failed", it) }
    }
    return answer.poll(budgetMs, TimeUnit.MILLISECONDS)
}

/**
 * The first time wireless debugging is enabled on a given Wi-Fi network Android puts up "Allow
 * wireless debugging on this network?", and the write to `adb_wifi_enabled` does not return until
 * someone taps it. That gesture is the point of the dialog, so no permission this app can hold
 * will dismiss it -- the most it can do is stop pretending to be unreachable and name the tap it
 * is waiting for.
 *
 * Which call stalled cannot be seen from outside the phone, but the setting can: if it reads on,
 * the write landed and port discovery is what overran; if off, the write is still parked.
 */
private fun stalledJson(context: Context): JSONObject = JSONObject().apply {
    val enabled = SecureSettingsWriter.isWirelessDebuggingEnabled(context)
    put("ok", false)
    put("awaitingConsent", !enabled)
    put(
        "error",
        if (enabled) {
            "debugging is on but the adb port did not resolve within ${RESPONSE_BUDGET_MS}ms"
        } else {
            "waiting for the on-device \"Allow wireless debugging on this network?\" prompt"
        },
    )
    put("debuggingEnabled", enabled)
    put("permissionGranted", SecureSettingsWriter.isPermissionGranted(context))
    put("model", Build.MODEL)
}

/** String equality short-circuits on the first differing byte, which times how much of a guess
 * was right. The channel is thin over a LAN, but a fixed-cost compare closes it for one line. */
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

private fun parseQuery(query: String?): Map<String, String> {
    if (query.isNullOrEmpty()) return emptyMap()
    return query.split("&").mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) return@mapNotNull null
        // URLDecoder throws on a malformed %-escape, and this runs before the token is
        // checked -- so a five-character GET from anyone on the LAN used to take the whole
        // process down without authenticating at all. A pair that will not decode is simply
        // not a pair.
        runCatching {
            URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.getOrNull()
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
        503 -> "Service Unavailable"
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
