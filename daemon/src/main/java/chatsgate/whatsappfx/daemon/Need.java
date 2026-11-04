package chatsgate.whatsappfx.daemon;

/**
 * What one request needs a person to have approved. {@code chat} is the chat it names, or null for none (a listing, the app) or
 * for every chat. {@code sends} is true for a request that posts a new message: those are counted against the lease. {@code announce}
 * requests (a send, an edit, a delete) each raise a notification.
 */
public record Need(Tier tier, String chat, boolean sends, boolean announce, String detail) {

    public static Need list() { return new Need(Tier.LIST, null, false, false, null); }
    public static Need read(String chat) { return new Need(Tier.READ, chat, false, false, null); }
    public static Need control() { return new Need(Tier.CONTROL, null, false, false, null); }
    /** A new message: counted, and announced. */
    public static Need send(String chat) { return new Need(Tier.SEND, chat, true, true, null); }
    /** A reaction, a star, an edit, a delete: needs the SEND approval; edit and delete are announced, reactions are not. */
    public static Need change(String chat, boolean announce) { return new Need(Tier.SEND, chat, false, announce, null); }
}
