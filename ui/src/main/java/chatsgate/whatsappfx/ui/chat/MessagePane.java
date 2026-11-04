package chatsgate.whatsappfx.ui.chat;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;

/** The right pane: the open chat's header, its messages, and the compose box. */
final class MessagePane {

    final ChatData data;
    final ObservableList<Msg> items = FXCollections.observableArrayList();
    final ListView<Msg> view = new ListView<>(items);
    final Avatar avatar = new Avatar(40);
    final RichText title = new RichText(22);
    final Label sub = new Label();
    final TextArea compose = new TextArea();
    /** "Replying to …" or "Editing …" over the write box, while one of those is under way. */
    final Label context = new Label();
    /** The right end of the header: calls and search (dim), then the menu button ChatWindow adds. */
    final HBox tools = new HBox(2);
    /** Files waiting to be sent; the card sits above the write box. */
    final Attach attach = new Attach();
    /** Opens the file chooser; set by ChatWindow. */
    Runnable onAttach;
    /** Sends what is in the write box; set by ChatWindow. */
    Runnable onSend;
    static final javafx.scene.paint.Color DIM = javafx.scene.paint.Color.web("#6d6f6f");
    static final javafx.scene.paint.Color GREEN = javafx.scene.paint.Color.web("#21c063");
    final VBox node;
    String jid;
    /** The phone number of the open chat, digits only; null for a group, or a LID with no number known. */
    String phone;
    boolean group;

