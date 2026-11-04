package chatsgate.whatsappfx.wire;

/** Which system this is, asked once. */
public final class Os {

    public static final String NAME = System.getProperty("os.name", "").toLowerCase();
    public static final boolean WINDOWS = NAME.contains("win"), MAC = NAME.contains("mac");

    private Os() {}
}
