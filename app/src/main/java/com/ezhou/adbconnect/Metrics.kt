package com.ezhou.adbconnect

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "Metrics"
private const val METRICS_FILE = "metrics.json"

/** Margin past the 30-day window `snapshot` reports, so a day never falls out of `last30`
 * before its own persist has had a chance to prune it. */
private const val PRUNE_HORIZON_DAYS = 35L
private const val LAST30_WINDOW_DAYS = 30L

enum class MetricEvent(val id: String, val label: String, val breaksDownEnable: Boolean = false) {
    ENABLE_REMOTE("enable.remote", "Enable requests (LAN)"),
    STATUS_REMOTE("status.remote", "Status requests (LAN)"),
    ENABLE_MANUAL("enable.manual", "Enable taps (in app)"),
    APP_OPEN("app.open", "App opens"),
    ENABLE_ALREADY_ON("enable.already_on", "already on, no-op", breaksDownEnable = true),
    ENABLE_FLIPPED("enable.flipped", "had to turn it on", breaksDownEnable = true),
    ENABLE_PORT_FOUND("enable.port_found", "adbd port resolved", breaksDownEnable = true),
    ENABLE_PORT_TIMEOUT("enable.port_timeout", "port lookup timed out", breaksDownEnable = true),
    ENABLE_DENIED("enable.denied", "permission not granted", breaksDownEnable = true),
    SERVICE_START("service.start", "Service starts"),
    SERVICE_BOOT_START("service.boot_start", "Boot restarts"),
    SERVICE_KEEPALIVE_RESTART("service.keepalive_restart", "Keepalive restarts"),
    SERVICE_BIND_FAILED("service.bind_failed", "Port bind failures"),
    AUTH_REJECTED("auth.rejected", "Rejected (bad token)"),
    INSTALL_REMOTE("install.remote", "APK pushes (LAN)"),
    INSTALL_REJECTED("install.rejected", "Rejected (bad APK)"),
    INSTALL_STAGED("install.staged", "Staged, awaiting tap"),
    INSTALL_SUCCESS("install.success", "Installed"),
    INSTALL_FAILED("install.failed", "Install failed"),

    REQUEST_STALLED("request.stalled", "Requests that timed out"),
}

data class EventCount(val total: Long, val last30: Long)

data class MetricsSnapshot(val since: LocalDate, val counts: Map<MetricEvent, EventCount>)

internal class MetricsStore(
    private val file: File,
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    private var since: LocalDate? = null
    private val counts = mutableMapOf<String, MutableMap<Long, Long>>()
    private val totals = mutableMapOf<String, Long>()
    private var loaded = false

    @Synchronized
    fun record(event: MetricEvent) {
        ensureLoaded()
        val today = clock().toEpochDay()
        val days = counts.getOrPut(event.id) { mutableMapOf() }
        days[today] = (days[today] ?: 0L) + 1L
        totals[event.id] = (totals[event.id] ?: 0L) + 1L
        prune(today)
        persist()
    }

    @Synchronized
    fun snapshot(): MetricsSnapshot {
        ensureLoaded()
        val today = clock().toEpochDay()
        val cutoff = today - (LAST30_WINDOW_DAYS - 1)
        val perEvent = MetricEvent.entries.associateWith { event ->
            val days = counts[event.id].orEmpty()
            val last30 = days.entries.filter { it.key >= cutoff }.sumOf { it.value }
            EventCount(total = totals[event.id] ?: 0L, last30 = last30)
        }
        return MetricsSnapshot(since = since ?: clock(), counts = perEvent)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        load()
    }

    private fun load() {
        if (!file.exists()) {
            since = clock()
            return
        }
        try {
            val json = JSONObject(file.readText())
            since = LocalDate.ofEpochDay(json.getLong("since"))
            val countsJson = json.optJSONObject("counts") ?: JSONObject()
            for (eventId in countsJson.keys()) {
                val eventJson = countsJson.getJSONObject(eventId)
                totals[eventId] = eventJson.optLong("total", 0L)
                val daysJson = eventJson.optJSONObject("days") ?: JSONObject()
                val days = mutableMapOf<Long, Long>()
                for (dayKey in daysJson.keys()) {
                    days[dayKey.toLong()] = daysJson.getLong(dayKey)
                }
                counts[eventId] = days
            }
        } catch (e: JSONException) {
            Log.w(TAG, "corrupt metrics file, starting fresh", e)
            since = clock()
            counts.clear()
            totals.clear()
        } catch (e: NumberFormatException) {
            Log.w(TAG, "corrupt metrics file, starting fresh", e)
            since = clock()
            counts.clear()
            totals.clear()
        }
    }

    private fun prune(today: Long) {
        val cutoff = today - PRUNE_HORIZON_DAYS
        for (days in counts.values) {
            days.keys.retainAll { it > cutoff }
        }
    }

    private fun persist() {
        val json = JSONObject().apply {
            put("since", (since ?: clock()).toEpochDay())
            put("counts", JSONObject().apply {
                for ((eventId, days) in counts) {
                    put(eventId, JSONObject().apply {
                        put("total", totals[eventId] ?: 0L)
                        put("days", JSONObject().apply {
                            for ((day, count) in days) {
                                put(day.toString(), count)
                            }
                        })
                    })
                }
            })
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }
}

object Metrics {
    private val stores = mutableMapOf<String, MetricsStore>()

    /** Callers are the service's onCreate, a BroadcastReceiver's onReceive and the UI's enable
     * path -- all main-thread, none able to afford a disk write inline. Single-threaded so the
     * store's writes stay ordered. */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "metrics-writer").apply { isDaemon = true }
    }

    @Synchronized
    private fun storeFor(context: Context): MetricsStore =
        stores.getOrPut(context.applicationContext.filesDir.absolutePath) {
            MetricsStore(File(context.applicationContext.filesDir, METRICS_FILE))
        }

    fun record(context: Context, event: MetricEvent) {
        val store = storeFor(context)
        writer.execute { store.record(event) }
    }

    fun snapshot(context: Context): MetricsSnapshot = storeFor(context).snapshot()
}
