package chatsgate.whatsappfx.daemon;

/**
 * What a program asks to do through whatsappcli, in the order of what it reaches. An approval covers its own tier and every
 * tier below it (SEND also reads and lists), except CONTROL, which covers only itself: controlling the app is not reading it.
 * SEND includes changing and deleting the user's own messages, so a mistake can be undone.
 */
public enum Tier {
    LIST("list", "see the names of your chats and contacts"),
    READ("read", "read messages"),
    SEND("send", "send messages and reactions as you, and change or delete your own messages"),
    CONTROL("control", "control WhatsappFx itself: stop it, link or unlink this PC, change what it does");

    public final String word, verb;

    Tier(String word, String verb) {
        this.word = word;
        this.verb = verb;
    }

    public boolean covers(Tier asked) {
        if (asked == CONTROL || this == CONTROL) return asked == this;
        return ordinal() >= asked.ordinal();
    }

    public static Tier of(String word) {
        if ("write".equalsIgnoreCase(word)) return SEND;
        for (var t : values()) if (t.word.equalsIgnoreCase(word)) return t;
        return READ;
    }
}
