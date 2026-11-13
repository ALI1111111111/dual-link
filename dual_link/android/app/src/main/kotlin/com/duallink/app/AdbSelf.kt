package com.duallink.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phone A starts its own voice helper with no PC: Dual Link connects to this phone's own
 * Wireless debugging (localhost only, same idea as Shizuku) and runs the helper as the shell user,
 * which Android lets use call-audio interception. The user pairs once with the code Android shows.
 *
 * Afterwards Dual Link can re-enable Wireless debugging itself (WRITE_SECURE_SETTINGS, granted once
 * through that same connection), so reboots and Wi-Fi drops recover without the user.
 */
object AdbSelf {
    private const val TAG = "DualLinkAdb"
    private const val KEY_FILE = "/data/local/tmp/duallink.k"
    const val ACTION_PAIR_CODE = "com.duallink.app.ADB_PAIR_CODE"
    private const val NOTIF_PAIR = 9101
    @Volatile private var mgr: Manager? = null
    @Volatile var lastError: String? = null
        private set
    @Volatile var busy = false
        private set

    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val p = Prefs(ctx)
            val storedKey = p.adbKey
            val storedCert = p.adbCert
            if (storedKey != null && storedCert != null) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Crypto.unb64(storedKey)))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Crypto.unb64(storedCert)))
            } else {
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                key = kp.private
                cert = selfSigned(kp.public.encoded, kp.private)
                p.adbKey = Crypto.b64(key.encoded)   // encrypted with the Android Keystore (see Prefs)
                p.adbCert = Crypto.b64(cert.encoded)
            }
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "DualLink"

        /** Minimal self-signed X.509 certificate; adbd only checks the public key. */
        private fun selfSigned(publicKey: ByteArray, priv: PrivateKey): Certificate {
            val alg = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val name = X500Name("CN=DualLink")
            val now = System.currentTimeMillis()
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger.valueOf(now)))
                setIssuer(name); setSubject(name)
                setStartDate(Time(Date(now - 86_400_000L)))
                setEndDate(Time(Date(now + 30L * 365 * 86_400_000L)))
                setSignature(alg)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(publicKey))
            }.generateTBSCertificate()
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(priv); update(tbs.encoded) }.sign()
            val v = ASN1EncodableVector().apply { add(tbs); add(alg); add(DERBitString(sig)) }
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(DERSequence(v).encoded))
        }
    }

    private fun manager(ctx: Context) = mgr ?: synchronized(this) { mgr ?: Manager(ctx.applicationContext).also { mgr = it } }

    fun devOptionsOn(ctx: Context) =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun wirelessDebuggingOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1

    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun state(ctx: Context): Map<String, Any?> = mapOf(
        "devOptions" to devOptionsOn(ctx),
        "wirelessDebugging" to wirelessDebuggingOn(ctx),
        "paired" to Prefs(ctx).adbPaired,
        "voiceOn" to Prefs(ctx).voiceOn,
        "autoRecover" to canWriteSecure(ctx),
        "busy" to busy,
        "watching" to watching,
        "codeScreen" to codeScreen,
        "error" to lastError,
        "supported" to (Build.VERSION.SDK_INT >= 33), // call-audio interception API
    )

    // ---------------------------------------------------------------- pairing

    @Volatile private var watching = false
    @Volatile private var codeScreen = false

    /**
     * Guided pairing: watches for Android's pairing-code screen (up to 5 min) and pops the
     * "type the code" notification the moment it opens, so the user never has to hunt for it.
     */
    fun startPairing(ctx: Context) {
        val app = ctx.applicationContext
        if (watching) return
        watching = true; codeScreen = false; lastError = null
        guide(app)
        Thread {
            val until = System.currentTimeMillis() + 300_000
            try {
                while (watching && !Prefs(app).adbPaired && System.currentTimeMillis() < until) {
                    val open = findService(app, "_adb-tls-pairing._tcp", 4_000) != null
                    if (open && !codeScreen) {
                        codeScreen = true
                        NotificationManagerCompat.from(app).cancel(NOTIF_PAIR) // re-alert as a pop-up
                        askForCode(app)
                        Log.i(TAG, "UAT adb_code_screen")
                    } else if (!open && codeScreen && !busy) {
                        codeScreen = false
                        guide(app)
                    }
                    Bus.emit("adb", state(app))
                    Thread.sleep(1_000)
                }
            } finally {
                watching = false; codeScreen = false
                if (!Prefs(app).adbPaired && !busy) NotificationManagerCompat.from(app).cancel(NOTIF_PAIR)
                Bus.emit("adb", state(app))
            }
        }.start()
    }

    private fun guide(ctx: Context) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_SMS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Dual Link voice setup")
            .setContentText("Tap \"Wireless debugging\", then \"Pair device with pairing code\".")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "In Settings: tap the words \"Wireless debugging\", then \"Pair device with pairing code\". " +
                    "Dual Link will ask for the code as soon as it appears."))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_PAIR, n) }
    }

    /** Shows a notification where the user types the 6-digit code from "Pair device with pairing code". */
    fun askForCode(ctx: Context) {
        ensureChannels(ctx)
        val reply = PendingIntent.getBroadcast(ctx, 41, Intent(ctx, AdbPairReceiver::class.java).setAction(ACTION_PAIR_CODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE) // mutable: RemoteInput fills it
        val action = NotificationCompat.Action.Builder(R.drawable.ic_stat_link, "Enter code", reply)
            .addRemoteInput(RemoteInput.Builder("code").setLabel("6-digit pairing code").build()).build()
        val n = NotificationCompat.Builder(ctx, CH_SMS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Type the 6-digit code here")
            .setContentText("Tap \"Enter code\" and type the code shown behind this message.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(action)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_PAIR, n) }
    }

    private fun progress(ctx: Context, text: String, done: Boolean = false) {
        val n = NotificationCompat.Builder(ctx, CH_SMS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Dual Link voice setup")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setAutoCancel(done)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_PAIR, n) }
        Bus.emit("adb", state(ctx))
    }

    /** Finds the pairing port Android is advertising right now (only while the pairing dialog is open). */
    private fun findService(ctx: Context, type: String, timeoutMs: Long): Int? {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val latch = CountDownLatch(1)
        var port: Int? = null
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        // Only this phone's own service, never another device on the Wi-Fi.
                        @Suppress("DEPRECATION")
                        val ip = i.host?.hostAddress
                        if (ip != null && isOwnAddress(ip)) { port = i.port; latch.countDown() }
                    }
                })
            }
        }
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return port
    }

    private fun isOwnAddress(ip: String): Boolean = ip.startsWith("127.") || runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.any { it.hostAddress?.substringBefore('%') == ip.substringBefore('%') }
    }.getOrDefault(false)

    fun pair(ctx: Context, code: String) {
        val app = ctx.applicationContext
        if (!code.matches(Regex("\\d{6}"))) { lastError = "The pairing code is 6 digits"; progress(app, lastError!!); return }
        Thread {
            busy = true
            try {
                progress(app, "Pairing…")
                val port = findService(app, "_adb-tls-pairing._tcp", 8_000)
                    ?: throw IllegalStateException("Pairing dialog not found. Open \"Pair device with pairing code\" and try again.")
                if (!manager(app).pair("127.0.0.1", port, code)) throw IllegalStateException("Wrong code, try again")
                Prefs(app).adbPaired = true
                watching = false
                lastError = null
                Log.i(TAG, "UAT adb_paired")
                progress(app, "Paired. Starting voice…")
                busy = false
                ensureHelper(app, force = true)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "pair failed: $lastError")
                progress(app, "Pairing failed: $lastError", done = true)
            } finally {
                busy = false
                Bus.emit("adb", state(app))
            }
        }.start()
    }

    // ---------------------------------------------------------------- start helper

    private fun shell(m: Manager, cmd: String): String =
        m.openStream("shell:$cmd").use { s -> s.openInputStream().bufferedReader().readText() }

    /**
     * Makes sure the voice helper runs. Blocking; call from a background thread.
     * Returns true if a fresh helper was launched.
     */
    @Synchronized
    fun ensureHelper(ctx: Context, force: Boolean = false): Boolean {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (!p.adbPaired || !p.voiceOn || Build.VERSION.SDK_INT < 33) return false
        busy = true
        Bus.emit("adb", state(app))
        try {
            if (!wirelessDebuggingOn(app) && canWriteSecure(app) && localIp() != null) {
                Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", 1)
                Log.i(TAG, "UAT adb_wifi_reenabled")
                Thread.sleep(2_500)
            }
            val m = manager(app)
            if (!m.isConnected && !m.autoConnect(app, 10_000)) throw IllegalStateException("Wireless debugging is off")
            if (!canWriteSecure(app)) shell(m, "pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
            val key = Crypto.newKey()
            val apk = app.applicationInfo.sourceDir
            // Key goes through a file only the shell user can read (0600), never on the command line.
            shell(m, "pkill -f 'com.duallink.app.voice.[V]oiceHelper'; umask 077; printf '%s' '$key' > $KEY_FILE; " +
                "CLASSPATH=$apk setsid nohup app_process / com.duallink.app.voice.VoiceHelper @$KEY_FILE " +
                "> /data/local/tmp/duallink_voice.log 2>&1 < /dev/null &")
            p.voiceKey = key
            runCatching { m.disconnect() } // the helper runs on its own; no need to keep adb open
            lastError = null
            Log.i(TAG, "UAT helper_launched")
            GatewayService.instance?.voice?.wake()
            NotificationManagerCompat.from(app).cancel(NOTIF_PAIR)
            return true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "helper launch failed: $lastError")
            runCatching { mgr?.disconnect() }
            return false
        } finally {
            busy = false
            Bus.emit("adb", state(app))
        }
    }
}

/** Receives the pairing code typed into the notification. */
class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence("code")?.toString()?.trim() ?: return
        AdbSelf.pair(ctx, code)
    }
}
