package com.ezhou.adbconnect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Wireless debugging is exactly what a reboot turns off, and a reboot is also the one moment
 * nobody can reach the phone over adb to start the service by hand -- this is the whole reason
 * the receiver exists.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ContextCompat.startForegroundService(context, Intent(context, AdbControlService::class.java))
    }
}
