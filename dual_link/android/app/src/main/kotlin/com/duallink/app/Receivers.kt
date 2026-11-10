package com.duallink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Gateway only: catches incoming SMS (long messages arrive in parts and are joined here). */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (Prefs(ctx).mode != "gateway") return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Which SIM it arrived on (subscription id); key name differs across Android versions.
        val sim = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX",
            intent.getIntExtra("subscription", -1))
        parts.groupBy { it.displayOriginatingAddress ?: "unknown" }.forEach { (from, list) ->
            val body = list.joinToString("") { it.displayMessageBody ?: "" }
            GatewayService.onSmsReceived(ctx, from, body, list.first().timestampMillis, sim)
        }
    }
}

/** Restarts the background service after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (!p.enabled) return
        runCatching {
            when (p.mode) {
                "gateway" -> GatewayService.start(ctx)
                "client" -> ClientService.start(ctx)
            }
        }
    }
}
