package chatsgate.whatsappfx.daemon;

import java.security.SecureRandom;
import java.util.HexFormat;

/** The loopback token: random per run, never persisted beyond daemon.json. */
public final class Tokens {

    private static final SecureRandom RNG = new SecureRandom();

    private Tokens() {}

    public static String random() {
        var b = new byte[24];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
