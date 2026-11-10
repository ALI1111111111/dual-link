package com.duallink.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ProtocolTest {
    private val key = Crypto.newKey()

    /** Copies everything written so a test can replay it later. */
    private class Tee(val out: OutputStream) : OutputStream() {
        val log = ByteArrayOutputStream()
        @Volatile var deliver = true
        override fun write(b: Int) { if (deliver) out.write(b); log.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { if (deliver) out.write(b, off, len); log.write(b, off, len) }
        override fun flush() = out.flush()
        fun lines() = log.toString("US-ASCII").split("\n").filter { it.isNotEmpty() }
    }

    private class Pair(val client: SecureLink, val server: SecureLink, val toServer: PipedOutputStream, val clientTee: Tee)

    private fun pipePair(clientKey: String = key, serverKey: String = key): Pair {
        val c2s = PipedOutputStream(); val sIn = PipedInputStream(c2s, 1 shl 20)
        val s2c = PipedOutputStream(); val cIn = PipedInputStream(s2c, 1 shl 20)
        val tee = Tee(c2s)
        // Like the real gateway: a failed handshake closes the connection.
        val server = CompletableFuture.supplyAsync {
            try { SecureLink.serverHandshake(sIn, s2c, serverKey) } catch (e: Exception) { s2c.close(); throw e }
        }
        val client = SecureLink.clientHandshake(cIn, tee, clientKey)
        return Pair(client, server.get(5, TimeUnit.SECONDS), c2s, tee)
    }

    @Test fun roundTripBothDirections() {
        val p = pipePair()
        p.client.write(JSONObject().put("t", "send").put("body", "Salam 👋 اردو"))
        assertEquals("Salam 👋 اردو", p.server.read()!!.getString("body"))
        p.server.write(JSONObject().put("t", "sms").put("body", "OTP 1234"))
        assertEquals("OTP 1234", p.client.read()!!.getString("body"))
        repeat(100) { p.client.write(JSONObject().put("i", it)) }
        repeat(100) { assertEquals(it, p.server.read()!!.getInt("i")) }
    }

    @Test fun wrongKeyIsRejected() {
        try {
            pipePair(clientKey = Crypto.newKey())
            fail("handshake should fail")
        } catch (e: Exception) {
            // expected: either side refuses
        }
    }

    @Test fun replayedFrameIsRejected() {
        val p = pipePair()
        p.client.write(JSONObject().put("t", "send").put("id", "x"))
        p.server.read()
        val recorded = p.clientTee.lines().last()
        p.toServer.write((recorded + "\n").toByteArray()); p.toServer.flush()
        assertThrows { p.server.read() }
    }

    @Test fun tamperedFrameIsRejected() {
        val p = pipePair()
        val frame = captureFrame(p)
        // Flip one bit in the encrypted part (e.g. trying to change the phone number).
        val bad = Crypto.unb64(frame).also { it[12] = (it[12].toInt() xor 1).toByte() }
        p.toServer.write((Crypto.b64(bad) + "\n").toByteArray()); p.toServer.flush()
        assertThrows { p.server.read() }
    }

    @Test fun untamperedCapturedFrameStillWorks() {
        // Control for the test above: the same capture path without changes is accepted.
        val p = pipePair()
        val frame = captureFrame(p)
        p.toServer.write((frame + "\n").toByteArray()); p.toServer.flush()
        assertEquals("hi", p.server.read()!!.getString("body"))
    }

    @Test fun frameFromOldSessionIsRejectedInNewSession() {
        val old = pipePair()
        old.client.write(JSONObject().put("t", "send").put("id", "old"))
        old.server.read()
        val recorded = old.clientTee.lines().last()
        val fresh = pipePair()
        fresh.toServer.write((recorded + "\n").toByteArray()); fresh.toServer.flush()
        assertThrows { fresh.server.read() }
    }

    @Test fun hugeLineIsRejected() {
        val p = pipePair()
        p.toServer.write(ByteArray(MAX_LINE + 10) { 'A'.code.toByte() }); p.toServer.flush()
        assertThrows { p.server.read() }
    }

    @Test fun garbageIsRejected() {
        val p = pipePair()
        p.toServer.write("not base64 at all!!\n".toByteArray()); p.toServer.flush()
        assertThrows { p.server.read() }
    }

    @Test fun worksOverRealTcpSockets() {
        ServerSocket(0).use { ss ->
            val srv = CompletableFuture.supplyAsync {
                val s = ss.accept()
                val l = SecureLink.serverHandshake(s.getInputStream().buffered(), s.getOutputStream(), key)
                val m = l.read()!!
                l.write(JSONObject().put("t", "status").put("id", m.getString("id")).put("state", "sent"))
                s
            }
            Socket("127.0.0.1", ss.localPort).use { s ->
                val l = SecureLink.clientHandshake(s.getInputStream().buffered(), s.getOutputStream(), key)
                l.write(JSONObject().put("t", "send").put("id", "abc"))
                val r = l.read()!!
                assertEquals("abc", r.getString("id"))
                assertEquals("sent", r.getString("state"))
            }
            srv.get(5, TimeUnit.SECONDS).close()
        }
    }

    @Test fun phoneNumberRules() {
        assertTrue(SendPolicy.validNumber("+923001234567"))
        assertTrue(SendPolicy.validNumber("0300 123-4567"))
        assertTrue(SendPolicy.validNumber("8888"))
        assertFalse(SendPolicy.validNumber("*123#"))   // no USSD codes
        assertFalse(SendPolicy.validNumber("abc"))
        assertFalse(SendPolicy.validNumber("12"))
        assertFalse(SendPolicy.validNumber("+1234567890123456"))
        assertFalse(SendPolicy.validBody(""))
        assertFalse(SendPolicy.validBody("x".repeat(SendPolicy.MAX_BODY + 1)))
    }

    @Test fun samePersonIsOneConversation() {
        assertEquals("03001234567", phoneKey("+923001234567"))
        assertEquals("03001234567", phoneKey("+92 300 123-4567"))
        assertEquals("03001234567", phoneKey("00923001234567"))
        assertEquals("03001234567", phoneKey("923001234567"))
        assertEquals("03001234567", phoneKey("03001234567"))
        assertEquals("8558", phoneKey("8558"))           // short codes untouched
        assertEquals("JazzCash", phoneKey("JazzCash"))   // sender names untouched
        assertEquals("+14155550100", phoneKey("+1 415 555 0100")) // foreign numbers keep +
    }

    @Test fun networkNameIsPerPairing() {
        val k2 = Crypto.newKey()
        assertEquals(nsdName(key), nsdName(key))             // stable
        assertTrue(nsdName(key) != nsdName(k2))               // different pairing, different name
        assertTrue(nsdName(key).matches(Regex("DualLink-[0-9a-f]{10}")))
    }

    @Test fun rateLimiterSlidesWindow() {
        var now = 0L
        val r = RateLimiter(3, 60_000) { now }
        repeat(3) { assertTrue(r.tryAcquire()) }
        assertFalse(r.tryAcquire())
        now = 60_000
        assertTrue(r.tryAcquire())
    }

    /** Lets the client write its next frame without delivering it, so the test can alter it first. */
    private fun captureFrame(p: Pair): String {
        p.clientTee.deliver = false
        p.client.write(JSONObject().put("t", "send").put("to", "+923001234567").put("body", "hi"))
        p.clientTee.deliver = true
        return p.clientTee.lines().last()
    }

    private fun assertThrows(block: () -> Unit) {
        try { block(); fail("expected an exception") } catch (e: AssertionError) { throw e } catch (e: Exception) { }
    }
}
