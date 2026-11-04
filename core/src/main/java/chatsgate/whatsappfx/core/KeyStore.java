package chatsgate.whatsappfx.core;

import chatsgate.whatsappfx.wire.Paths2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/// The key that encrypts the session store (the linked device's identity and Signal keys): 256 random bits made on first use,
/// never written in the clear on Windows or macOS. The connector receives it in its own environment only ({@link Connector}).
///
/// - Windows: `session.key` in the home holds the key protected by DPAPI for the current user account; a copy of the folder is
///   unreadable on another machine or under another account.
/// - macOS: the key is a generic password in the user's login Keychain (service `whatsappfx`, account `session-key`);
///   `session.key` only records that it lives there.
/// - Anything else: `session.key` holds the key with owner-only permissions.
///
/// A Windows password reset that does not know the old password, a new Mac or a lost Keychain loses the key, so it can be written
/// down as a recovery code ({@link #recoveryCode()}) and put back with {@link #restore}. This protects data files that are copied
/// or read from another account or an offline disk. It does not protect against a program running as the same user, which holds
/// for any desktop program.
public final class KeyStore {

    static final String FILE_MAGIC = "whatsappfx-key 1";
    static final byte[] ENTROPY = "whatsappfx.session.key".getBytes(StandardCharsets.UTF_8);
    static final String KC_SERVICE = "whatsappfx", KC_ACCOUNT = "session-key";
    static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] cached;
    private static boolean created;

    private KeyStore() { }

    static Path file() { return Paths2.home().resolve("session.key"); }

    /// The key, made on first use and held in memory after that.
    public static synchronized byte[] key() {
        if (cached != null) return cached;
        var f = file();
        try {
            if (Files.exists(f)) {
                cached = read(f);
            } else {
                var k = new byte[32];
                RANDOM.nextBytes(k);
                write(f, k);
                created = true;
                cached = k;
                Log.info("session key created; write down its recovery code (whatsappcli key --recovery-code)");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read or create the session key " + f, e);
        }
        return cached;
    }

    public static String keyHex() { return HexFormat.of().formatHex(key()); }

    /// True when this run made the key: the moment to show the recovery code to the person.
    public static synchronized boolean createdThisRun() { return created; }

    public static synchronized boolean exists() { return cached != null || Files.exists(file()); }

    /// Where the key is kept, for status lines: "dpapi", "keychain" or "plain".
    public static String protection() {
        try {
            var lines = Files.readAllLines(file());
            return lines.size() > 1 ? lines.get(1).trim() : "unknown";
        } catch (IOException e) {
            return "none";
        }
    }

    /// The key as text to write down: 56 characters in 8 groups, the last three bytes a check against a mistyped character.
    public static String recoveryCode() {
        var k = key();
        var all = Arrays.copyOf(k, 35);
        System.arraycopy(check(k), 0, all, 32, 3);
        var raw = b32(all);
        var out = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 7) out.append(i == 0 ? "" : "-").append(raw, i, i + 7);
        return out.toString();
    }

    /// Puts a key back from its recovery code. Refuses to replace a different key that is already here unless `replace` is true.
    public static synchronized void restore(String code, boolean replace) {
        var all = unb32(code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT));
        var k = Arrays.copyOf(all, 32);
        if (!Arrays.equals(Arrays.copyOfRange(all, 32, 35), check(k))) throw new IllegalArgumentException("this recovery code is not right: a character was mistyped");
        var f = file();
        try {
            if (Files.exists(f) && !replace && !Arrays.equals(read(f), k))
                throw new IllegalStateException("this machine already has a different session key; restoring would make the session store unreadable (use --replace to do it anyway)");
            write(f, k);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the session key " + f, e);
        }
        cached = k;
    }

    /// For tests: forget what is held in memory.
    static synchronized void reset() { cached = null; created = false; }

    static byte[] check(byte[] key) {
        try {
            var d = MessageDigest.getInstance("SHA-256");
            d.update("whatsappfx-recovery".getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(d.digest(key), 3);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String os() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT); }
    private static boolean windows() { return os().contains("windows"); }
    private static boolean mac() { return os().contains("mac"); }

    private static void write(Path f, byte[] key) throws IOException {
        String how, body;
        if (windows()) {
            how = "dpapi";
            body = Base64.getEncoder().encodeToString(Dpapi.protect(key, ENTROPY));
        } else if (mac()) {
            how = "keychain";
            keychainPut(HexFormat.of().formatHex(key));
            body = KC_SERVICE + "/" + KC_ACCOUNT;
        } else {
            how = "plain";
            body = Base64.getEncoder().encodeToString(key);
        }
        var text = FILE_MAGIC + "\n" + how + "\n" + body + "\n";
        Files.createDirectories(f.getParent());
        var tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, text);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) { }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] read(Path f) throws IOException {
        var lines = Files.readAllLines(f);
        if (lines.size() < 3 || !FILE_MAGIC.equals(lines.get(0))) throw new IllegalStateException(f + " is not a whatsappfx session key file");
        var body = lines.get(2).trim();
        return switch (lines.get(1).trim()) {
            case "dpapi" -> {
                if (!windows()) throw new IllegalStateException("the session key here is protected by Windows and cannot be read on this system; restore it from its recovery code");
                yield Dpapi.unprotect(Base64.getDecoder().decode(body), ENTROPY);
            }
            case "keychain" -> {
                if (!mac()) throw new IllegalStateException("the session key is in a macOS Keychain; restore it on this system from its recovery code");
                yield HexFormat.of().parseHex(keychainGet());
            }
            case "plain" -> Base64.getDecoder().decode(body);
            default -> throw new IllegalStateException("unknown key protection '" + lines.get(1) + "' in " + f);
        };
    }

    /// `security -i` reads its command from standard input, so the key never appears in a process's command line.
    private static void keychainPut(String hex) throws IOException {
        var p = new ProcessBuilder("security", "-i").redirectErrorStream(true).start();
        try (var w = p.outputWriter(StandardCharsets.UTF_8)) {
            w.write("add-generic-password -U -s " + KC_SERVICE + " -a " + KC_ACCOUNT + " -w " + hex + "\n");
        }
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!waitOk(p)) throw new IOException("the Keychain refused the session key: " + out.strip());
    }

    private static String keychainGet() throws IOException {
        var p = new ProcessBuilder("security", "find-generic-password", "-s", KC_SERVICE, "-a", KC_ACCOUNT, "-w").start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!waitOk(p) || out.length() != 64) throw new IOException("the session key is not in this user's Keychain; restore it from its recovery code");
        return out;
    }

    private static boolean waitOk(Process p) throws IOException {
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the Keychain", e);
        }
    }

    static String b32(byte[] in) {
        var out = new StringBuilder();
        int buf = 0, bits = 0;
        for (var b : in) {
            buf = (buf << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) { out.append(B32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
            buf &= (1 << bits) - 1;
        }
        if (bits > 0) out.append(B32.charAt((buf << (5 - bits)) & 31));
        return out.toString();
    }

    static byte[] unb32(String s) {
        var out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("this recovery code is not right: '" + c + "' is not a valid character");
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) { out.write((buf >> (bits - 8)) & 0xff); bits -= 8; buf &= (1 << bits) - 1; }
        }
        var all = out.toByteArray();
        if (all.length != 35) throw new IllegalArgumentException("this recovery code is not right: it has the wrong length");
        return all;
    }
}
