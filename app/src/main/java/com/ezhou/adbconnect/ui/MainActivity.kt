package com.ezhou.adbconnect.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ezhou.adbconnect.EventCount
import com.ezhou.adbconnect.MetricEvent
import com.ezhou.adbconnect.Metrics
import com.ezhou.adbconnect.MetricsSnapshot
import com.ezhou.adbconnect.StayAwake
import com.ezhou.adbconnect.TokenStore
import com.ezhou.adbconnect.clearStagedUpdateIfInstalled
import com.ezhou.adbconnect.commitStagedUpdate
import com.ezhou.adbconnect.pendingUpdateFile
import com.ezhou.adbconnect.service.AdbControlService
import com.ezhou.adbconnect.service.isAdbServiceListening
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GRANT_COMMAND =
    "adb shell pm grant com.ezhou.adbconnect android.permission.WRITE_SECURE_SETTINGS"

private const val SESSION_RESULT_ACTION = "com.ezhou.adbconnect.SESSION_RESULT"

class MainActivity : ComponentActivity() {

    // The in-app fallback for when the pushed-update notification never shows -- notifications for
    // this app are off by default. Committing the staged APK reports back here; being foreground,
    // this activity may launch the system confirm screen directly, which the background notification
    // receiver cannot.
    private val sessionResult = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                == PackageInstaller.STATUS_PENDING_USER_ACTION
            ) {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The whole point of this app is that the service is already running before wireless
        // debugging drops -- start it here so a plain launch from the launcher is enough, without
        // relying on the user to separately know the service needs kicking off.
        ContextCompat.startForegroundService(this, Intent(this, AdbControlService::class.java))

        ContextCompat.registerReceiver(
            this,
            sessionResult,
            IntentFilter(SESSION_RESULT_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch(Dispatchers.IO) { clearStagedUpdateIfInstalled(applicationContext) }

        if (savedInstanceState == null) {
            Metrics.record(applicationContext, MetricEvent.APP_OPEN)
        }

        setContent {
            AdbConnectTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AdbConnectScreen(onInstallUpdate = ::installStagedUpdate)
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(sessionResult)
        super.onDestroy()
    }

    private fun installStagedUpdate() {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val sender = PendingIntent.getBroadcast(
            this, 0, Intent(SESSION_RESULT_ACTION).setPackage(packageName), flags,
        )
        commitStagedUpdate(this, sender.intentSender)
    }
}

private data class UiState(
    val serviceRunning: Boolean = false,
    val permissionGranted: Boolean = false,
    val debuggingEnabled: Boolean = false,
    val port: Int? = null,
    val dozeExempt: Boolean = false,
    val token: String = "",
    val busy: Boolean = false,
    val lastError: String? = null,
    val metrics: MetricsSnapshot? = null,
    val pendingUpdate: Boolean = false,
    val awakeEnabled: Boolean = true,
    val awakeWindowMinutes: Int = 30,
    val awakeDeadlineMs: Long? = null,
    val wakePermissionGranted: Boolean = false,
)

@Composable
private fun AdbConnectScreen(onInstallUpdate: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(UiState(token = TokenStore.getOrCreate(context))) }
    val scope = rememberCoroutineScope()

    // Scoped to STARTED, not to the composition: the foreground service keeps this process alive
    // forever, so a plain LaunchedEffect leaves the Activity composed after onStop and the poll
    // runs 28800 times a day against a screen nobody is looking at.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val (serviceRunning, metricsSnapshot) = withContext(Dispatchers.IO) {
                    isAdbServiceListening() to Metrics.snapshot(context)
                }
                val status = AdbControlService.currentStatus(context, resolvePort = false)
                state = state.copy(
                    serviceRunning = serviceRunning,
                    permissionGranted = status.optBoolean("permissionGranted"),
                    debuggingEnabled = status.optBoolean("debuggingEnabled"),
                    port = if (status.isNull("port")) null else status.optInt("port"),
                    dozeExempt = isIgnoringBatteryOptimizations(context),
                    metrics = metricsSnapshot,
                    pendingUpdate = pendingUpdateFile(context).exists(),
                    awakeEnabled = StayAwake.isEnabled(context),
                    awakeWindowMinutes = StayAwake.windowMinutes(context),
                    awakeDeadlineMs = StayAwake.deadlineMs(context),
                    wakePermissionGranted = StayAwake.isWakePermissionGranted(context),
                )
                delay(3000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ADB Connection Service", style = MaterialTheme.typography.headlineSmall)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusRow("Service running", if (state.serviceRunning) "yes" else "no")
            StatusRow("Listen port", AdbControlService.LISTEN_PORT.toString())
            StatusRow("WRITE_SECURE_SETTINGS granted", if (state.permissionGranted) "yes" else "no")
            StatusRow("Wireless debugging", if (state.debuggingEnabled) "on" else "off")
            StatusRow("Debugging port", state.port?.toString() ?: "unknown")
            StatusRow("Reachable while idle", if (state.dozeExempt) "yes" else "no — see below")
        }

        if (!state.dozeExempt) {
            Text(
                "Android is free to cut this app off the network once the phone has been idle a " +
                    "while, which is when the PC needs it most. Exempt it from battery " +
                    "optimisation — bootstrap.sh does this over adb, or tap here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { context.startActivity(batteryOptimizationSettingsIntent(context)) }) {
                Text("Exempt from battery optimisation")
            }
        }

        KeepAwakeSection(
            enabled = state.awakeEnabled,
            windowMinutes = state.awakeWindowMinutes,
            deadlineMs = state.awakeDeadlineMs,
            wakePermissionGranted = state.wakePermissionGranted,
            onEnabledChange = {
                StayAwake.setEnabled(context, it)
                state = state.copy(awakeEnabled = it, awakeDeadlineMs = StayAwake.deadlineMs(context))
            },
            onWindowChange = {
                StayAwake.setWindowMinutes(context, it)
                state = state.copy(awakeWindowMinutes = it)
            },
        )

        state.metrics?.let { UsageSection(it) }

        if (state.pendingUpdate) {
            Text(
                "A new build has been pushed to this phone. If no notification appeared, install it " +
                    "here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onInstallUpdate) { Text("Install pushed update") }
        }

        if (!state.permissionGranted) {
            Text(
                "Grant this once (bootstrap.sh does it automatically), then relaunch:",
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectableMono(GRANT_COMMAND)
        }

        Text("Auth token (read by the LAN script, never type this by hand)", style = MaterialTheme.typography.labelLarge)
        SelectableMono(state.token)

        Button(
            onClick = {
                scope.launch {
                    Metrics.record(context, MetricEvent.ENABLE_MANUAL)
                    state = state.copy(busy = true, lastError = null)
                    val status = AdbControlService.enableAndResolve(context)
                    state = state.copy(
                        busy = false,
                        permissionGranted = status.optBoolean("permissionGranted"),
                        debuggingEnabled = status.optBoolean("debuggingEnabled"),
                        port = if (status.isNull("port")) null else status.optInt("port"),
                        lastError = status.optString("error", "").ifEmpty { null },
                    )
                }
            },
            enabled = !state.busy,
        ) {
            Text(if (state.busy) "Enabling…" else "Enable wireless debugging now")
        }

        state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

private val awakeWindowChoices = listOf(15, 30, 60, 120)
private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun KeepAwakeSection(
    enabled: Boolean,
    windowMinutes: Int,
    deadlineMs: Long?,
    wakePermissionGranted: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onWindowChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Keep awake during adb sessions", style = MaterialTheme.typography.titleSmall)
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                awakeWindowChoices.forEach { minutes ->
                    FilterChip(
                        selected = minutes == windowMinutes,
                        onClick = { onWindowChange(minutes) },
                        label = { Text(if (minutes < 60) "$minutes min" else "${minutes / 60} h") },
                    )
                }
            }
            StatusRow(
                "Stay awake while charging",
                deadlineMs?.let { "until ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clockFormatter)}" }
                    ?: "idle",
            )
            StatusRow("Wake screen permission", if (wakePermissionGranted) "granted" else "no — run bootstrap.sh")
        }
    }
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val countColumnWidth = 48.dp

@Composable
private fun UsageSection(metrics: MetricsSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Usage", style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(1f))
            Text(
                "30d",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(countColumnWidth),
            )
            Text(
                "all",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(countColumnWidth),
            )
        }
        MetricEvent.entries.forEach { event ->
            val count = metrics.counts[event] ?: EventCount(0, 0)
            MetricRow(
                label = event.label,
                last30 = count.last30,
                total = count.total,
                indent = if (event.breaksDownEnable) 12.dp else 0.dp,
            )
        }
        Text(
            "Tracking since ${metrics.since.format(dateFormatter)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricRow(label: String, last30: Long, total: Long, indent: Dp) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = indent),
        )
        Text(
            last30.toString(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(countColumnWidth),
        )
        Text(
            total.toString(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(countColumnWidth),
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SelectableMono(text: String) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        )
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

/**
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS shows a one-tap dialog but is Play-policy restricted;
 * this app is sideloaded, so it is the right one. Should a future Android refuse to resolve it, the
 * settings list still gets the user to the same toggle in two more taps.
 */
private fun batteryOptimizationSettingsIntent(context: Context): Intent {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    return if (request.resolveActivity(context.packageManager) != null) {
        request
    } else {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}