    MessagePane(ChatData data) {
        this.data = data;
        view.getStyleClass().add("msg-list");
        view.setFocusTraversable(false);
        view.setCellFactory(v -> new MessageCell(this, v));
        view.setPlaceholder(new Label("Choose a chat on the left"));
        avatar.setVisible(false);
        sub.getStyleClass().add("chat-sub");
        var names = new VBox(1, title, sub);
        names.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(names, Priority.ALWAYS);
        var header = new HBox(12, avatar, names, tools);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 16, 8, 16));
        header.getStyleClass().add("chat-header");
        tools.setAlignment(Pos.CENTER_RIGHT);
        // Calls, in-chat search and attachments are not features of this app yet: they are drawn dim, with a tooltip saying so.
        // Calls are not supported: struck through, dimmed, and the tooltip says so. Search in a chat is not there yet.
        for (var n : new String[]{"video", "call"}) tools.getChildren().add(Icons.crossed(Icons.button(n, 22, DIM, "Calls are not supported in WhatsappFx")));
        tools.getChildren().add(dim(Icons.button("search", 22, DIM, "Search in a chat is not available in WhatsappFx yet")));

        compose.setPromptText("Type a message");
        compose.setWrapText(true);
        compose.setPrefRowCount(1);
        compose.getStyleClass().add("compose-box");
        HBox.setHgrow(compose, Priority.ALWAYS);
        var send = Icons.button("send", 22, GREEN, "Send (Enter)");
        Icons.accent(send); // the number's colour, whatever it is set to
        send.setOnMouseClicked(e -> { if (onSend != null) onSend.run(); });
        var mic = dim(Icons.button("mic", 22, DIM, "Voice messages are not available in WhatsappFx yet"));
        var action = new javafx.scene.layout.StackPane(mic, send);
        send.visibleProperty().bind(compose.textProperty().isNotEmpty());
        mic.visibleProperty().bind(compose.textProperty().isEmpty());
        var attachButton = Icons.button("attach", 22, javafx.scene.paint.Color.web("#a2a3a3"), "Attach files (F6). You can also paste a picture or files, or drop them here.");
        attachButton.setOnMouseClicked(e -> { if (onAttach != null) onAttach.run(); });
        var pill = new HBox(2, attachButton,
                dim(Icons.button("emoji", 22, DIM, "Emoji are not available in WhatsappFx yet")), compose, action);
        pill.setAlignment(Pos.CENTER_LEFT);
        pill.getStyleClass().add("write-pill");
        HBox.setHgrow(pill, Priority.ALWAYS);
        var write = new HBox(pill);
        write.setPadding(new Insets(8, 16, 12, 16));
        write.getStyleClass().add("write-area");

        VBox.setVgrow(view, Priority.ALWAYS);
        context.getStyleClass().add("context-bar");
        context.setMaxWidth(Double.MAX_VALUE);
        context.setPadding(new Insets(6, 16, 6, 16));
        showContext(null);
        node = new VBox(header, view, context, attach.bar, write);
        node.getStyleClass().add("chat-pane");
        node.setMinWidth(380);
    }

    static javafx.scene.Node dim(javafx.scene.Node n) {
        n.setOpacity(0.55);
        return n;
    }

    void open(ChatRow row) {
        jid = row.jid;
        avatar.setVisible(true);
        phone = row.group || row.phone == null || row.jid.endsWith("@lid") && row.phone.equals(ChatData.digits(row.jid)) ? null : row.phone;
        group = row.group;
        avatar.set(row.name, row.jid, row.group);
        title.set(row.name, "-fx-font-size: 16px;");
        sub.setText(row.group ? "group" : row.phone == null ? "" : "+" + row.phone);
        items.clear();
        load(jid);
    }

    /** The newest page again: after a send, or F5. */
    void load(String forJid) {
        if (forJid == null) return;
        data.io.submit(() -> {
            var page = data.messages(forJid, null);
            Platform.runLater(() -> {
                if (!forJid.equals(jid)) return;
                items.setAll(Times.days(page));
                if (!items.isEmpty()) view.scrollTo(items.size() - 1);
            });
        });
    }

    /**
     * What arrived since the newest shown, appended so older pages fetched with F8 stay; the "sending…" stand-ins
     * go once the real messages are in. Scrolls down only if the last message was in view.
     */
    void newer() {
        if (jid == null) return;
        var forJid = jid;
        data.io.submit(() -> {
            var page = data.messages(forJid, null);
            Platform.runLater(() -> {
                if (!forJid.equals(jid)) return;
                var shown = new java.util.HashSet<String>();
                var all = new ArrayList<Msg>();
                for (var m : items) if (m.id() != null) { shown.add(m.id()); all.add(m); }
                var added = 0;
                for (var m : page) if (shown.add(m.id())) { all.add(m); added++; }
                if (added == 0 && all.size() == items.size()) return;
                var atEnd = Flows.lastVisible(view) >= items.size() - 1;
                all.sort(java.util.Comparator.comparingLong(Msg::ts));
                items.setAll(Times.days(all));
                if (atEnd) view.scrollTo(items.size() - 1);
            });
        });
    }

    /** The page before the oldest shown, kept in place on screen. */
    void older(java.util.function.Consumer<String> say) {
        if (jid == null || items.isEmpty()) return;
        var forJid = jid;
        var oldest = items.get(0).ts();
        data.io.submit(() -> {
            var page = data.messages(forJid, oldest);
            Platform.runLater(() -> {
                if (!forJid.equals(jid)) return;
                if (page.isEmpty()) { say.accept("no older messages in the index"); return; }
                var all = new ArrayList<Msg>(page);
                all.addAll(items);
                items.setAll(Times.days(all));
                view.scrollTo(page.size());
                say.accept(page.size() + " older messages");
            });
        });
    }

    /** Drawn at once with "sending…"; replaced when the index has the real one. */
    void pending(String text) {
        var now = System.currentTimeMillis() / 1000;
        var last = items.isEmpty() ? null : items.get(items.size() - 1);
        var day = last == null || !Times.date(last.ts()).equals(Times.date(now)) ? Times.day(now) : null;
        items.add(new Msg(null, true, now, null, text, false, null, day, null));
        view.scrollTo(items.size() - 1);
    }

    /** null hides it. */
    void showContext(String text) {
        context.setText(text == null ? "" : text);
        context.setVisible(text != null);
        context.setManaged(text != null);
    }

}
