package chatsgate.whatsappfx.wire;

/**
 * The build this code came from: the {@code Implementation-Version} the shaded jar's manifest carries (set at release
 * time from {@code -Djrexe.version}, see the parent pom). The app and the command line of one release carry the same
 * string, so the command line can tell when it was started against an app of another build.
 */
public final class Version {

    private Version() {}

    /** {@code dev} when run from a build folder (no manifest entry). */
    public static String current() {
        var v = Version.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }
}
