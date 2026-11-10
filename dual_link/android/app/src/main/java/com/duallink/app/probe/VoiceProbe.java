package com.duallink.app.probe;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Looper;

import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Call-audio feasibility probe. Our own code, shipped inside the Dual Link APK.
 *
 * Runs on Phone A as the ADB shell user (which holds CALL_AUDIO_INTERCEPTION and
 * CAPTURE_AUDIO_OUTPUT), during an active call:
 *   adb shell CLASSPATH=<apk path> app_process / com.duallink.app.probe.VoiceProbe
 *
 * 1. Reports whether the phone supports call audio interception.
 * 2. Records 5 s of the caller's voice (downlink) and prints its loudness; saves /data/local/tmp/dl_probe.pcm.
 * 3. Plays a 3 s 800 Hz beep into the call (uplink): the other person should hear it.
 * Nothing leaves the phone; the recording file is deleted at the end unless "keep" is passed.
 */
public final class VoiceProbe {
    private static final int RATE = 16000;
    private static final int SHELL_UID = 2000; // android.os.Process.SHELL_UID (hidden constant)

    public static void main(String[] args) throws Exception {
        boolean keep = args.length > 0 && "keep".equals(args[0]);
        Context ctx = shellContext();
        AudioManager am = newAudioManager(ctx);

        out("audio mode = " + am.getMode() + " (2 = in call)");
        Method supported = AudioManager.class.getMethod("isPstnCallAudioInterceptable");
        boolean ok = (boolean) supported.invoke(am);
        out("isPstnCallAudioInterceptable = " + ok);
        if (!ok) { out("RESULT: this phone does not allow call audio interception"); return; }

        AudioFormat fmtIn = new AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        AudioFormat fmtOut = new AudioFormat.Builder().setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();

        // --- 1) hear the caller
        Method getRec = AudioManager.class.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class);
        AudioRecord rec = (AudioRecord) getRec.invoke(am, fmtIn);
        out("downlink record state = " + rec.getState());
        short[] buf = new short[RATE / 50]; // 20 ms
        long sumSq = 0, n = 0;
        int peak = 0;
        String path = "/data/local/tmp/dl_probe.pcm";
        rec.startRecording();
        try (FileOutputStream f = new FileOutputStream(path)) {
            long end = System.currentTimeMillis() + 5000;
            byte[] bytes = new byte[buf.length * 2];
            while (System.currentTimeMillis() < end) {
                int r = rec.read(buf, 0, buf.length);
                if (r <= 0) continue;
                for (int i = 0; i < r; i++) {
                    int s = buf[i];
                    sumSq += (long) s * s; n++;
                    peak = Math.max(peak, Math.abs(s));
                    bytes[2 * i] = (byte) s; bytes[2 * i + 1] = (byte) (s >> 8);
                }
                f.write(bytes, 0, r * 2);
            }
        }
        rec.stop(); rec.release();
        double rms = n == 0 ? 0 : Math.sqrt((double) sumSq / n);
        out(String.format("downlink: %d samples, rms=%.0f, peak=%d -> %s", n, rms, peak,
                rms > 100 ? "CALLER AUDIO CAPTURED" : "silence (was the other side quiet?)"));
        if (!keep) new java.io.File(path).delete();

        // --- 2) talk into the call
        Method getTrack = AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class);
        AudioTrack track = (AudioTrack) getTrack.invoke(am, fmtOut);
        out("uplink track state = " + track.getState());
        short[] tone = new short[RATE / 50];
        track.play();
        long t = 0, end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            for (int i = 0; i < tone.length; i++, t++) tone[i] = (short) (Math.sin(2 * Math.PI * 800 * t / RATE) * 8000);
            track.write(tone, 0, tone.length);
        }
        track.stop(); track.release();
        out("uplink: played 3 s beep into the call -> ask the other person if they heard it");
        out("RESULT: done");
    }

    private static void out(String s) { System.out.println("[VoiceProbe] " + s); }

    // ---- minimal app-less Context for a shell process (same idea as scrcpy's workarounds) ----

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
        Context base = (Context) getSys.invoke(at);
        return new ShellContext(base);
    }

    private static AudioManager newAudioManager(Context ctx) throws Exception {
        Constructor<AudioManager> c = AudioManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        return c.newInstance(ctx);
    }

    /** Presents itself as the ADB shell package so audio permission checks match our uid. */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(SHELL_UID).setPackageName("com.android.shell").build();
        }
        @Override public Context getApplicationContext() { return this; }
    }
}
