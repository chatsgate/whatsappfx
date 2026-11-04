package chatsgate.whatsappfx.daemon;

/** What sort of conversation a chat id is; it changes what sending there costs, not only where it goes. */
public enum ChatKind {
    /** Not about one chat: a listing, or the app itself. */
    NONE("", ""),
    /** The user's own "message yourself" chat: sending there reaches nobody else. */
    SELF("your own chat", "yourself"),
    SINGLE("one person", "one person"),
    /** Groups and communities. */
    GROUP("a group", "a group"),
    /** Channels, status updates and broadcast lists: never sent to from the command line. */
    BROADCAST("a channel, status or broadcast list", "a broadcast place");

    public final String label, noun;

    ChatKind(String label, String noun) {
        this.label = label;
        this.noun = noun;
    }

    /** By the shape of the id; {@code self} is decided by the caller, who knows which number is the user's own. */
    public static ChatKind of(String jid, boolean self) {
        if (jid == null || jid.isBlank()) return NONE;
        if (jid.endsWith("@g.us")) return GROUP;
        if (jid.endsWith("@newsletter") || jid.endsWith("@broadcast")) return BROADCAST;
        return self ? SELF : SINGLE;
    }
}
