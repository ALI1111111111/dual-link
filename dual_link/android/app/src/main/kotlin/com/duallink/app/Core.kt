package com.duallink.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.flutter.plugin.common.EventChannel
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val DEFAULT_PORT = 47820
const val NSD_TYPE = "_duallink._tcp."
const val CH_SERVICE = "service"
const val CH_SMS = "sms"

/** Wraps the pairing key with a key that never leaves the phone's secure hardware (Android Keystore). */
private object Vault {
    private const val ALIAS = "duallink-vault"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    fun wrap(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Crypto.b64(c.iv + c.doFinal(plain.toByteArray()))
    }

    fun unwrap(data: String): String? = runCatching {
        val raw = Crypto.unb64(data)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("duallink", Context.MODE_PRIVATE)
    var mode: String? get() = p.getString("mode", null); set(v) = p.edit().putString("mode", v).apply()
    var key: String?
        get() = p.getString("key", null)?.let { Vault.unwrap(it) }
        set(v) = p.edit().putString("key", v?.let { Vault.wrap(it) }).apply()
    /** Phone A: key shared with the voice helper (set by the ADB launcher). */
    var voiceKey: String?
        get() = p.getString("voiceKey", null)?.let { Vault.unwrap(it) }
        set(v) = p.edit().putString("voiceKey", v?.let { Vault.wrap(it) }).apply()
    /** Phone A: identity used to talk to its own Wireless debugging (private key encrypted). */
    var adbKey: String?
        get() = p.getString("adbKey", null)?.let { Vault.unwrap(it) }
        set(v) = p.edit().putString("adbKey", v?.let { Vault.wrap(it) }).apply()
    var adbCert: String? get() = p.getString("adbCert", null); set(v) = p.edit().putString("adbCert", v).apply()
    var adbPaired: Boolean get() = p.getBoolean("adbPaired", false); set(v) = p.edit().putBoolean("adbPaired", v).apply()
    /** Phone A: user opted in to "Talk from Phone B" (Advanced). Off by default: no Wireless debugging needed. */
    var voiceOn: Boolean get() = p.getBoolean("voiceOn", adbPaired); set(v) = p.edit().putBoolean("voiceOn", v).apply()
    var host: String? get() = p.getString("host", null); set(v) = p.edit().putString("host", v).apply()
    var port: Int get() = p.getInt("port", DEFAULT_PORT); set(v) = p.edit().putInt("port", v).apply()
    var enabled: Boolean get() = p.getBoolean("enabled", false); set(v) = p.edit().putBoolean("enabled", v).apply()
    fun clear() = p.edit().clear().apply()
}

/**
 * Gateway: `pending` = received SMS not yet confirmed by the client; `sent` = send ids already handled
 * (so a retry after a dropped connection never sends the same SMS twice).
 * Client: `messages` = local inbox/outbox.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "duallink.db", null, 4) {
    /** A finished message waiting to be copied into Android's SMS database. */
    data class Row(val id: String, val address: String, val body: String, val ts: Long, val status: String, val read: Boolean)

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending(id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sent(id TEXT PRIMARY KEY, state TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE messages(id TEXT PRIMARY KEY, address TEXT NOT NULL, body TEXT NOT NULL, " +
                "ts INTEGER NOT NULL, dir INTEGER NOT NULL, status TEXT NOT NULL, read INTEGER NOT NULL DEFAULT 0, " +
                "sim INTEGER NOT NULL DEFAULT -1, reason TEXT, sys_uri TEXT)"
        )
        db.execSQL("CREATE INDEX idx_msg_addr ON messages(address, ts)")
        createCalls(db)
    }

    /** Phone B: Phone A's call history. type = CallLog.Calls.TYPE (1 in, 2 out, 3 missed, 5 rejected). */
    private fun createCalls(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE calls(id TEXT PRIMARY KEY, number TEXT NOT NULL, type INTEGER NOT NULL, ts INTEGER NOT NULL, dur INTEGER NOT NULL, sim INTEGER NOT NULL)")

    fun addCall(id: String, number: String, type: Int, ts: Long, dur: Long, sim: Int) {
        writableDatabase.insertWithOnConflict("calls", null, ContentValues().apply {
            put("id", id); put("number", phoneKey(number)); put("type", type); put("ts", ts); put("dur", dur); put("sim", sim)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun newestCallTs(): Long = readableDatabase.rawQuery("SELECT COALESCE(MAX(ts),0) FROM calls", null).use { it.moveToFirst(); it.getLong(0) }

    fun calls(limit: Int = 300): List<Map<String, Any>> = readableDatabase.rawQuery(
        "SELECT id, number, type, ts, dur, sim FROM calls ORDER BY ts DESC LIMIT $limit", null
    ).use { c ->
        buildList { while (c.moveToNext()) add(mapOf("id" to c.getString(0), "number" to c.getString(1), "type" to c.getInt(2),
            "ts" to c.getLong(3), "dur" to c.getLong(4), "sim" to c.getInt(5))) }
    }

    /** Keeps existing messages when the app is updated. */
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sim INTEGER NOT NULL DEFAULT -1")
            db.execSQL("ALTER TABLE messages ADD COLUMN reason TEXT")
        }
        if (o < 3) db.execSQL("ALTER TABLE messages ADD COLUMN sys_uri TEXT")
        if (o < 4) createCalls(db)
    }

    // ---- default SMS app (Phone B) ----
    fun unmirrored(limit: Int): List<Row> = readableDatabase.rawQuery(
        "SELECT id,address,body,ts,status,read FROM messages WHERE sys_uri IS NULL " +
            "AND status IN ('received','sent','failed') ORDER BY ts LIMIT $limit", null
    ).use { c ->
        buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getInt(5) == 1)) }
    }

