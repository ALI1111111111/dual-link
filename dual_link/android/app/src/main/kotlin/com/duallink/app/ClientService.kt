package com.duallink.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Runs on the phone without a SIM. Keeps one connection to the gateway, saves incoming SMS,
 * shows notifications and sends replies.
 *
 * Battery: no retries while off Wi-Fi (waits for Android to say Wi-Fi is back), exponential
 * back-off up to 60 s, a tiny keep-alive every 45 s, and network discovery only after failures.
 */
class ClientService : Service() {
    companion object {
        private const val TAG = "DualLinkClient"
        private const val PING_MS = 45_000L
        @Volatile var instance: ClientService? = null
        @Volatile var connected = false
        @Volatile var gatewayBattery: Int? = null
        /** Phone A's SIMs as last reported: [{id, slot, name}]. */
        @Volatile var gatewaySims: List<Map<String, Any>> = emptyList()

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ClientService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ClientService::class.java))

        /** Saves the message, then sends it now or as soon as the gateway is reachable. */
        /** [sim] = Phone A subscription id, or -1 for Phone A's default SMS SIM. */
        fun sendSms(ctx: Context, to: String, body: String, sim: Int = -1): String {
            val id = UUID.randomUUID().toString()
            Store.get(ctx).addMessage(id, to, body, System.currentTimeMillis(), 1, "queued", sim)
            Log.i(TAG, "UAT send_queued id=$id")
            instance?.flushQueue()
            return id
        }
    }

    private lateinit var prefs: Prefs
    @Volatile private var running = true
    @Volatile private var link: SecureLink? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var onWifi = false
    private val wake = Object() // connect loop sleeps on this; Wi-Fi changes wake it early
    private val writer = Executors.newSingleThreadExecutor()
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 2, notification("Connecting to Phone A…"), type)
        instance = this
        watchWifi()
        SystemSms.sync(this) // catch up if Dual Link became the default SMS app since last run
        Thread(::connectLoop, "client-conn").start()
        Thread(::pingLoop, "client-ping").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        instance = null
        connected = false
        stopDiscovery()
        runCatching { netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        runCatching { socket?.close() }
        synchronized(wake) { wake.notifyAll() }
        writer.shutdownNow()
        Bus.emit("client", mapOf("connected" to false))
        super.onDestroy()
    }

    private fun watchWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET) // emulators use this sometimes
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                onWifi = true
                Log.i(TAG, "UAT wifi_up")
                synchronized(wake) { wake.notifyAll() } // reconnect right away
            }
            override fun onLost(n: Network) {
                onWifi = false
                Log.i(TAG, "UAT wifi_down")
                runCatching { socket?.close() }
            }
        }
        netCallback = cb
        cm.registerNetworkCallback(req, cb)
        onWifi = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } ?: false
    }

    private fun sleep(ms: Long) = synchronized(wake) { if (running) wake.wait(ms) }

    private fun connectLoop() {
        var backoff = 1_000L
        var failures = 0
        while (running) {
            val host = prefs.host
            val key = prefs.key
            if (!onWifi || host == null || key == null) { sleep(10 * 60_000L); continue } // woken by Wi-Fi callback
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, prefs.port), 5_000)
                s.soTimeout = 10_000
                s.tcpNoDelay = true
                val l = SecureLink.clientHandshake(s.getInputStream().buffered(), s.getOutputStream(), key)
                s.soTimeout = 120_000 // gateway answers every ping, so silence = dead link
                link = l
                setConnected(true)
                stopDiscovery()
                backoff = 1_000L; failures = 0
                flushQueue()
                // Catch up on SMS history (first time: last 30 days; later: only what's new).
                write(JSONObject().put("t", "history").put("since", Store.get(this).newestHistoryTs()))
                while (running) {
                    val m = l.read() ?: break
                    handle(m)
                }
            } catch (e: Exception) {
                Log.i(TAG, "connection to $host failed: ${e.message}")
            } finally {
                runCatching { socket?.close() }
                link = null; socket = null
                setConnected(false)
            }
            if (!running) break
            if (++failures == 3) startDiscovery() // maybe the gateway's Wi-Fi address changed
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun pingLoop() {
        while (running) {
            Thread.sleep(PING_MS)
            if (link != null) write(JSONObject().put("t", "ping"))
        }
    }

    private fun write(m: JSONObject, onOk: (() -> Unit)? = null) {
        val l = link ?: return
        val s = socket
        runCatching {
            writer.execute {
                try { l.write(m); onOk?.invoke() } catch (e: Exception) { runCatching { s?.close() } }
            }
        }
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "sms" -> {
                val id = m.getString("id")
                val from = m.getString("from")
                val body = m.getString("body")
                val isNew = Store.get(this).addMessage(id, from, body, m.getLong("ts"), 0, "received", m.optInt("sim", -1))
                write(JSONObject().put("t", "ack").put("id", id)) // ack only after it is saved
                Log.i(TAG, "UAT sms_received id=$id new=$isNew")
                if (isNew) {
                    Notifier.sms(this, phoneKey(from), body)
                    SystemSms.sync(this)
                    Bus.emit("sms", mapOf("address" to phoneKey(from)))
                }
            }
            "hist" -> Store.get(this).addHistory(m.getString("id"), m.getString("addr"), m.getString("body"),
                m.getLong("ts"), m.getInt("dir"), m.optInt("sim", -1))
            "hist_done" -> {
                Log.i(TAG, "UAT history_done n=${m.optInt("n")}")
                SystemSms.sync(this)
                Bus.emit("sms", emptyMap())
            }
            "status" -> {
                val id = m.getString("id")
                val state = m.getString("state")
                val reason = m.optString("reason").ifEmpty { null }
                Store.get(this).setStatus(id, state, reason)
                Log.i(TAG, "UAT send_status id=$id state=$state${reason?.let { " reason=$it" } ?: ""}")
                Bus.emit("status", mapOf("id" to id, "state" to state))
                if (state == "sent" || state == "failed") SystemSms.sync(this)
            }
            "info" -> {
                gatewayBattery = m.optInt("battery")
                val a = m.optJSONArray("sims")
                if (a != null) gatewaySims = (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { mapOf("id" to it.getInt("id"), "slot" to it.getInt("slot"), "name" to it.getString("name")) }
                }
                Bus.emit("client", mapOf("connected" to true, "battery" to gatewayBattery))
            }
        }
    }

    /** Sends everything the gateway hasn't confirmed yet. Safe to repeat: the gateway drops duplicates. */
    fun flushQueue() {
        if (link == null) return
        runCatching {
            writer.execute {
                val store = Store.get(this)
                for (m in store.unconfirmedOutgoing()) {
                    val id = m.getString("id")
                    write(JSONObject().put("t", "send").put("id", id).put("to", m.getString("to"))
                        .put("body", m.getString("body")).put("sim", m.getInt("sim"))) {
                        store.setStatus(id, "sending")
                        Bus.emit("status", mapOf("id" to id, "state" to "sending"))
                    }
                }
            }
        }
    }

    private fun setConnected(c: Boolean) {
        if (connected == c) return
        connected = c
        Log.i(TAG, if (c) "UAT connected" else "UAT disconnected")
        getSystemService(android.app.NotificationManager::class.java)
            .notify(2, notification(if (c) "Connected to Phone A" else "Connecting to Phone A…"))
        Bus.emit("client", mapOf("connected" to c))
    }

    /** Looks for the gateway on the network; stops itself once connected or after 30 s. */
    private fun startDiscovery() {
        if (discovery != null) return
        val mgr = getSystemService(NSD_SERVICE) as NsdManager
        nsd = mgr
        val d = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { discovery = null }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                val key = prefs.key ?: return
                if (i.serviceName != nsdName(key)) return // someone else's Dual Link
                @Suppress("DEPRECATION")
                mgr.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val ip = i.host?.hostAddress ?: return
                        if (!connected && ip != prefs.host) {
                            Log.i(TAG, "gateway found at $ip:${i.port}")
                            prefs.host = ip; prefs.port = i.port
                            synchronized(wake) { wake.notifyAll() }
                        }
                    }
                })
            }
        }
        discovery = d
        runCatching { mgr.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, d) }.onFailure { discovery = null }
        Thread { Thread.sleep(30_000); stopDiscovery() }.start()
    }

    private fun stopDiscovery() {
        val d = discovery ?: return
        discovery = null
        runCatching { nsd?.stopServiceDiscovery(d) }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Dual Link")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
