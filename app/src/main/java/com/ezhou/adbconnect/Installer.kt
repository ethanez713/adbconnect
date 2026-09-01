package com.ezhou.adbconnect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ezhou.adbconnect.service.InstallResultReceiver
import java.io.File
import java.security.MessageDigest

private const val TAG = "Installer"

/** Reject anything larger before a byte is read. A debug build of this app is tens of MB; a
 * push far past that is a mistake or an abuse, not a real update. */
const val MAX_APK_BYTES = 200L * 1024 * 1024

const val UPDATE_CHANNEL_ID = "adbconnect_update"
const val UPDATE_NOTIFICATION_ID = 2

/** The APK last accepted by [stageAndPrompt], kept so [MainActivity] can offer the install again
 * if the notification was dismissed or notifications are switched off entirely. */
private const val PENDING_APK = "pending-update.apk"

fun pendingUpdateFile(context: Context): File = File(context.filesDir, PENDING_APK)

sealed interface StageOutcome {
    data class Ok(val versionCode: Long, val versionName: String?) : StageOutcome
    data class Rejected(val reason: String) : StageOutcome
}

/**
 * The security boundary of the whole push channel. An adb peer that has the LAN token can hand this
 * app any file; the only thing that stops that being arbitrary-code-execution is that the file is
 * installed as an *update to this package*, and Android refuses an update whose signing certificate
 * differs from the installed one. We check the signer here too so a mismatched APK is rejected
 * before it ever reaches the system installer, but the authoritative check is the OS's own at
 * commit time -- this one is a fast, early no.
 *
 * [getPackageArchiveInfo] with GET_SIGNING_CERTIFICATES reads the APK's *claimed* signer; it does
 * not fully re-verify the v2/v3 block, which is why the OS check at install is what actually counts.
 */
fun verifyOwnSigner(context: Context, apk: File): StageOutcome {
    val pm = context.packageManager
    val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: return StageOutcome.Rejected("not a readable APK")
    if (archive.packageName != context.packageName) {
        return StageOutcome.Rejected("APK is ${archive.packageName}, not ${context.packageName}")
    }
    val pushed = signerDigests(archive.signingInfo?.apkContentsSigners)
    val own = signerDigests(
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners,
    )
    if (pushed.isEmpty() || own.isEmpty() || pushed.intersect(own).isEmpty()) {
        return StageOutcome.Rejected("APK is not signed by this app's key")
    }
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        archive.longVersionCode
    } else {
        @Suppress("DEPRECATION") archive.versionCode.toLong()
    }
    return StageOutcome.Ok(versionCode, archive.versionName)
}

/**
 * Verify the APK is a genuine update of this app, then commit a PackageInstaller session. Success
 * here means the session was *committed*, not that anything was installed: the system replies
 * asynchronously to [InstallResultReceiver] with a confirmation the user still has to tap.
 */
fun stageAndPrompt(context: Context, apk: File): StageOutcome {
    val verdict = verifyOwnSigner(context, apk)
    if (verdict is StageOutcome.Rejected) return verdict

    val staged = pendingUpdateFile(context)
    if (apk.absolutePath != staged.absolutePath) {
        apk.copyTo(staged, overwrite = true)
    }
    commitStagedUpdate(context, resultSender(context).intentSender)
    Metrics.record(context, MetricEvent.INSTALL_STAGED)
    return verdict
}

/**
 * Commit the already-verified [pendingUpdateFile] to a PackageInstaller session, reporting the
 * outcome to [statusSender]. The push path routes that to [InstallResultReceiver] (a notification);
 * the in-app button routes it to a receiver it owns so it can launch the confirm screen directly,
 * which a foreground activity may do and a background receiver may not.
 */
fun commitStagedUpdate(context: Context, statusSender: android.content.IntentSender) {
    val apk = pendingUpdateFile(context)
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setAppPackageName(context.packageName)
    val sessionId = installer.createSession(params)
    installer.openSession(sessionId).use { session ->
        session.openWrite("base.apk", 0, apk.length()).use { out ->
            apk.inputStream().use { it.copyTo(out) }
            session.fsync(out)
        }
        session.commit(statusSender)
    }
}

private fun resultSender(context: Context): PendingIntent {
    // Explicit component, not an implicit broadcast: background-launched implicit broadcasts do not
    // reach a manifest receiver on modern Android, and this commit often runs from the socket thread.
    val intent = Intent(context, InstallResultReceiver::class.java)
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // The system writes the status extras into this intent, so it must be mutable.
        flags = flags or PendingIntent.FLAG_MUTABLE
    }
    return PendingIntent.getBroadcast(context, 0, intent, flags)
}

/**
 * The install-confirm screen [EXTRA_INTENT] is an Activity start, and a background context (the
 * receiver that gets it) is barred from launching an activity directly. Wrapping it in a
 * notification hands the launch to the user's tap, which is allowed -- and is the "queue" the PC
 * asked for: the phone sits one tap from the update.
 */
fun postInstallPrompt(context: Context, confirm: Intent) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            UPDATE_CHANNEL_ID,
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
    }
    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
    val tap = PendingIntent.getActivity(context, 0, confirm, flags)
    val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
        .setContentTitle(context.getString(R.string.update_ready_title))
        .setContentText(context.getString(R.string.update_ready_text))
        .setSmallIcon(R.drawable.ic_notification)
        .setContentIntent(tap)
        .setAutoCancel(true)
        .build()
    try {
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
        // POST_NOTIFICATIONS ungranted. The in-app Install button is the fallback path.
        Log.w(TAG, "cannot post update notification; use the in-app button", e)
    }
}

/**
 * Drop the staged APK once it has actually been installed. Installing a self-update kills the
 * process, so the session's success callback is not a reliable place to clean up, and this app's
 * versionCode never moves between rebuilds -- so the honest test of "already installed" is that the
 * running APK's bytes equal the staged ones. Called on launch; a no-op in the normal case where
 * nothing is staged.
 */
fun clearStagedUpdateIfInstalled(context: Context) {
    val staged = pendingUpdateFile(context)
    if (!staged.exists()) return
    val running = File(context.applicationInfo.sourceDir)
    if (running.exists() && staged.length() == running.length() && sha256(staged) == sha256(running)) {
        staged.delete()
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun signerDigests(signatures: Array<Signature>?): Set<String> {
    if (signatures == null) return emptySet()
    val sha = MessageDigest.getInstance("SHA-256")
    return signatures.map { sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        .toSet()
}
