package com.ezhou.adbconnect

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.ezhou.adbconnect.service.AwakeExpiryReceiver

private const val TAG = "StayAwake"
private const val PREFS = "stay_awake"
private const val KEY_ENABLED = "enabled"
private const val KEY_WINDOW_MINUTES = "window_minutes"
private const val KEY_DEADLINE_MS = "deadline_ms"
private const val KEY_WRITTEN = "written_value"

private const val DEFAULT_WINDOW_MINUTES = 30
private const val NO_WINDOW = 0L
private const val NOT_WRITTEN = -1

/** Every plug type: AC | USB | WIRELESS | DOCK, which is what Developer options' own toggle writes. */
private const val STAY_ON_ALL_SOURCES = 15
private const val STAY_ON_OFF = 0

private const val WAKE_LOCK_HOLD_MS = 1_000L

enum class ExpiryAction { REARM, CLEAR_SETTING, LEAVE_SETTING }

/** [current] differing from [written] means someone changed the setting mid-window; that change wins. */
internal fun expiryAction(current: Int, written: Int, nowMs: Long, deadlineMs: Long): ExpiryAction = when {
    nowMs < deadlineMs -> ExpiryAction.REARM
    current == written -> ExpiryAction.CLEAR_SETTING
    else -> ExpiryAction.LEAVE_SETTING
}

/**
 * Keeps the screen on while charging for a sliding window after the PC last touched the phone, so a
 * session is not cut off by the screen sleeping, the keyguard engaging, and Wi-Fi idling. The PC is
 * the only party that can see adb activity, so it pings [touch] through
 * [com.ezhou.adbconnect.service.AwakeReceiver]; each ping pushes the deadline out. The window
 * drives `stay_on_while_plugged_in`, which is a no-op on battery, so nothing here drains it.
 *
 * The app owns that setting while enabled: expiry writes it back to off, unless it no longer holds
 * the value this object wrote.
 */
object StayAwake {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun windowMinutes(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW_MINUTES, DEFAULT_WINDOW_MINUTES)

    /** Epoch millis the current window ends, or null when none is open. */
    fun deadlineMs(context: Context): Long? =
        prefs(context).getLong(KEY_DEADLINE_MS, NO_WINDOW).takeIf { it > System.currentTimeMillis() }

    fun setWindowMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_WINDOW_MINUTES, minutes).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) expire(context, nowMs = Long.MAX_VALUE)
    }

    fun isWakePermissionGranted(context: Context): Boolean =
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            "android:turn_screen_on",
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED

    fun touch(context: Context) {
        if (!isEnabled(context)) return
        val app = context.applicationContext
        wakeScreen(app)
        try {
            Settings.Global.putInt(app.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_ALL_SOURCES)
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; no window opened", e)
            return
        }
        val deadline = System.currentTimeMillis() + windowMinutes(app) * 60_000L
        prefs(app).edit()
            .putLong(KEY_DEADLINE_MS, deadline)
            .putInt(KEY_WRITTEN, STAY_ON_ALL_SOURCES)
            .apply()
        arm(app, deadline)
        Metrics.record(app, MetricEvent.AWAKE_PING)
    }

    fun expire(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val deadline = prefs(app).getLong(KEY_DEADLINE_MS, NO_WINDOW)
        if (deadline == NO_WINDOW) return
        val current = Settings.Global.getInt(
            app.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_OFF,
        )
        when (expiryAction(current, prefs(app).getInt(KEY_WRITTEN, NOT_WRITTEN), nowMs, deadline)) {
            ExpiryAction.REARM -> {
                arm(app, deadline)
                return
            }
            ExpiryAction.CLEAR_SETTING -> try {
                Settings.Global.putInt(app.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_OFF)
            } catch (e: SecurityException) {
                Log.w(TAG, "could not clear stay-awake", e)
            }
            ExpiryAction.LEAVE_SETTING -> Metrics.record(app, MetricEvent.AWAKE_USER_OVERRIDE)
        }
        prefs(app).edit().putLong(KEY_DEADLINE_MS, NO_WINDOW).putInt(KEY_WRITTEN, NOT_WRITTEN).apply()
        app.getSystemService(AlarmManager::class.java).cancel(expiryIntent(app))
    }

    /** Alarms do not survive a reboot, so the service start that follows one re-arms or expires the window. */
    fun reconcile(context: Context) {
        val app = context.applicationContext
        if (prefs(app).getLong(KEY_DEADLINE_MS, NO_WINDOW) == NO_WINDOW) return
        expire(app)
    }

    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "adbconnect:wake",
        )
        lock.acquire(WAKE_LOCK_HOLD_MS)
        if (!power.isInteractive) Metrics.record(context, MetricEvent.AWAKE_WAKE_IGNORED)
    }

    /** An inexact alarm: exact ones need a permission, and a few minutes' slack on a 30-minute window is fine. */
    private fun arm(context: Context, deadlineMs: Long) {
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineMs, expiryIntent(context))
    }

    private fun expiryIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AwakeExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
