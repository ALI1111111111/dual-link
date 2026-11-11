package com.duallink.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val VTAG = "DualLinkVoice"
private const val RATE = 16000
private const val FRAME_BYTES = RATE / 50 * 2 // 20 ms of 16-bit mono

/**
 * Receives the voice helper's key from the ADB launcher. Protected by the DUMP permission,
 * which only the ADB shell / system hold, so no other app can plant a key.
 */
class VoiceKeyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val key = i.getStringExtra("key") ?: return
        runCatching { Crypto.keyBytes(key) }.getOrNull() ?: return
        Prefs(ctx).voiceKey = key
        Log.i(VTAG, "UAT voice_key_set")
        GatewayService.instance?.voice?.wake()
    }
}

/** Phone A: connects the Dual Link app to the voice helper and relays audio to/from Phone B. */
class GatewayVoice(private val ctx: Context, private val toClient: (JSONObject) -> Unit) {
    @Volatile private var link: SecureLink? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var running = true
    @Volatile var helperStatus = "not_started"
        private set
    private val wake = Object()

    fun start() = Thread(::loop, "gw-voice").start()

    fun stop() {
        running = false
        runCatching { socket?.close() }
        synchronized(wake) { wake.notifyAll() }
    }

    fun wake() { runCatching { socket?.close() }; synchronized(wake) { wake.notifyAll() } }

    /** Turns "Talk from Phone B" on or off. Off also stops the helper process. */
    fun setEnabled(on: Boolean) {
        Prefs(ctx).voiceOn = on
        Thread {
            if (!on) {
                runCatching { link?.write(JSONObject().put("t", "quit")) }
                Prefs(ctx).voiceKey = null
            }
            wake()
        }.start()
    }

    private fun loop() {
        var failures = 0
        var nextLaunch = 0L
        var backoff = 30_000L
        while (running) {
            // Voice is opt-in: while off, do nothing at all (no helper, no Wireless debugging, no battery).
            if (!Prefs(ctx).voiceOn) {
                if (helperStatus != "not_started") setStatus("not_started")
                synchronized(wake) { if (running) wake.wait(600_000) }
                continue
            }
            val key = Prefs(ctx).voiceKey
            // Helper unreachable for a while: relaunch it through this phone's own Wireless debugging.
            // Back-off (30 s → 10 min) keeps battery use tiny if it keeps failing (e.g. no Wi-Fi).
            if ((key == null || failures >= 2) && Prefs(ctx).adbPaired && System.currentTimeMillis() >= nextLaunch) {
                val ok = AdbSelf.ensureHelper(ctx)
                nextLaunch = System.currentTimeMillis() + if (ok) 30_000L else backoff
                backoff = if (ok) 30_000L else (backoff * 2).coerceAtMost(600_000L)
                failures = 0
                if (ok) Thread.sleep(1_500) // let the helper open its port
            }
            if (key != null || Prefs(ctx).voiceKey != null) try {
                val k = Prefs(ctx).voiceKey!!
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress("127.0.0.1", 47821), 2000)
                s.tcpNoDelay = true
                val l = SecureLink.clientHandshake(s.getInputStream().buffered(), s.getOutputStream(), k)
                link = l
                failures = 0; backoff = 30_000L
                setStatus("ready")
                while (running) {
                    val m = l.read() ?: break
                    when (m.optString("t")) {
                        "down" -> toClient(JSONObject().put("t", "va").put("d", m.getString("d")))
                        "status" -> setStatus(m.getString("s"))
                    }
                }
            } catch (e: Exception) {
                failures++
                if (helperStatus != "not_started") Log.i(VTAG, "helper link: ${e.message}")
            } finally {
                runCatching { socket?.close() }
                link = null
                if (helperStatus != "not_started") setStatus("not_started")
            }
            synchronized(wake) { if (running) wake.wait(5_000) }
        }
    }

    private fun setStatus(s: String) {
        if (helperStatus == s) return
        helperStatus = s
        Bus.emit("gateway", mapOf("voiceHelper" to s))
        Log.i(VTAG, "UAT voice_helper $s")
        toClient(JSONObject().put("t", "voice_status").put("s", s))
    }

    // All writes to the helper go through one background thread: keeps order, and never touches
    // the network from Android's main thread (that throws after the frame number is used up,
    // which the helper then rightly rejects as an out-of-order frame).
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun send(m: JSONObject) {
        val l = link ?: return
        runCatching { writer.execute { runCatching { l.write(m) }.onFailure { runCatching { socket?.close() } } } }
    }

    /** From Phone B: {"t":"voice","on":bool} or {"t":"va","d":b64 mic audio}. */
    fun fromClient(m: JSONObject) {
        when (m.optString("t")) {
            "voice" -> send(JSONObject().put("t", if (m.optBoolean("on")) "start" else "stop"))
            "va" -> send(JSONObject().put("t", "up").put("d", m.getString("d")))
        }
    }

    fun callEnded() = send(JSONObject().put("t", "stop"))
}

