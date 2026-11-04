package chatsgate.whatsappfx.ui.chat;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/**
 * What a right click on a message offers: copy it, copy or open its links, reply quoting it, react, star, and edit
 * when it is our own text sent in the last 15 minutes (WhatsApp refuses later edits). Writes go through the window.
 */
final class MessageMenu {

    static final String[] REACTIONS = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
    static final long EDIT_WINDOW_S = 15 * 60;

    private MessageMenu() {}

    static ContextMenu of(ChatWindow w, Msg m) {
        var menu = new ContextMenu();
        menu.getItems().add(item("Copy text", () -> copy(m.plain())));
        var links = RichText.linksIn(m.plain());
        for (var link : links.stream().limit(3).toList()) {
            var shown = link.length() > 48 ? link.substring(0, 48) + "…" : link;
            menu.getItems().add(item("Open link  " + shown, () -> RichText.open(link)));
            menu.getItems().add(item("Copy link  " + shown, () -> copy(link)));
        }
        menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(item("Reply", () -> w.startReply(m)));
        var react = new Menu("React");
        for (var r : REACTIONS) react.getItems().add(item(r, () -> w.react(m, r)));
        if (m.reaction() != null && !m.reaction().isBlank()) react.getItems().add(item("Remove my reaction", () -> w.react(m, "")));
        menu.getItems().add(react);
        menu.getItems().add(item(m.starred() ? "Unstar" : "Star", () -> w.star(m, !m.starred())));
        var age = System.currentTimeMillis() / 1000 - m.ts();
        if (m.fromMe() && !m.plain().startsWith("📎") && age < EDIT_WINDOW_S) menu.getItems().add(item("Edit", () -> w.startEdit(m)));
        menu.getItems().forEach(i -> i.setMnemonicParsing(false));
        return menu;
    }

    static MenuItem item(String text, Runnable action) {
        var i = new MenuItem(text);
        i.setMnemonicParsing(false);
        i.setOnAction(e -> action.run());
        return i;
    }

    static void copy(String text) {
        var c = new ClipboardContent();
        c.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(c);
    }
}
