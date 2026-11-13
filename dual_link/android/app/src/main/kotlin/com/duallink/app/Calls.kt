package com.duallink.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Phone A: watches its own calls and lets Phone B decline / answer / end / dial.
 * Realme's own dialer stays the phone app; Dual Link only needs call permissions.
 * Call audio stays on Phone A (Android gives no app access to call voice without root).
 */
class GatewayCalls(private val ctx: Context, private val push: (JSONObject) -> Unit) {
    companion object { private const val TAG = "DualLinkCalls" }

    private val main = Handler(Looper.getMainLooper())
    private val telecom = ctx.getSystemService(TelecomManager::class.java)
    private val telephony = ctx.getSystemService(TelephonyManager::class.java)
    private val dialLimiter = RateLimiter(5)
    private var state = TelephonyManager.EXTRA_STATE_IDLE
    private var callId: String? = null
    private var number: String? = null
    private var sim = -1
    private var answered = false
    private var outgoing = false
    private var dialed: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val sub = i.getIntExtra("subscription", i.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
            onState(st, num, sub)
        }
    }

    fun start() {
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED) // sent by the system
    }

    fun stop() { runCatching { ctx.unregisterReceiver(receiver) } }

    val inCall: Boolean get() = state != TelephonyManager.EXTRA_STATE_IDLE

    /** Current call state for a (re)connecting Phone B, so it never keeps showing a call that ended. */
    @Synchronized
    fun snapshot(): JSONObject {
        val st = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "active"
            else -> "ended"
        }
        return JSONObject().put("t", "call").put("id", callId).put("state", st).put("number", number ?: "")
            .put("sim", sim).put("ts", System.currentTimeMillis()).put("missed", false).put("sync", true)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun event(st: String, extra: JSONObject.() -> Unit = {}) {
        val m = JSONObject().put("t", "call").put("id", callId).put("state", st)
            .put("number", number ?: "").put("sim", sim).put("ts", System.currentTimeMillis())
        m.extra()
        Log.i(TAG, "UAT call_state $st")
        push(m)
    }

    /** Android sends RINGING twice (with and without the number); both are handled. */
    @Synchronized
    private fun onState(st: String, num: String?, sub: Int) {
        when (st) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val fresh = state != st
                if (fresh) { callId = UUID.randomUUID().toString(); answered = false; outgoing = false; number = null; sim = sub }
                if (sub >= 0) sim = sub
                val gotNumber = !num.isNullOrEmpty() && number != num
                if (gotNumber) number = num
                state = st
                if (fresh || gotNumber) event("ringing")
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (state == TelephonyManager.EXTRA_STATE_RINGING) { answered = true; state = st; event("active") }
                else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    callId = UUID.randomUUID().toString(); outgoing = true; number = dialed; sim = if (sub >= 0) sub else sim
                    state = st; event("active") { put("outgoing", true) }
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (state == st) return
                val missed = state == TelephonyManager.EXTRA_STATE_RINGING && !answered
                state = st
                event("ended") { put("missed", missed); put("outgoing", outgoing) }
                dialed = null
                main.postDelayed({ sendLog(System.currentTimeMillis() - 10 * 60_000L) }, 2_000) // newest entry
            }
        }
    }

    /** Commands from Phone B. */
    @SuppressLint("MissingPermission")
    fun command(m: JSONObject) {
        val cmd = m.optString("cmd")
        val result = runCatching {
            when (cmd) {
                "decline", "end" -> {
                    if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) error("no_permission")
                    @Suppress("DEPRECATION") telecom.endCall()
                }
                "answer" -> {
                    if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) error("no_permission")
                    @Suppress("DEPRECATION") telecom.acceptRingingCall()
                    true
                }
                "dial" -> {
                    if (!granted(Manifest.permission.CALL_PHONE)) error("no_permission")
                    val to = SendPolicy.normalize(m.optString("number"))
                    if (!SendPolicy.validNumber(to)) error("invalid_number")
                    if (!dialLimiter.tryAcquire()) error("rate_limited")
                    dialed = to
                    val extras = Bundle()
                    handleFor(m.optInt("sim", -1))?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
                    telecom.placeCall(Uri.fromParts("tel", to, null), extras)
                    true
                }
                else -> error("unknown")
            }
        }
        Log.i(TAG, "UAT call_cmd $cmd ok=${result.isSuccess}${result.exceptionOrNull()?.let { " reason=${it.message}" } ?: ""}")
        push(JSONObject().put("t", "callcmd_result").put("cmd", cmd).put("ok", result.isSuccess)
            .put("reason", result.exceptionOrNull()?.message ?: ""))
    }

    @SuppressLint("MissingPermission")
    private fun handleFor(subId: Int): PhoneAccountHandle? {
        if (subId < 0 || !granted(Manifest.permission.READ_PHONE_STATE)) return null
        return runCatching { telecom.callCapablePhoneAccounts.firstOrNull { telephony.getSubscriptionId(it) == subId } }.getOrNull()
    }

    /** Call history newer than [since] (max 30 days, 500 calls), sent in chunks. */
    @SuppressLint("MissingPermission")
    fun sendLog(since: Long) {
        if (!granted(Manifest.permission.READ_CALL_LOG)) return
        Thread {
            val subOf = HashMap<String, Int>()
            runCatching { telecom.callCapablePhoneAccounts.forEach { subOf[it.id] = telephony.getSubscriptionId(it) } }
            val from = maxOf(since, System.currentTimeMillis() - 30L * 86_400_000L)
            val items = JSONArray()
            runCatching {
                ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
                        CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID),
                    // No "LIMIT" in sortOrder: the call log provider rejects it (seen on Realme, Android 13).
                    "${CallLog.Calls.DATE} > ?", arrayOf(from.toString()), "${CallLog.Calls.DATE} DESC")?.use { c ->
                    var count = 0
                    while (c.moveToNext() && count++ < 500) {
                        items.put(JSONObject().put("id", "c-" + c.getLong(0)).put("number", c.getString(1) ?: "")
                            .put("type", c.getInt(2)).put("ts", c.getLong(3)).put("dur", c.getLong(4))
                            .put("sim", subOf[c.getString(5) ?: ""] ?: c.getString(5)?.toIntOrNull() ?: -1))
                        if (items.length() == 100) { push(JSONObject().put("t", "calls").put("items", JSONArray(items.toString()))); while (items.length() > 0) items.remove(0) }
                    }
                }
            }.onFailure { Log.w(TAG, "call log read failed: ${it.message}") }
            push(JSONObject().put("t", "calls").put("items", items).put("done", true))
        }.start()
    }
}

