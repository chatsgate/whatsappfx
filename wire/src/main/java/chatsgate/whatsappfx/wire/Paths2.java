package chatsgate.whatsappfx.wire;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Every location the three processes agree on.
 *
 * <p>Nothing here is ever inside a Drive-synced tree: the sync client rewrites files under it, which
 * an embedded database and an append-only journal both take badly.
 */
public final class Paths2 {

    public static final String HOME_ENV = "WHATSAPPFX_HOME";

    private Paths2() {}

    public static Path home() {
        var override = System.getenv(HOME_ENV);
        if (override != null && !override.isBlank()) return Paths.get(override);
        return Paths.get(System.getProperty("user.home"), "chatsgate", "whatsappfx");
    }

    public static Path daemonFile() {
        return home().resolve("daemon.json");
    }

    public static Path configFile() {
        return home().resolve("whatsappfx_settings.toml");
    }

    /** The settings file's earlier name; {@code Config.load} renames it once to {@link #configFile()}. */
    public static Path legacyConfigFile() {
        return home().resolve("config.toml");
    }

    public static Path connectorExe() {
        return home().resolve("bin").resolve(connectorName());
    }

    /** The Go connector's file name: with .exe on Windows, without it elsewhere. */
    public static String connectorName() {
        return Os.WINDOWS ? "wa-connector.exe" : "wa-connector";
    }

    /** The numbers, in the person's order: id, title and colour of each (AccountStore). */
    public static Path accountsFile() {
        return home().resolve("accounts.json");
    }

    public static Path accountsDir() {
        return home().resolve("accounts");
    }

    /** Per-account root. The account is a key everywhere: each number has its own session store, journal and spool under it. */
    public static Path account(String accountId) {
        return accountsDir().resolve(accountId);
    }

    public static Path sessionDb(String accountId) {
        return account(accountId).resolve("session").resolve("whatsmeow.db");
    }

    public static Path journalDir(String accountId) {
        return account(accountId).resolve("journal");
    }

    public static Path spoolDir(String accountId) {
        return account(accountId).resolve("spool");
    }

    public static Path indexRoot() {
        return home().resolve("index");
    }

    public static Path logFile() {
        return home().resolve("wa.log");
    }
}
