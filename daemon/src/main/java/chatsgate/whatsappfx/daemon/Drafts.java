package chatsgate.whatsappfx.daemon;

import module java.base;

/**
 * {@code send --draft}: a message for somebody else that nobody has sent. It goes to the user's OWN chat as WhatsApp's official
 * click-to-chat link (whatsapp.com/faq/en/general/26000030). With a number, {@code https://wa.me/<number>?text=<text>} opens that
 * person's chat with the text in the write box. Without one, {@code https://wa.me/?text=<text>} opens WhatsApp with the text ready
 * and lets the user PICK the chat, which is how a group draft works (a group has no number). Either way the text is also written
 * out in the message so it can be read or copied, and it is put in the write box of that chat in the app's window, unsent.
 * Everything the command line does here is a send to yourself, which affects nobody else.
 */
final class Drafts {

    /** WhatsApp documents no limit; a link this long is where links in chats stop being reliable. */
    static final int MAX_LINK = 1800;

    private Drafts() {}

    static Map<String, Object> make(Hub hub, String target, String text) throws Exception {
        var info = ChatInfo.of(hub, target);
        if (info.kind() == ChatKind.SELF) throw new IllegalArgumentException("that is your own chat: use send, there is nothing to draft");
        var self = hub.ownChat();
        if (self == null) throw new IllegalStateException("this PC is not linked to a number yet");
        var number = info.kind() == ChatKind.SINGLE ? numberOf(hub, target) : null;
        var link = link(number, text);
        var who = info.name() != null ? info.name() : target;
        String note = null;
        String body;
        if (link.length() <= MAX_LINK) {
            body = "Draft for " + who + ". Tap the link to open WhatsApp with the text ready" + (number == null ? ", then choose the chat" : "") + ", review, and send:\n"
                    + link + "\n\n" + text;
            if (number == null) note = "no phone number for this chat, so the link opens WhatsApp and you choose the chat";
        } else {
            link = null;
            body = "Draft for " + who + ":\n\n" + text;
            note = "the text is too long for a link, so it was sent as plain text";
        }
        var reply = hub.connector.call("send.text", Map.of("chat", self, "text", body), 120);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
        var hook = hub.draftHook;
        if (hook != null) hook.accept(target, text);
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("for", who);
        out.put("link", link);
        out.put("sentTo", "your own chat");
        out.put("inWindow", hook != null);
        if (note != null) out.put("note", note);
        return out;
    }

    /** With a number: that person's chat. Without: WhatsApp opens and the user picks the chat. */
    static String link(String number, String text) {
        return "https://wa.me/" + (number == null ? "" : number) + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** The phone number of a one-to-one chat: the id itself, or the number the contact list holds for a LID chat. */
    private static String numberOf(Hub hub, String jid) {
        if (jid.endsWith("@s.whatsapp.net")) return ChatInfo.user(jid);
        try {
            for (var c : Queries.chats(hub.index.db(), hub.account, jid, 5))
                if (jid.equals(c.get("jid")) && c.get("pn") != null) return ChatInfo.user(c.get("pn").toString());
        } catch (RuntimeException e) {
            // no number known: the link then has none, and the user picks the chat
        }
        return null;
    }
}
