package com.duallink.app.voice;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Looper;
import android.util.Base64;

import com.duallink.app.SecureLink;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Phone A voice helper. Our own code, shipped in the Dual Link APK, started once over ADB:
 *   app_process / com.duallink.app.voice.VoiceHelper <key>
 * It runs as the ADB shell user, which on Android 13+ may use the official call audio
 * interception API (CALL_AUDIO_INTERCEPTION). It only:
 *   - listens on 127.0.0.1 (same phone), and accepts the Dual Link app only after an encrypted
 *     handshake with the 256-bit key the launcher gave both of them;
 *   - while a call is active and the app asks: sends the caller's voice to the app ("down")
 *     and plays the app's audio (Phone B's mic) into the call ("up").
 * No files, no other network access.
 */
public final class VoiceHelper {
    private static final int RATE = 16000;
    private static final int FRAME = RATE / 50; // 20 ms
    private static final int PORT = 47821;

    private static AudioManager am;
    private static volatile SecureLink link;
    private static volatile boolean wanted;
    private static volatile AudioRecord rec;
    private static volatile AudioTrack track;
    private static Thread audioThread;
    // Uplink diagnostics (Phone B's voice going into the call)
    private static long upFrames, upBytes, upErrors, upSq, upN;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { log("usage: VoiceHelper <key>"); return; }
        String key = args[0];
        if (key.startsWith("@")) { // key file written by the app with mode 0600; read once, then delete
            java.io.File f = new java.io.File(key.substring(1));
            key = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
            f.delete();
        }
        shellCtx = shellContext();
        am = newAudioManager(shellCtx);
        try (ServerSocket ss = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
            log("listening on 127.0.0.1:" + PORT);
            while (true) {
                Socket s = ss.accept();
                try {
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(10_000);
                    InputStream in = new java.io.BufferedInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    SecureLink l = SecureLink.Companion.serverHandshake(in, out, key);
                    s.setSoTimeout(0);
                    link = l;
                    log("app connected");
                    status("ready");
                    serve(l);
                } catch (Exception e) {
                    log("client dropped: " + e.getMessage());
                } finally {
                    link = null;
                    stopAudio();
                    try { s.close(); } catch (Exception ignored) { }
                }
            }
        }
    }

    private static void serve(SecureLink l) throws Exception {
        while (true) {
            JSONObject m = l.read();
            if (m == null) return;
            switch (m.optString("t")) {
                case "start": wanted = true; redirect = m.optBoolean("redirect", true); startAudio(); break;
                case "stop": wanted = false; stopAudio(); status("idle"); break;
                case "up": {
                    AudioTrack t = track;
                    if (t != null) {
                        byte[] b = Base64.decode(m.getString("d"), Base64.NO_WRAP);
                        // Blocking write: never silently drop Phone B's voice (at most ~20 ms wait).
                        int w = t.write(b, 0, b.length, AudioTrack.WRITE_BLOCKING);
                        upFrames++; if (w > 0) upBytes += w; else upErrors++;
                        for (int i = 0; i + 1 < b.length; i += 2) { int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8)); upSq += (long) s * s; upN++; }
                        if (upFrames % 100 == 0) {
                            android.media.AudioDeviceInfo dev = t.getRoutedDevice();
                            log(String.format("up: frames=%d bytesWritten=%d errors=%d rms=%.0f head=%d underruns=%d device=%s",
                                    upFrames, upBytes, upErrors, Math.sqrt((double) upSq / Math.max(1, upN)), t.getPlaybackHeadPosition(),
                                    t.getUnderrunCount(), dev == null ? "none" : dev.getType() + "/" + dev.getProductName()));
                            upSq = 0; upN = 0;
                        }
                    }
                    break;
                }
                default: break;
            }
        }
    }

    /** Waits (up to 60 s) for the phone to be in a call, then bridges audio until stopped. */
    private static synchronized void startAudio() {
        if (audioThread != null && audioThread.isAlive()) return;
        audioThread = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + 60_000;
                while (wanted && !inCall() && System.currentTimeMillis() < deadline) Thread.sleep(200);
                if (!wanted || !inCall()) { status("no_call"); return; }
                boolean ok = (boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(am);
                if (!ok) { status("unsupported"); return; }
                AudioFormat in = new AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
                AudioFormat out = new AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                // 1) Open both streams in normal call mode (caller audio extraction works here)...
                AudioRecord r = (AudioRecord) AudioManager.class.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class).invoke(am, in);
                AudioTrack t = (AudioTrack) AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class).invoke(am, out);
                rec = r; track = t;
                r.startRecording();
                t.play();
                // 2) ...then switch to Android 13 "call redirect" mode, so the call's uplink carries
                // only Phone B's voice (on this phone, injection is ignored in normal call mode).
                if (redirect) {
                    am.setMode(MODE_CALL_REDIRECT);
                    log("audio mode after redirect request = " + am.getMode());
                }
                upFrames = 0; upBytes = 0; upErrors = 0; upSq = 0; upN = 0;
                status("on");
                android.media.AudioDeviceInfo dev = t.getRoutedDevice();
                log("bridging call audio; uplink track state=" + t.getState() + " playState=" + t.getPlayState()
                        + " device=" + (dev == null ? "none" : dev.getType() + "/" + dev.getProductName())
                        + " micMuted=" + am.isMicrophoneMute());
                byte[] buf = new byte[FRAME * 2];
                long downFrames = 0, downSq = 0, downN = 0;
                int zeroRun = 0, source = 0; // 0 = interception API, 1 = VOICE_DOWNLINK, 2 = VOICE_CALL
                while (wanted && inCall()) {
                    int n = r.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    boolean allZero = true;
                    for (int i = 0; i + 1 < n; i += 2) {
                        int s = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                        if (s != 0) allZero = false;
                        downSq += (long) s * s; downN++;
                    }
                    zeroRun = allZero ? zeroRun + 1 : 0;
                    if (++downFrames % 100 == 0) {
                        log(String.format("down: frames=%d rms=%.0f source=%d mode=%d", downFrames,
                                Math.sqrt((double) downSq / Math.max(1, downN)), source, am.getMode()));
                        downSq = 0; downN = 0;
                    }
                    // 3) Pure digital silence for 1.5 s = this capture path is dead in redirect
                    // mode; try the next one (the shell may also capture the call downlink directly).
                    if (zeroRun >= 75 && source < 2) {
                        source++;
                        log("downlink silent; switching capture source to " + (source == 1 ? "VOICE_DOWNLINK" : "VOICE_CALL"));
                        AudioRecord alt = openCapture(source == 1 ? 3 /* VOICE_DOWNLINK */ : 4 /* VOICE_CALL */, in);
                        if (alt != null) {
                            try { r.stop(); } catch (Exception ignored) { }
                            r.release();
                            r = alt; rec = alt;
                            r.startRecording();
                        }
                        zeroRun = 0;
                    }
                    SecureLink l = link;
                    if (l != null) {
                        l.write(new JSONObject().put("t", "down").put("d", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)));
                    }
                }
            } catch (Throwable e) {
                log("audio error: " + e);
                status("error");
            } finally {
                stopAudio();
                // Hand the call back to Phone A's own mic/earpiece if it is still going.
                if (am.getMode() == MODE_CALL_REDIRECT) am.setMode(AudioManager.MODE_IN_CALL);
                if (wanted) status("ended");
            }
        }, "voice");
        audioThread.start();
    }

    private static final int MODE_CALL_REDIRECT = 5; // AudioManager.MODE_CALL_REDIRECT (API 33)
    private static Context shellCtx;

    /** Fallback call-audio capture (the shell holds CAPTURE_AUDIO_OUTPUT). Null if refused. */
    @SuppressLint("MissingPermission")
    private static AudioRecord openCapture(int source, AudioFormat fmt) {
        try {
            AudioRecord r = new AudioRecord.Builder().setContext(shellCtx).setAudioSource(source)
                    .setAudioFormat(fmt).setBufferSizeInBytes(FRAME * 2 * 8).build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Throwable e) {
            log("capture source " + source + " refused: " + e);
        }
        return null;
    }
    private static volatile boolean redirect = true;

    private static boolean inCall() {
        int m = am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == MODE_CALL_REDIRECT;
    }

    private static synchronized void stopAudio() {
        AudioRecord r = rec; rec = null;
        AudioTrack t = track; track = null;
        if (r != null) { try { r.stop(); } catch (Exception ignored) { } r.release(); }
        if (t != null) { try { t.stop(); } catch (Exception ignored) { } t.release(); }
    }

    private static void status(String s) {
        SecureLink l = link;
        if (l == null) return;
        try { l.write(new JSONObject().put("t", "status").put("s", s)); } catch (Exception ignored) { }
    }

    private static void log(String s) { System.out.println("[VoiceHelper] " + s); }

    // ---- minimal app-less Context for a shell process ----

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi"})
    private static Context shellContext() throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = atClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object at = ctor.newInstance();
        Field cur = atClass.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, at);
        Field sys = atClass.getDeclaredField("mSystemThread");
        sys.setAccessible(true);
        sys.setBoolean(at, true);
        Method getSys = atClass.getDeclaredMethod("getSystemContext");
        return new ShellContext((Context) getSys.invoke(at));
    }

    private static AudioManager newAudioManager(Context ctx) throws Exception {
        Constructor<AudioManager> c = AudioManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        return c.newInstance(ctx);
    }

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        }
        @Override public Context getApplicationContext() { return this; }
    }
}
