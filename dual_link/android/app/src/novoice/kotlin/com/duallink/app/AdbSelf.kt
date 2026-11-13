package com.duallink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Play Store build: "talk from Phone B" is not included (no Wireless debugging, no extra
 * permissions). Same API as the full build so the rest of the app is unchanged.
 */
object AdbSelf {
    fun devOptionsOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun state(ctx: Context): Map<String, Any?> = mapOf("supported" to false, "voiceOn" to false, "paired" to false)
    fun startPairing(ctx: Context) {}
    fun pair(ctx: Context, code: String) {}
    fun ensureHelper(ctx: Context, force: Boolean = false): Boolean = false
}

class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {}
}
