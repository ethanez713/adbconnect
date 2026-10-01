package com.ezhou.adbconnect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ezhou.adbconnect.StayAwake

/** The PC's "a session is in progress" ping; sent with `adb shell am broadcast -n`. */
class AwakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = StayAwake.touch(context)
}

/** Alarm target that ends the window; the deadline check lives in [StayAwake.expire]. */
class AwakeExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = StayAwake.expire(context)
}
