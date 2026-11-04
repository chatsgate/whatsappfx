package chatsgate.whatsappfx.core;

import java.util.Locale;

/**
 * Names that survive Windows, Google Drive and a 260-character path limit.
 *
 * <p>Truncation is deliberate and lossy on the display half only: the message id stays intact in
 * every filename, so the file is always traceable back to exactly one message.
 */
public final class Slug {

    private static final String ILLEGAL = "<>:\"/\\|?*";
    public static final int CHAT_MAX = 48, FILE_MAX = 60;

    private Slug() {}

    public static String clean(String raw, int max) {
        if (raw == null || raw.isBlank()) return "unnamed";
        var sb = new StringBuilder(raw.length());
        for (var c : raw.toCharArray()) {
            if (c < 0x20 || ILLEGAL.indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        var s = sb.toString().strip();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) s = "unnamed";
        return s.length() <= max ? s : s.substring(0, max).strip();
    }

    /** A chat folder name: the display name when there is one, else the user part of the JID. */
    public static String chat(String jid, String name) {
        var base = (name != null && !name.isBlank()) ? name : userPart(jid);
        return clean(base, CHAT_MAX) + "-" + shortHash(jid);
    }

    /** Keeps the extension while shortening the stem, so the file still opens with the right app. */
    public static String file(String original, int max) {
        if (original == null || original.isBlank()) return "";
        var name = clean(original, Integer.MAX_VALUE);
        var dot = name.lastIndexOf('.');
        if (dot <= 0 || name.length() - dot > 12) return clean(name, max);
        var stem = name.substring(0, dot);
        var ext = name.substring(dot);
        var room = Math.max(1, max - ext.length());
        return clean(stem, room) + ext.toLowerCase(Locale.ROOT);
    }

    public static String userPart(String jid) {
        if (jid == null) return "unknown";
        var at = jid.indexOf('@');
        return at < 0 ? jid : jid.substring(0, at);
    }

    /** Six characters of the JID's hash, so two chats with the same display name never collide. */
    public static String shortHash(String s) {
        var h = 0;
        for (var c : (s == null ? "" : s).toCharArray()) h = h * 31 + c;
        return Integer.toHexString(h & 0xFFFFFF);
    }
}
