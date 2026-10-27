package com.ezhou.adbconnect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ezhou.adbconnect.MetricEvent
import com.ezhou.adbconnect.Metrics

/**
 * Wireless debugging is exactly what a reboot turns off, and a reboot is also the one moment
 * nobody can reach the phone over adb to start the service by hand -- this is the whole reason
 * the receiver exists.
 *
 * An update kills the process the same way, and an update pushed through `/install` happens
 * precisely when adb is unreachable, so the listener has to come back without anyone opening the
 * app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Metrics.record(context, MetricEvent.SERVICE_BOOT_START)
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        ContextCompat.startForegroundService(context, Intent(context, AdbControlService::class.java))
    }
}
