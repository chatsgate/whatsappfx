package chatsgate.whatsappfx.ui.chat;

/** One conversation in the left list. The snippet is filled lazily, when the row is first drawn. */
final class ChatRow {

    final String jid, name, phone;
    final boolean group;
    final long lastTs, unread;
    volatile String snippet;
    volatile boolean asked;

    ChatRow(String jid, String name, String phone, boolean group, long lastTs, long unread) {
        this.jid = jid;
        this.name = name;
        this.phone = phone;
        this.group = group;
        this.lastTs = lastTs;
        this.unread = unread;
    }

    boolean matches(String needle) {
        var n = needle.toLowerCase();
        return name.toLowerCase().contains(n) || jid.contains(n) || phone != null && phone.contains(n);
    }
}
