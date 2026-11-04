package chatsgate.whatsappfx.ui.chat;

/**
 * One message as the window draws it. {@code text} carries mentions already named (marked for {@link RichText});
 * {@code day} is set on the first message of a calendar day.
 */
record Msg(String id, boolean fromMe, long ts, String who, String text, boolean starred, String reaction, String day, Pic pic) {

    /**
     * What a picture, video or sticker message shows: the small JPEG WhatsApp carries inside the message (always there, no
     * download), and the file itself when it is on this machine and a format the window can draw. {@code w} and {@code h} are
     * the full picture's size, 0 when unknown.
     */
    record Pic(String kind, byte[] thumb, String file, int w, int h, String name, long size, String chat, String id) {

        boolean picture() {
            return "image".equals(kind) || "video".equals(kind) || "sticker".equals(kind);
        }
    }

    Msg withDay(String d) {
        return new Msg(id, fromMe, ts, who, text, starred, reaction, d, pic);
    }

    /** The text as a person would copy it: mentions as "@Name", no marks. */
    String plain() {
        return People.plain(text);
    }
}
