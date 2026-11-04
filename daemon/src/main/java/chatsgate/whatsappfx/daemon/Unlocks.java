package chatsgate.whatsappfx.daemon;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Paths2;

import module java.base;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * The walls the product keeps up: command-line sending, and sending to groups, stay off until the developer has read a person's
 * written reason and issued a code for it. A code is a signature, made with a private key only the developer holds, over
 * (feature, the logged-in number, the reason), so it opens one feature, for one number, for that stated reason, and cannot be
 * passed on to another number. The app holds the matching public key, so it can check a code and never make one.
 *
 * <p>The reason the user wrote is kept verbatim in {@code unlocks.json} beside the code. Records of codes and declarations go
 * to the vault by the user's own hand. "Wallet open" mode (see {@link Hub#walletOpen()}) lets requests through for up to four hours without them.
 */
public final class Unlocks {

    public enum Feature {
        /** Any command-line sending (an agent or a script acting for the user), other than a note to yourself. */
        AUTOMATION("automation"),
        /** Sending to groups and communities, on top of automation. */
        GROUPS("groups");

        public final String word;

        Feature(String word) { this.word = word; }
    }

    public record Entry(String feature, String number, String reason, String code) {}

    /**
     * THE SWITCH. Whether the walls are up at all. OFF for WhatsappFx, by the user's own decision (2026-11-04): here reading, listing and
     * sending to one chat, a group, a channel or a broadcast list need only the per-program approval (F2), and no developer code.
     * The mechanism is built and tested because another product may reuse it, where command-line access is meant to stay closed in the
     * public build; turning it on for WhatsappFx later is this one line. A field and not a constant so the test harness can raise it.
     */
    public static volatile boolean REQUIRED = false;

    /** Ed25519 public key (X.509), made by {@code prp/18-prp.02.issue-unlock.java keygen}. */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAK++dZ82taNeVtqhLCeGQG4hQ4aeaDZzOT+wjbeDwOX8=";

    private static final Gson GSON = new Gson();
    private final Path file = Paths2.home().resolve("unlocks.json");

    /** Whether this feature is unlocked for this number: a record of it exists whose code still verifies. */
    public synchronized boolean has(Feature f, String number) {
        return number != null && load().stream().anyMatch(e -> e.feature().equals(f.word) && number.equals(e.number()) && verify(f, e.number(), e.reason(), e.code()));
    }

    /** Checks and keeps one code; returns null when it is accepted, else the reason it is not. */
    public synchronized String add(Feature f, String number, String reason, String code) {
        if (number == null) return "this PC is not linked to a number yet";
        if (reason == null || reason.isBlank()) return "write the reason you need this";
        if (code == null || code.isBlank()) return "enter the code you were sent";
        if (!verify(f, number, reason, code.strip())) return "this code does not match this number, this feature and this reason";
        var all = new ArrayList<>(load());
        all.removeIf(e -> e.feature().equals(f.word) && number.equals(e.number()));
        all.add(new Entry(f.word, number, normalise(reason), code.strip()));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(all));
            Log.info("unlocked " + f.word + " for +" + number);
            return null;
        } catch (IOException e) {
            return "could not save the unlock: " + e.getMessage();
        }
    }

    public synchronized List<Entry> records() { return List.copyOf(load()); }

    static boolean verify(Feature f, String number, String reason, String code) {
        try {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            var s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(message(f, number, reason));
            return s.verify(Base64.getUrlDecoder().decode(code));
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] message(Feature f, String number, String reason) {
        return ("whatsappfx-unlock-v1|" + f.word + "|" + number + "|" + normalise(reason)).getBytes(StandardCharsets.UTF_8);
    }

    static String normalise(String reason) { return reason.strip().replaceAll("\\s+", " "); }

    private List<Entry> load() {
        try {
            if (!Files.isRegularFile(file)) return List.of();
            List<Entry> l = GSON.fromJson(Files.readString(file), new TypeToken<List<Entry>>() {}.getType());
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }
}