/** Phone B: plays the caller's voice and sends the mic, like a normal phone call. */
object ClientVoice {
    @Volatile var active = false
        private set
    @Volatile var speaker = false
        private set
    @Volatile var muted = false
    @Volatile var helperStatus = "not_started"
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private val queue = ArrayBlockingQueue<ByteArray>(25) // ~500 ms max buffered
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    @Volatile private var framesIn = 0L
    @Volatile private var framesOut = 0L
    private var micSq = 0L
    private var micN = 0L

    fun hasMic(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context): Boolean {
        if (active) return true
        if (!hasMic(ctx)) return false
        val am = ctx.getSystemService(AudioManager::class.java)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minRec, FRAME_BYTES * 4))
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(FRAME_BYTES * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        rec = r; track = t
        queue.clear(); framesIn = 0; framesOut = 0
        active = true
        setSpeaker(ctx, speaker)
        r.startRecording()
        t.play()
        Thread({ micLoop(r) }, "voice-mic").start()
        Thread({ playLoop(t) }, "voice-play").start()
        ClientService.instance?.write(JSONObject().put("t", "voice").put("on", true))
        Log.i(VTAG, "UAT voice_client_started")
        return true
    }

    private fun micLoop(r: AudioRecord) {
        val buf = ByteArray(FRAME_BYTES)
        val silence = ByteArray(FRAME_BYTES)
        while (active) {
            val n = r.read(buf, 0, buf.size)
            if (n <= 0) continue
            val data = if (muted) silence else buf
            ClientService.instance?.write(JSONObject().put("t", "va").put("d", Base64.encodeToString(data, 0, n, Base64.NO_WRAP)))
            framesOut++
            // Diagnostics: is the mic actually picking up sound?
            var sq = 0L
            for (i in 0 until n - 1 step 2) { val s = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toInt(); sq += s.toLong() * s }
            micSq += sq; micN += n / 2
            if (framesOut % 100 == 0L) {
                Log.i(VTAG, "UAT voice_mic frames=$framesOut rms=${Math.sqrt(micSq.toDouble() / maxOf(1, micN)).toInt()} muted=$muted")
                micSq = 0; micN = 0
            }
        }
    }

    private fun playLoop(t: AudioTrack) {
        var primed = false
        while (active) {
            if (!primed && queue.size < 3) { Thread.sleep(10); continue } // ~60 ms jitter cushion
            primed = true
            val f = queue.poll(200, TimeUnit.MILLISECONDS)
            if (f == null) { primed = false; continue }
            t.write(f, 0, f.size)
        }
    }

    /** Caller's voice from Phone A. Drops the oldest audio if we fall behind, to keep delay low. */
    fun onAudio(b64: String) {
        if (!active) return
        val f = Base64.decode(b64, Base64.NO_WRAP)
        if (!queue.offer(f)) { queue.poll(); queue.offer(f) }
        if (++framesIn == 50L) Log.i(VTAG, "UAT voice_audio_flowing")
    }

    fun setSpeaker(ctx: Context, on: Boolean) {
        speaker = on
        val am = ctx.getSystemService(AudioManager::class.java)
        val want = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        am.availableCommunicationDevices.firstOrNull { it.type == want }?.let { am.setCommunicationDevice(it) }
    }

    @Synchronized
    fun stop(ctx: Context) {
        if (!active) return
        active = false
        ClientService.instance?.write(JSONObject().put("t", "voice").put("on", false))
        rec?.let { runCatching { it.stop() }; it.release() }
        track?.let { runCatching { it.stop() }; it.release() }
        aec?.release(); ns?.release()
        rec = null; track = null; aec = null; ns = null
        queue.clear()
        val am = ctx.getSystemService(AudioManager::class.java)
        am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
        Log.i(VTAG, "UAT voice_client_stopped in=$framesIn out=$framesOut")
    }

    fun state(): Map<String, Any> = mapOf("active" to active, "speaker" to speaker, "muted" to muted, "helper" to helperStatus)
}
