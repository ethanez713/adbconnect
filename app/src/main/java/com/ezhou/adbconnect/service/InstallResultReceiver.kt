package com.ezhou.adbconnect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.ezhou.adbconnect.MetricEvent
import com.ezhou.adbconnect.Metrics
import com.ezhou.adbconnect.pendingUpdateFile
import com.ezhou.adbconnect.postInstallPrompt

/**
 * Receives the asynchronous outcome of a PackageInstaller session committed by
 * [com.ezhou.adbconnect.stageAndPrompt]. The session commit does not install anything on its own:
 * a same-signed update to an already-installed app still needs the user's confirmation, which is
 * exactly the tap this app surfaces rather than tries to bypass.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                postInstallPrompt(context.applicationContext, confirm)
            }
            // STATUS_SUCCESS is not handled here: a self-update kills the process before it can be
            // delivered, so INSTALL_SUCCESS is counted on the next launch by clearStagedUpdateIfInstalled.
            PackageInstaller.STATUS_SUCCESS -> pendingUpdateFile(context.applicationContext).delete()
            else -> Metrics.record(context.applicationContext, MetricEvent.INSTALL_FAILED)
        }
    }
}
