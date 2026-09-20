package com.ezhou.adbconnect.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.ezhou.adbconnect.MetricEvent
import com.ezhou.adbconnect.Metrics
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "KeepAliveJob"
private const val JOB_ID = 4787
private const val INTERVAL_MS = 15 * 60 * 1000L

/**
 * The rung that catches the process dying between events. Foreground + START_STICKY + the boot
 * receiver all restart the service on an *event* (launch, reboot, redelivery); none of them fire
 * when Android reclaims the process under memory pressure while the phone sits idle -- which is
 * exactly when the PC later finds port [AdbControlService.LISTEN_PORT] refusing connections. A
 * persisted periodic job is scheduled at the system level, so it runs in a fresh process even
 * after the app's own is gone, and re-launches the service if its socket has stopped answering.
 *
 * Not a cure-all: a force-stopped or "deep sleeping" (OEM-parked) app receives no jobs at all
 * until it is launched by hand, and starting a foreground service from the background succeeds
 * only while the app is battery-optimisation-exempt -- the same "Reachable while idle" state the
 * service already depends on.
 */
class KeepAliveJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        ensureServiceRunning(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJob::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}

fun ensureServiceRunning(context: Context) {
    if (isAdbServiceListening()) return
    try {
        ContextCompat.startForegroundService(context, Intent(context, AdbControlService::class.java))
        Metrics.record(context, MetricEvent.SERVICE_KEEPALIVE_RESTART)
    } catch (e: Exception) {
        // Android 12+ bars a background start of a foreground service unless the app is
        // battery-optimisation-exempt. If it throws, the app is not exempt and nothing here can
        // fix that -- the in-app "Exempt from battery optimisation" prompt is the only lever.
        Log.w(TAG, "could not restart service from background", e)
    }
}

fun isAdbServiceListening(): Boolean =
    try {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", AdbControlService.LISTEN_PORT), 500)
            true
        }
    } catch (e: IOException) {
        false
    }