/** Phone B: rings for calls on Phone A, shows missed calls and the in-call banner. */
object ClientCalls {
    private const val TAG = "DualLinkCalls"
    const val NOTIF_CALL = 9001
    const val ACTION_DECLINE = "com.duallink.app.CALL_DECLINE"
    const val ACTION_ANSWER = "com.duallink.app.CALL_ANSWER"
    const val ACTION_END = "com.duallink.app.CALL_END"

    /** Random per process; only our own notification knows it, so other apps can't trigger "answer". */
    val launchToken: String = Crypto.newKey()

    /** The call currently ringing / active on Phone A, for the UI. */
    @Volatile var current: Map<String, Any?>? = null

    fun handle(ctx: Context, m: JSONObject) {
        val st = m.getString("state")
        val number = phoneKey(m.optString("number"))
        val sim = m.optInt("sim", -1)
        Log.i(TAG, "UAT call_$st missed=${m.optBoolean("missed")}")
        when (st) {
            "ringing" -> { current = mapOf("state" to st, "number" to number, "sim" to sim, "since" to m.optLong("ts")); ring(ctx, number, sim) }
            "active" -> { current = mapOf("state" to st, "number" to number, "sim" to sim, "since" to m.optLong("ts"), "outgoing" to m.optBoolean("outgoing")); ongoing(ctx, number) }
            "ended" -> {
                current = null
                ClientVoice.stop(ctx)
                NotificationManagerCompat.from(ctx).cancel(NOTIF_CALL)
                if (m.optBoolean("missed")) missed(ctx, number)
            }
        }
        Bus.emit("call", mapOf("state" to st, "number" to number))
    }

    private fun simLabel(sim: Int): String =
        ClientService.gatewaySims.firstOrNull { it["id"] == sim }?.let { "SIM ${it["slot"]} · ${it["name"]}" } ?: "Phone A"

    private fun pi(ctx: Context, action: String, number: String) = PendingIntent.getBroadcast(ctx, action.hashCode(),
        Intent(ctx, CallActionReceiver::class.java).setAction(action).putExtra("number", number),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp(ctx: Context, number: String?) = PendingIntent.getActivity(ctx, 7,
        Intent(ctx, MainActivity::class.java).putExtra("callScreen", true).putExtra("address", number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun ring(ctx: Context, number: String, sim: Int) {
        ensureCallChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Incoming call · ${simLabel(sim)}")
            .setContentText(displayName(ctx, number))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(openApp(ctx, number), true)
            .setContentIntent(openApp(ctx, number))
            .addAction(R.drawable.ic_stat_link, "Decline", pi(ctx, ACTION_DECLINE, number))
            // Opens the app (needed for the mic), which answers and starts audio on this phone.
            .addAction(R.drawable.ic_stat_link, "Answer here", PendingIntent.getActivity(ctx, 8,
                Intent(ctx, MainActivity::class.java).putExtra("answerHere", true).putExtra("address", number)
                    .putExtra("token", launchToken)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build().apply { flags = flags or android.app.Notification.FLAG_INSISTENT } // keep ringing
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_CALL, n) }
    }

    private fun ongoing(ctx: Context, number: String) {
        ensureCallChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("On a call on Phone A")
            .setContentText(displayName(ctx, number))
            .setOngoing(true)
            .setUsesChronometer(true)
            .setContentIntent(openApp(ctx, number))
            .addAction(R.drawable.ic_stat_link, "End call", pi(ctx, ACTION_END, number))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_CALL, n) }
    }

    private fun missed(ctx: Context, number: String) {
        val n = NotificationCompat.Builder(ctx, CH_SMS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Missed call")
            .setContentText(displayName(ctx, number))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, number))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(("missed" + number).hashCode(), n) }
    }
}

/** Decline / Answer on Phone A / End from Phone B's notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val cmd = when (i.action) {
            ClientCalls.ACTION_DECLINE -> "decline"
            ClientCalls.ACTION_ANSWER -> "answer"
            ClientCalls.ACTION_END -> "end"
            else -> return
        }
        if (cmd == "decline") NotificationManagerCompat.from(ctx).cancel(ClientCalls.NOTIF_CALL) // stop ringing now
        ClientService.callCommand(JSONObject().put("cmd", cmd))
    }
}

const val CH_CALL = "calls"

fun ensureCallChannels(ctx: Context) {
    ensureChannels(ctx)
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    nm.createNotificationChannel(android.app.NotificationChannel(CH_CALL, "Incoming calls", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
        lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
    })
}
