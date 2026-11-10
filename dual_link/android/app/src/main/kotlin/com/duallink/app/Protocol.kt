package com.duallink.app

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Wire protocol (plain JVM code so it can be unit tested on the PC).
 *
 * 1. Client -> gateway: AES-GCM(pairing key, {"t":"hello","n":clientNonce,"v":1})
 * 2. Gateway -> client: AES-GCM(pairing key, {"t":"welcome","n":gatewayNonce,"c":clientNonce})
 *    Both sides now derive a fresh session key = HMAC-SHA256(pairing key, clientNonce|gatewayNonce).
 * 3. Every later frame: AES-GCM(session key) with a counter in the IV. The receiver only accepts the
 *    next expected counter, so recorded frames can't be replayed, reordered or reflected.
 *
 * One frame = one base64 line. Lines over MAX_LINE are rejected (stops memory abuse).
 */

const val PROTOCOL_VERSION = 1
const val MAX_LINE = 64 * 1024

class ProtocolException(msg: String) : IOException(msg)

object Crypto {
    private val rnd = SecureRandom()
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun newKey(): String = b64e.encodeToString(random(32))
    fun keyBytes(b64: String): ByteArray = b64d.decode(b64).also { require(it.size == 32) { "bad key" } }

    /** Random IV; used only for the two handshake messages. */
    fun seal(key: ByteArray, plain: String): String {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return b64e.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun open(key: ByteArray, data: String): String {
        val raw = try { b64d.decode(data) } catch (e: IllegalArgumentException) { throw ProtocolException("bad frame") }
        if (raw.size < 28) throw ProtocolException("short frame")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            throw ProtocolException("auth failed")
        }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    fun b64(b: ByteArray): String = b64e.encodeToString(b)
    fun unb64(s: String): ByteArray = b64d.decode(s)
}

/** Reads one '\n'-terminated line, refusing anything longer than [max] bytes. */
fun InputStream.readFrameLine(max: Int = MAX_LINE): String? {
    val buf = ByteArrayOutputStream()
    while (true) {
        val b = read()
        if (b == -1) return if (buf.size() == 0) null else throw ProtocolException("truncated")
        if (b == '\n'.code) return buf.toString(Charsets.US_ASCII.name())
        if (buf.size() >= max) throw ProtocolException("line too long")
        buf.write(b)
    }
}

/** An authenticated, encrypted, replay-protected connection. Thread-safe writes. */
class SecureLink private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    sessionKey: ByteArray,
    private val sendDir: Byte,
    private val recvDir: Byte,
) {
    private val key = SecretKeySpec(sessionKey, "AES")
    private var sendSeq = 0L
    private var recvSeq = 0L

    private fun iv(dir: Byte, seq: Long) = ByteBuffer.allocate(12).put(dir).put(0).put(0).put(0).putLong(seq).array()

    @Synchronized
    fun write(obj: JSONObject) {
        val seq = sendSeq++
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv(sendDir, seq)))
        val ct = c.doFinal(obj.toString().toByteArray(Charsets.UTF_8))
        val frame = ByteBuffer.allocate(8 + ct.size).putLong(seq).put(ct).array()
        output.write((Crypto.b64(frame) + "\n").toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** Returns null when the other side closed the connection. Throws on any tampering or replay. */
    fun read(): JSONObject? {
        val line = input.readFrameLine() ?: return null
        val raw = try { Crypto.unb64(line) } catch (e: IllegalArgumentException) { throw ProtocolException("bad frame") }
        if (raw.size < 8 + 16) throw ProtocolException("short frame")
        val seq = ByteBuffer.wrap(raw, 0, 8).long
        if (seq != recvSeq) throw ProtocolException("replayed or out-of-order frame")
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv(recvDir, seq)))
            c.doFinal(raw, 8, raw.size - 8)
        } catch (e: Exception) {
            throw ProtocolException("auth failed")
        }
        recvSeq++
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    companion object {
        private const val TO_GATEWAY: Byte = 1
        private const val TO_CLIENT: Byte = 2

        private fun session(k: ByteArray, cn: ByteArray, sn: ByteArray) =
            Crypto.hmac(k, "duallink-session-v1".toByteArray(), cn, sn)

        fun clientHandshake(input: InputStream, output: OutputStream, pairingKey: String): SecureLink {
            val k = Crypto.keyBytes(pairingKey)
            val cn = Crypto.random(16)
            val hello = JSONObject().put("t", "hello").put("n", Crypto.b64(cn)).put("v", PROTOCOL_VERSION)
            output.write((Crypto.seal(k, hello.toString()) + "\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            val line = input.readFrameLine(4096) ?: throw ProtocolException("closed during handshake")
            val w = JSONObject(Crypto.open(k, line))
            if (w.optString("t") != "welcome" || w.optString("c") != Crypto.b64(cn)) throw ProtocolException("bad welcome")
            val sn = Crypto.unb64(w.getString("n"))
            if (sn.size != 16) throw ProtocolException("bad nonce")
            return SecureLink(input, output, session(k, cn, sn), TO_GATEWAY, TO_CLIENT)
        }

        fun serverHandshake(input: InputStream, output: OutputStream, pairingKey: String): SecureLink {
            val k = Crypto.keyBytes(pairingKey)
            val line = input.readFrameLine(4096) ?: throw ProtocolException("closed during handshake")
            val h = JSONObject(Crypto.open(k, line))
            if (h.optString("t") != "hello") throw ProtocolException("bad hello")
            val cn = Crypto.unb64(h.getString("n"))
            if (cn.size != 16) throw ProtocolException("bad nonce")
            val sn = Crypto.random(16)
            val welcome = JSONObject().put("t", "welcome").put("n", Crypto.b64(sn)).put("c", Crypto.b64(cn))
            output.write((Crypto.seal(k, welcome.toString()) + "\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            return SecureLink(input, output, session(k, cn, sn), TO_CLIENT, TO_GATEWAY)
        }
    }
}

/**
 * Network name Phone A announces on Wi-Fi. Derived from the pairing key, so Phone B only follows
 * its own Phone A, never another Dual Link on the same network. Reveals nothing about the key.
 */
fun nsdName(pairingKey: String): String =
    "DualLink-" + Crypto.hmac(Crypto.keyBytes(pairingKey), "duallink-nsd-v1".toByteArray())
        .take(5).joinToString("") { "%02x".format(it) }

/** Gateway-side checks before using the SIM. */
object SendPolicy {
    const val MAX_BODY = 2000
    const val MAX_PER_MINUTE = 20
    private val phone = Regex("^\\+?[0-9]{3,15}$")

    fun normalize(to: String) = to.replace(Regex("[\\s\\-()]"), "")
    fun validNumber(to: String) = phone.matches(normalize(to))
    fun validBody(body: String) = body.isNotEmpty() && body.length <= MAX_BODY
}

/**
 * One key per person, so "+92 300 1234567", "00923001234567" and "03001234567" are one conversation.
 * Short codes and sender names (e.g. "8558", "JazzCash") are kept as they are.
 */
fun phoneKey(address: String): String {
    val a = address.replace(Regex("[\\s\\-()]"), "")
    return when {
        a.startsWith("+92") && a.length == 13 -> "0" + a.substring(3)
        a.startsWith("0092") && a.length == 14 -> "0" + a.substring(4)
        a.startsWith("92") && a.length == 12 && a[2] == '3' -> "0" + a.substring(2)
        else -> a
    }
}

/** Sliding one-minute window. */
class RateLimiter(private val max: Int, private val windowMs: Long = 60_000, private val now: () -> Long = System::currentTimeMillis) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        while (times.isNotEmpty() && t - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(t)
        return true
    }
}
