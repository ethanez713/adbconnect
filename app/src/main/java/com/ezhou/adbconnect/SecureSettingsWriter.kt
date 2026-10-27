package com.ezhou.adbconnect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The one write this whole app exists to make. `adb_wifi_enabled` is a Settings.Global key with
 * no public constant, guarded by WRITE_SECURE_SETTINGS -- a permission apps cannot request at
 * runtime, only receive via `adb shell pm grant` while a wired or already-working connection is
 * available.
 */
object SecureSettingsWriter {
    private const val SETTING = "adb_wifi_enabled"

    fun isPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS,
        ) == PackageManager.PERMISSION_GRANTED

    fun isWirelessDebuggingEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING, 0) == 1

    /**
     * Returns true on success. A missing grant surfaces as a normal false here -- never a crash --
     * because the grant is expected to be absent until `tools/bootstrap.sh` (or the command shown
     * in the app) has been run once.
     */
    fun enableWirelessDebugging(context: Context): Boolean = write(context, 1)

    fun disableWirelessDebugging(context: Context): Boolean = write(context, 0)

    private fun write(context: Context, value: Int): Boolean =
        try {
            Settings.Global.putInt(context.contentResolver, SETTING, value)
        } catch (e: SecurityException) {
            false
        }
}
