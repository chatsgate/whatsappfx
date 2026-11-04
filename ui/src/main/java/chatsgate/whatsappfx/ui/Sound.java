package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Paths2;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The chime for a new message: two quick marimba notes made in memory, or {@code sound.wav} in the app's
 * home if the user drops one there. {@code sound.off} beside it turns it off, so the choice survives a restart.
 */
public final class Sound {

    static final Path OFF = Paths2.home().resolve("sound.off");
    static final Path CUSTOM = Paths2.home().resolve("sound.wav");

    private Sound() {}

    public static boolean on() {
        return !Files.exists(OFF);
    }

    public static void on(boolean on) {
        try {
            if (on) Files.deleteIfExists(OFF);
            else Files.writeString(OFF, "off");
        } catch (IOException e) {
            Log.warn("sound setting not saved: " + e.getMessage());
        }
    }

    /** Off the calling thread, and never throws: a machine without a sound device just stays quiet. */
    public static void chime() {
        if (!on()) return;
        Thread.ofVirtual().name("chime").start(() -> {
            try {
                var in = Files.isRegularFile(CUSTOM) ? AudioSystem.getAudioInputStream(CUSTOM.toFile()) : synth();
                var clip = AudioSystem.getClip();
                clip.open(in);
                clip.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) clip.close(); });
                clip.start();
            } catch (Exception | LinkageError e) {
                Log.warn("no sound: " + e);
            }
        });
    }

    static final Path RING_OFF = Paths2.home().resolve("ring.off");
    static final Path RING_CUSTOM = Paths2.home().resolve("ring.wav");

    public static boolean ringOn() {
        return !Files.exists(RING_OFF);
    }

    public static void ringOn(boolean on) {
        try {
            if (on) Files.deleteIfExists(RING_OFF);
            else Files.writeString(RING_OFF, "off");
        } catch (IOException e) {
            Log.warn("ring setting not saved: " + e.getMessage());
        }
    }

    /** A ringtone playing in a loop; {@link #stop} ends it from any thread. */
    public static final class Ring {
        private volatile javax.sound.sampled.Clip clip;
        private volatile boolean stopped;

        public void stop() {
            stopped = true;
            var c = clip;
            if (c != null) { c.stop(); c.close(); }
        }
    }

    /**
     * Rings until stopped: a short rising phrase twice, then a pause, over and over ({@code ring.wav} in the app's home
     * replaces it; {@code ring.off} silences calls). Never throws.
     */
    public static Ring ring() {
        var r = new Ring();
        if (!ringOn()) return r;
        Thread.ofVirtual().name("ring").start(() -> {
            try {
                var in = Files.isRegularFile(RING_CUSTOM) ? AudioSystem.getAudioInputStream(RING_CUSTOM.toFile()) : ringtone();
                var clip = AudioSystem.getClip();
                clip.open(in);
                r.clip = clip;
                if (r.stopped) { clip.close(); return; }
                clip.loop(javax.sound.sampled.Clip.LOOP_CONTINUOUSLY);
            } catch (Exception | LinkageError e) {
                Log.warn("no ring: " + e);
            }
        });
        return r;
    }

    // whatsappfx sounds like a soft marimba: the timbre, not just
    // the pitch, tells this app apart from others. A message is a short figure; a call is a longer one that loops.
    static final double[] MARIMBA = {1, 3.93, 9.2}, MARIMBA_W = {1, 0.22, 0.05};

    /** A rolled marimba phrase, twice, then a pause: the call. */
    static AudioInputStream ringtone() {
        float rate = 44_100;
        var out = new ByteArrayOutputStream();
        for (int i = 0; i < 2; i++) {
            for (var hz : new double[] {784.0, 1046.5, 1318.5}) strike(out, rate, hz, 0.11, 0.34, 7, MARIMBA, MARIMBA_W);
            strike(out, rate, 1568.0, 0.36, 0.34, 7, MARIMBA, MARIMBA_W);
            strike(out, rate, 0, 0.14, 0, 1, MARIMBA, MARIMBA_W);
        }
        strike(out, rate, 0, 1.5, 0, 1, MARIMBA, MARIMBA_W);
        var pcm = out.toByteArray();
        return new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(rate, 16, 1, true, false), pcm.length / 2);
    }

    /** Two quick marimba notes, rising: the message. */
    static AudioInputStream synth() {
        float rate = 44_100;
        var out = new ByteArrayOutputStream();
        strike(out, rate, 1046.5, 0.12, 0.34, 9, MARIMBA, MARIMBA_W);
        strike(out, rate, 1318.5, 0.34, 0.34, 9, MARIMBA, MARIMBA_W);
        var pcm = out.toByteArray();
        return new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(rate, 16, 1, true, false), pcm.length / 2);
    }

    /** One struck note: partials at {@code ratios} of {@code hz}, the higher ones fading faster; {@code hz} 0 is a rest. */
    static void strike(ByteArrayOutputStream out, float rate, double hz, double seconds, double volume, double decay, double[] ratios, double[] weights) {
        int n = (int) (rate * seconds), attack = (int) (rate * 0.004);
        double total = 0;
        for (var w : weights) total += w;
        for (int i = 0; i < n; i++) {
            double t = i / (double) rate, s = 0;
            for (int k = 0; k < ratios.length; k++) s += weights[k] * Math.exp(-decay * (1 + k) * t) * Math.sin(2 * Math.PI * hz * ratios[k] * t);
            short v = (short) (Math.min(1.0, i / (double) attack) * s / total * volume * Short.MAX_VALUE);
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
        }
    }
}
