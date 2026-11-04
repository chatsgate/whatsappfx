package chatsgate.whatsappfx.ui.notify;

import javafx.scene.input.KeyCode;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.ui.chat.ChatWindow;

/** A new message: who and what, a reply box, F5 to open the chat. A second message from the same chat updates it. */
final class MessageCard extends Card {

    static final double SECONDS = 15;
    int count = 1;

    MessageCard(Hub hub, String title, String body, String chat) {
        super(hub, chat == null ? "msg:" + hub.account + ":*" : "msg:" + hub.account + ":" + chat, chat, SECONDS);
        this.title.setText(title);
        this.text.setText(body);
        action(KeyCode.F5, "Open chat", "blue", () -> { dismiss(); ChatWindow.show(hub, chat); });
        if (chat == null) {
            reply.setVisible(false);
            reply.setManaged(false);
        }
    }

    void more(String title, String body) {
        count++;
        this.title.setText(title + (count > 1 ? "  (" + count + " new)" : ""));
        this.text.setText(body);
        timer.playFromStart();
        Cards.place();
    }

    /** FX thread. One card per chat: a newer message updates the card that is already up. */
    static void show(Hub hub, String title, String body, String chat) {
        var open = Cards.find(chat == null ? "msg:" + hub.account + ":*" : "msg:" + hub.account + ":" + chat);
        if (open instanceof MessageCard m) m.more(title, body);
        else Cards.add(new MessageCard(hub, title, body, chat));
    }
}
