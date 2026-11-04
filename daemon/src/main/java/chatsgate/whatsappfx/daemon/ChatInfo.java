package chatsgate.whatsappfx.daemon;

import module java.base;

/**
 * What the gate needs to know about a chat id: its name for the window, and what kind it is. The list of chats is a scan of the
 * whole index, so each answer is remembered for ten minutes; a name changing within that is harmless, a kind never changes.
 */
public final class ChatInfo {

    public record Info(String jid, String name, ChatKind kind) {}

    private record Held(Info info, long at) {}

    private static final long KEEP_MS = 10 * 60_000L;
    private static final Map<String, Held> held = new ConcurrentHashMap<>();

    private ChatInfo() {}

    public static Info of(Hub hub, String jid) {
        if (jid == null || jid.isBlank()) return new Info(null, null, ChatKind.NONE);
        var key = hub.account + "|" + jid;
        var h = held.get(key);
        if (h != null && System.currentTimeMillis() - h.at() < KEEP_MS) return h.info();
        var info = look(hub, jid);
        held.put(key, new Held(info, System.currentTimeMillis()));
        return info;
    }

    private static Info look(Hub hub, String jid) {
        String name = null, pn = null;
        try {
            for (var c : Queries.chats(hub.index.db(), hub.account, jid, 5)) {
                if (!jid.equals(c.get("jid"))) continue;
                name = c.get("name") == null ? null : c.get("name").toString();
                pn = c.get("pn") == null ? null : c.get("pn").toString();
                break;
            }
        } catch (RuntimeException e) {
            // The index not answering must not make a request unaskable; fall back to the shape of the id.
        }
        var own = hub.ownUser();
        var self = own != null && (own.equals(user(jid)) || own.equals(user(pn)));
        return new Info(jid, name, ChatKind.of(jid, self));
    }

    static String user(String jid) {
        if (jid == null) return null;
        var u = jid.split("[:@]")[0];
        return u.isBlank() ? null : u;
    }
}