    fun setSysUri(id: String, uri: String) =
        writableDatabase.update("messages", ContentValues().apply { put("sys_uri", uri) }, "id=?", arrayOf(id))

    /** SIM this person last texted on (so replies go out on the same SIM), or -1. */
    fun lastIncomingSim(address: String): Int = readableDatabase.rawQuery(
        "SELECT sim FROM messages WHERE address=? AND dir=0 AND sim >= 0 ORDER BY ts DESC LIMIT 1", arrayOf(phoneKey(address))
    ).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    /** Deletes a conversation; returns the system SMS rows to delete as well. */
    fun deleteThread(address: String): List<String> {
        val key = phoneKey(address)
        val uris = sysUris("address=?", arrayOf(key))
        writableDatabase.delete("messages", "address=?", arrayOf(key))
        return uris
    }

    private fun sysUris(where: String, args: Array<String>): List<String> = readableDatabase.rawQuery(
        "SELECT sys_uri FROM messages WHERE sys_uri LIKE 'content://%' AND $where", args
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // ---- gateway ----
    fun addPending(id: String, json: JSONObject) {
        writableDatabase.insertWithOnConflict("pending", null,
            ContentValues().apply { put("id", id); put("json", json.toString()) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removePending(id: String) = writableDatabase.delete("pending", "id=?", arrayOf(id))

    fun allPending(): List<JSONObject> =
        readableDatabase.rawQuery("SELECT json FROM pending ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) }
        }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM pending", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Returns the earlier state if this send id was already handled, or null after claiming it. */
    @Synchronized
    fun claimSend(id: String): String? {
        readableDatabase.rawQuery("SELECT state FROM sent WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        writableDatabase.insert("sent", null, ContentValues().apply {
            put("id", id); put("state", "sending"); put("ts", System.currentTimeMillis())
        })
        // Keep the table small: forget ids older than 7 days.
        writableDatabase.delete("sent", "ts < ?", arrayOf((System.currentTimeMillis() - 7 * 86_400_000L).toString()))
        return null
    }

    fun setSendState(id: String, state: String) =
        writableDatabase.update("sent", ContentValues().apply { put("state", state) }, "id=?", arrayOf(id))

    // ---- client ----
    /** Returns false if the message was already stored (duplicate delivery). */
    fun addMessage(id: String, address: String, body: String, ts: Long, dir: Int, status: String, sim: Int, read: Boolean = dir == 1): Boolean {
        val row = writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
            put("id", id); put("address", phoneKey(address)); put("body", body); put("ts", ts)
            put("dir", dir); put("status", status); put("read", if (read) 1 else 0); put("sim", sim)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return row != -1L
    }

    /**
     * History from Phone A's SMS database. Skips anything already here: a received SMS that was also
     * relayed live has the same number, text and network timestamp (within 3 s). Sent SMS made by
     * Dual Link are already filtered out on Phone A, so genuine repeats ("ok", "ok") are kept.
     */
    @Synchronized
    fun addHistory(id: String, address: String, body: String, ts: Long, dir: Int, sim: Int): Boolean {
        val key = phoneKey(address)
        readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE id=? OR (address=? AND body=? AND dir=? AND ABS(ts-?) < 3000 AND id NOT LIKE 'h-%') LIMIT 1",
            arrayOf(id, key, body, dir.toString(), ts.toString())
        ).use { if (it.moveToFirst()) return false }
        return addMessage(id, key, body, ts, dir, if (dir == 1) "sent" else "received", sim, read = true)
    }

    fun newestHistoryTs(): Long = readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(ts),0) FROM messages WHERE id LIKE 'h-%'", null).use { it.moveToFirst(); it.getLong(0) }

    fun setStatus(id: String, status: String, reason: String? = null) =
        writableDatabase.update("messages", ContentValues().apply { put("status", status); put("reason", reason) }, "id=?", arrayOf(id))

    /** Marks a conversation read; returns the system SMS rows that need the same. */
    fun markRead(address: String): List<String> {
        val key = phoneKey(address)
        val uris = sysUris("address=? AND read=0", arrayOf(key))
        writableDatabase.update("messages", ContentValues().apply { put("read", 1) }, "address=? AND read=0", arrayOf(key))
        return uris
    }

    /** Not yet confirmed by the gateway. Re-sending is safe: the gateway ignores ids it already handled. */
    fun unconfirmedOutgoing(): List<JSONObject> =
        readableDatabase.rawQuery(
            "SELECT id,address,body,sim FROM messages WHERE dir=1 AND status IN ('queued','sending') ORDER BY ts", null
        ).use { c ->
            buildList { while (c.moveToNext()) add(JSONObject().put("id", c.getString(0)).put("to", c.getString(1))
                .put("body", c.getString(2)).put("sim", c.getInt(3))) }
        }

    fun threads(): List<Map<String, Any>> = readableDatabase.rawQuery(
        "SELECT m.address, m.body, m.ts, m.dir, " +
            "(SELECT COUNT(*) FROM messages u WHERE u.address=m.address AND u.read=0) " +
            "FROM messages m WHERE m.ts = (SELECT MAX(ts) FROM messages x WHERE x.address=m.address) " +
            "GROUP BY m.address ORDER BY m.ts DESC", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(mapOf(
                "address" to c.getString(0), "body" to c.getString(1), "ts" to c.getLong(2),
                "dir" to c.getInt(3), "unread" to c.getInt(4)))
        }
    }

    fun messages(address: String): List<Map<String, Any>> = readableDatabase.rawQuery(
        "SELECT id, body, ts, dir, status, sim, reason FROM messages WHERE address=? ORDER BY ts", arrayOf(phoneKey(address))
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(mapOf(
                "id" to c.getString(0), "body" to c.getString(1), "ts" to c.getLong(2),
                "dir" to c.getInt(3), "status" to c.getString(4), "sim" to c.getInt(5), "reason" to c.getString(6)))
        }
    }
}

/** Pushes events from services to the Flutter UI (if it is open). */
object Bus {
    @Volatile var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())
    fun emit(type: String, data: Map<String, Any?> = emptyMap()) {
        main.post { sink?.success(data + ("type" to type)) }
    }
}

fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback }
    .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
    .flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

fun ensureChannels(ctx: Context) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN))
    nm.createNotificationChannel(NotificationChannel(CH_SMS, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
        lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
    })
}
