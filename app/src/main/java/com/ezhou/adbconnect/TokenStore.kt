package com.ezhou.adbconnect

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * On-device auth token for [com.ezhou.adbconnect.service.AdbControlService]'s HTTP endpoints.
 *
 * Persisted twice on purpose. SharedPreferences is what the app itself reads back; the plain file
 * under filesDir exists only so `tools/bootstrap.sh` can pull it off the device with
 * `adb shell run-as com.ezhou.adbconnect cat files/token.txt` -- the alternative is the user
 * copying 32 hex characters off a phone screen by hand.
 */
object TokenStore {
    private const val PREFS = "adbconnect_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val TOKEN_FILE = "token.txt"

    @Volatile
    private var cached: String? = null

    fun getOrCreate(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val token = prefs.getString(KEY_TOKEN, null) ?: generateToken().also { fresh ->
                prefs.edit().putString(KEY_TOKEN, fresh).apply()
            }
            writeTokenFile(context, token)
            cached = token
            return token
        }
    }

    private fun writeTokenFile(context: Context, token: String) {
        val file = File(context.filesDir, TOKEN_FILE)
        file.writeText(token)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
