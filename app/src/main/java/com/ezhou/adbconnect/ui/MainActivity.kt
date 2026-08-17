package com.ezhou.adbconnect.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ezhou.adbconnect.TokenStore
import com.ezhou.adbconnect.service.AdbControlService
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GRANT_COMMAND =
    "adb shell pm grant com.ezhou.adbconnect android.permission.WRITE_SECURE_SETTINGS"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The whole point of this app is that the service is already running before wireless
        // debugging drops -- start it here so a plain launch from the launcher is enough, without
        // relying on the user to separately know the service needs kicking off.
        ContextCompat.startForegroundService(this, Intent(this, AdbControlService::class.java))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        setContent {
            AdbConnectTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AdbConnectScreen()
                }
            }
        }
    }
}

private data class UiState(
    val serviceRunning: Boolean = false,
    val permissionGranted: Boolean = false,
    val debuggingEnabled: Boolean = false,
    val port: Int? = null,
    val token: String = "",
    val busy: Boolean = false,
    val lastError: String? = null,
)

@Composable
private fun AdbConnectScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(UiState(token = TokenStore.getOrCreate(context))) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val serviceRunning = withContext(Dispatchers.IO) { isServiceListening() }
            val status = AdbControlService.currentStatus(context, resolvePort = false)
            state = state.copy(
                serviceRunning = serviceRunning,
                permissionGranted = status.optBoolean("permissionGranted"),
                debuggingEnabled = status.optBoolean("debuggingEnabled"),
                port = if (status.isNull("port")) null else status.optInt("port"),
            )
            delay(3000)
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

private fun isServiceListening(): Boolean =
    try {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", AdbControlService.LISTEN_PORT), 500)
            true
        }
    } catch (e: IOException) {
        false
    }
