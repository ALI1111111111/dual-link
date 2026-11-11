package com.duallink.app

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.telephony.SubscriptionManager
import org.json.JSONArray
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs on the phone with the SIM. Listens on the local Wi-Fi for the paired client,
 * forwards received SMS to it and sends SMS on its behalf.
 *
 * Battery: threads just sit blocked on the socket; no timers, no Wi-Fi lock, no wakelock.
 */
class GatewayService : Service() {
    companion object {
        private const val TAG = "DualLinkGateway"
        private const val ACTION_SENT = "com.duallink.app.SMS_SENT"
        @Volatile var instance: GatewayService? = null
        @Volatile var clientConnected = false

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, GatewayService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, GatewayService::class.java))

        /** Called by SmsReceiver. Stored first, so nothing is lost if the client is offline. */
        fun onSmsReceived(ctx: Context, from: String, body: String, ts: Long, sim: Int) {
            val id = UUID.randomUUID().toString()
            val msg = JSONObject().put("t", "sms").put("id", id).put("from", from).put("body", body).put("ts", ts).put("sim", sim)
            Store.get(ctx).addPending(id, msg)
            Log.i(TAG, "UAT sms_in id=$id")
            val svc = instance
            if (svc != null) svc.push(msg) else if (Prefs(ctx).enabled) runCatching { start(ctx) }
            Bus.emit("gateway", mapOf("pending" to Store.get(ctx).pendingCount()))
        }
    }

    private lateinit var prefs: Prefs
    private var server: ServerSocket? = null
    @Volatile private var link: SecureLink? = null
    @Volatile private var socket: Socket? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val reqCode = AtomicInteger(1)
    private val handshakes = Semaphore(4)          // limits strangers hammering the port
    private val writer = Executors.newSingleThreadExecutor() // keeps outgoing frames in order
    private val simQueue = Executors.newSingleThreadExecutor() // SMS go out in the order they were written
    private val limiter = RateLimiter(SendPolicy.MAX_PER_MINUTE)
    private var calls: GatewayCalls? = null
    var voice: GatewayVoice? = null
        private set
    // send id -> [parts remaining, failed?, first error code]
    private val sending = HashMap<String, IntArray>()

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getStringExtra("id") ?: return
            val s = synchronized(sending) {
                val s = sending[id] ?: return
                if (resultCode != Activity.RESULT_OK && s[1] == 0) { s[1] = 1; s[2] = resultCode }
                s[0]--
                if (s[0] > 0) return
                sending.remove(id)
                s
            }
            if (s[1] == 1) finishSend(id, "failed", simError(s[2])) else finishSend(id, "sent")
        }
    }

    /** Human-meaningful reason for a SIM send failure (codes from SmsManager.RESULT_*). */
    private fun simError(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE, 18 /* RESULT_RIL_RADIO_NOT_AVAILABLE */ -> "no_service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED, SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "blocked_by_phone"
        10 /* RESULT_NETWORK_REJECT */, 16 /* RESULT_MODEM_ERROR */, 17 /* RESULT_NETWORK_ERROR */,
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "network_rejected"
        else -> "sim_error_$code"
    }

    /** Active SIMs on this phone (needs READ_PHONE_STATE; empty list if not granted). */
    fun sims(): JSONArray {
        val out = JSONArray()
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return out
        runCatching {
            val sm = getSystemService(SubscriptionManager::class.java)
            sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }.forEach {
                out.put(JSONObject().put("id", it.subscriptionId).put("slot", it.simSlotIndex + 1)
                    .put("name", (it.carrierName ?: it.displayName ?: "SIM").toString()))
            }
        }
        return out
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, notification("Waiting for Phone B…"), type)
        instance = this
        ContextCompat.registerReceiver(this, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        voice = GatewayVoice(this) { push(it) }.also { it.start() }
        calls = GatewayCalls(this) {
            if (it.optString("state") == "ended") voice?.callEnded()
            push(it)
        }.also { it.start() }
        Thread(::acceptLoop, "gw-accept").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        clientConnected = false
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        runCatching { server?.close() }
        runCatching { socket?.close() }
        runCatching { unregisterReceiver(sentReceiver) }
        calls?.stop()
        voice?.stop()
        writer.shutdownNow()
        simQueue.shutdown() // let already-accepted SMS finish
        Bus.emit("gateway", mapOf("running" to false, "connected" to false))
        super.onDestroy()
    }

    /** Drops the current client (used after the pairing key is changed). */
    fun disconnectClient() { runCatching { socket?.close() } }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(prefs.port).also { server = it }
            registerNsd(ss.localPort)
            Bus.emit("gateway", mapOf("running" to true))
            while (!ss.isClosed) {
                val s = ss.accept()
                if (!handshakes.tryAcquire()) { runCatching { s.close() }; continue }
                Thread({ handleClient(s) }, "gw-client").start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "server stopped: ${e.message}")
        }
    }

    private fun handleClient(s: Socket) {
        val l: SecureLink
        try {
            val key = prefs.key ?: throw ProtocolException("no key")
            s.soTimeout = 10_000 // handshake must be quick
            s.tcpNoDelay = true
            l = SecureLink.serverHandshake(s.getInputStream().buffered(), s.getOutputStream(), key)
        } catch (e: Exception) {
            Log.w(TAG, "rejected connection from ${s.inetAddress?.hostAddress}: ${e.message}")
            runCatching { s.close() }
            return
        } finally {
            handshakes.release()
        }
        try {
            s.soTimeout = 120_000 // client pings every 45 s
            // Only one client at a time: newest wins.
            runCatching { socket?.close() }
            socket = s; link = l; clientConnected = true
            Log.i(TAG, "UAT client_connected")
            update("Connected to Phone B")
            push(info())
            push(JSONObject().put("t", "voice_status").put("s", voice?.helperStatus ?: "not_started"))
            Store.get(this).allPending().forEach { push(it) }
            while (true) {
                val m = l.read() ?: break
                when (m.optString("t")) {
                    "ack" -> {
                        Store.get(this).removePending(m.getString("id"))
                        Log.i(TAG, "UAT sms_acked id=${m.getString("id")}")
                        Bus.emit("gateway", mapOf("pending" to Store.get(this).pendingCount()))
                    }
                    // SIM sends happen one by one on their own queue so this reader keeps handling acks/pings.
                    "send" -> {
                        val id = m.getString("id"); val to = m.getString("to"); val body = m.getString("body")
                        val sim = m.optInt("sim", -1)
                        runCatching { simQueue.execute { handleSend(id, to, body, sim) } }
                    }
                    "ping" -> push(info())
                    "history" -> { val since = m.optLong("since"); Thread({ sendHistory(since) }, "gw-history").start() }
                    "calllog" -> calls?.sendLog(m.optLong("since"))
                    "callcmd" -> calls?.command(m)
                    "voice", "va" -> voice?.fromClient(m)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "client dropped: ${e.message}")
        } finally {
            runCatching { s.close() }
            if (socket === s) {
                socket = null; link = null; clientConnected = false
                Log.i(TAG, "UAT client_disconnected")
                update("Waiting for Phone B…")
            }
        }
    }

    private fun info(): JSONObject {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return JSONObject().put("t", "info")
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("model", Build.MODEL)
            .put("sims", sims())
    }

    fun push(msg: JSONObject) {
        val l = link ?: return
        val s = socket
        runCatching {
            writer.execute { runCatching { l.write(msg) }.onFailure { runCatching { s?.close() } } }
        }
    }

    /** Sends SMS from this phone's own inbox/sent box newer than [since] (max 30 days, 1000 messages). */
    private fun sendHistory(since: Long) {
        var n = 0
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            val from = maxOf(since, System.currentTimeMillis() - 30L * 86_400_000L)
            runCatching {
                contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf("_id", "address", "body", "date", "date_sent", "type", "sub_id"),
                    // Skip SMS that Dual Link itself sent: Phone B already has them.
                    "date > ? AND type IN (1,2) AND (creator IS NULL OR creator != ?)",
                    arrayOf(from.toString(), packageName), "date DESC LIMIT 1000"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val inbox = c.getInt(5) == 1
                        val sentAt = c.getLong(4)
                        push(JSONObject().put("t", "hist").put("id", "h-" + c.getLong(0))
                            .put("addr", c.getString(1) ?: continue).put("body", c.getString(2) ?: "")
                            .put("ts", if (inbox && sentAt > 0) sentAt else c.getLong(3)) // matches live relay time
                            .put("dir", if (inbox) 0 else 1).put("sim", c.getInt(6)))
                        n++
                    }
                }
            }.onFailure { Log.w(TAG, "history read failed: ${it.message}") }
        }
        Log.i(TAG, "UAT history_sent n=$n")
        push(JSONObject().put("t", "hist_done").put("n", n))
    }

    private fun handleSend(id: String, rawTo: String, body: String, sim: Int) {
        // Retry of something already handled? Just repeat the result; never send twice.
        Store.get(this).claimSend(id)?.let { prev ->
            Log.i(TAG, "UAT send_duplicate id=$id state=$prev")
            if (prev != "sending") push(status(id, prev))
            return
        }
        val to = SendPolicy.normalize(rawTo)
        val simIds = sims().let { a -> (0 until a.length()).map { a.getJSONObject(it).getInt("id") } }
        val reason = when {
            !SendPolicy.validNumber(to) -> "invalid_number"
            !SendPolicy.validBody(body) -> "invalid_body"
            sim >= 0 && simIds.isNotEmpty() && sim !in simIds -> "sim_not_available"
            !limiter.tryAcquire() -> "rate_limited"
            else -> null
        }
        if (reason != null) return finishSend(id, "failed", reason)
        try {
            val base: SmsManager = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
            // -1 = phone's default SMS SIM
            val sm = if (sim >= 0) base.createForSubscriptionId(sim) else base
            val parts = sm.divideMessage(body)
            synchronized(sending) { sending[id] = intArrayOf(parts.size, 0, 0) }
            val intents = ArrayList(parts.map {
                val i = Intent(ACTION_SENT).setPackage(packageName).putExtra("id", id)
                PendingIntent.getBroadcast(this, reqCode.getAndIncrement(), i,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            })
            Log.i(TAG, "UAT send_start id=$id parts=${parts.size} sim=$sim")
            sm.sendMultipartTextMessage(to, null, parts, intents, null)
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            synchronized(sending) { sending.remove(id) }
            finishSend(id, "failed", "sim_error")
        }
    }

    private fun finishSend(id: String, state: String, reason: String? = null) {
        Store.get(this).setSendState(id, state)
        Log.i(TAG, "UAT send_result id=$id state=$state${reason?.let { " reason=$it" } ?: ""}")
        push(status(id, state, reason))
    }

    private fun status(id: String, state: String, reason: String? = null) =
        JSONObject().put("t", "status").put("id", id).put("state", state).apply { reason?.let { put("reason", it) } }

    /** Re-announce under the new name after the pairing key changes. */
    fun reRegisterNsd() {
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        server?.takeIf { !it.isClosed }?.let { registerNsd(it.localPort) }
    }

    private fun registerNsd(port: Int) {
        val key = prefs.key ?: return
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply { serviceName = nsdName(key); serviceType = NSD_TYPE; this.port = port }
        nsdListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener) }
    }

    private fun update(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(1, notification(text))
        Bus.emit("gateway", mapOf("running" to true, "connected" to clientConnected))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Dual Link gateway")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
