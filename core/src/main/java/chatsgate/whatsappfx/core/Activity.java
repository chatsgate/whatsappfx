package chatsgate.whatsappfx.core;

import java.time.Instant;

/**
 * When this process last did real work: a request, an incoming message, a job step, a window event.
 * The idle memory trim reads it. Never touch it from a periodic tick, or the app is never idle.
 */
public final class Activity {

    static volatile Instant last = Instant.now();

    private Activity() {}

    public static void touch() { last = Instant.now(); }

    public static Instant last() { return last; }
}
