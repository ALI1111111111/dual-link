package com.duallink.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * DEBUG BUILDS ONLY — lets the UAT script on the PC drive the app with `adb shell am broadcast`.
 * Not compiled into release builds.
 */
class TestHooks : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val p = Prefs(ctx)
        when (i.getStringExtra("cmd")) {
            "gateway" -> {
                if (p.key == null) p.key = Crypto.newKey()
                p.mode = "gateway"; p.enabled = true
                GatewayService.start(ctx)
                val qr = JSONObject().put("h", i.getStringExtra("host") ?: localIp()).put("p", p.port).put("k", p.key)
                Log.i("DualLinkTest", "UAT qr=$qr")
            }
            "pair" -> {
                p.host = i.getStringExtra("h")!!; p.port = i.getIntExtra("p", DEFAULT_PORT); p.key = i.getStringExtra("k")!!
                p.mode = "client"; p.enabled = true
                ClientService.stop(ctx); ClientService.start(ctx)
                Log.i("DualLinkTest", "UAT paired")
            }
            "send" -> ClientService.sendSms(ctx, i.getStringExtra("to")!!, i.getStringExtra("body")!!, i.getIntExtra("sim", -1))
            "rotate" -> { p.key = Crypto.newKey(); GatewayService.instance?.disconnectClient(); Log.i("DualLinkTest", "UAT rotated") }
            "stopgw" -> GatewayService.stop(ctx)
            "startgw" -> GatewayService.start(ctx)
            "syscount" -> {
                val n = runCatching {
                    ctx.contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, arrayOf("_id"), null, null, null)?.use { it.count }
                }.getOrNull() ?: -1
                Log.i("DualLinkTest", "UAT syscount n=$n")
            }
            "count" -> {
                val c = Store.get(ctx).readableDatabase.rawQuery(
                    // in/sent = this run's live messages (history rows start with "h-");
                    // dup = the same SMS stored twice (same person, text, direction, within 3 s).
                    "SELECT (SELECT COUNT(*) FROM messages WHERE dir=0 AND id NOT LIKE 'h-%'), " +
                        "(SELECT COUNT(*) FROM messages WHERE dir=1 AND status='sent' AND id NOT LIKE 'h-%'), " +
                        "(SELECT COUNT(*) FROM pending), (SELECT COUNT(*) FROM sent), " +
                        "(SELECT COUNT(*) FROM messages a JOIN messages b ON a.id < b.id AND a.address=b.address " +
                        "AND a.body=b.body AND a.dir=b.dir AND ABS(a.ts-b.ts) < 3000)", null)
                c.use { it.moveToFirst(); Log.i("DualLinkTest", "UAT count in=${it.getInt(0)} sent=${it.getInt(1)} pending=${it.getInt(2)} gw_sent=${it.getInt(3)} dup=${it.getInt(4)}") }
            }
        }
    }
}
