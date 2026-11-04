package chatsgate.whatsappfx.ui.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** A chat list row: avatar, name and time, then the last line and an unread badge. */
final class ChatCell extends ListCell<ChatRow> {

    final ChatData data;
    final Avatar avatar = new Avatar(49);
    final RichText name = new RichText(22);
    final Label time = new Label();
    final RichText snippet = new RichText(20);
    final Label badge = new Label();
    final HBox root;

    ChatCell(ChatData data) {
        this.data = data;
        time.getStyleClass().add("chat-time");
        badge.getStyleClass().add("unread-badge");
        badge.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        time.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(name, Priority.ALWAYS);
        HBox.setHgrow(snippet, Priority.ALWAYS);
        var top = new HBox(8, name, time);
        top.setAlignment(Pos.CENTER_LEFT);
        var bottom = new HBox(8, snippet, badge);
        bottom.setAlignment(Pos.CENTER_LEFT);
        var text = new VBox(2, top, bottom);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        root = new HBox(12, avatar, text);
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(11.5, 14, 11.5, 12));
        root.getStyleClass().add("chat-row");
        setPrefWidth(0); // the list sets the width; the row must not widen it
        setText(null);
    }

    @Override
    protected void updateItem(ChatRow row, boolean empty) {
        super.updateItem(row, empty);
        if (empty || row == null) {
            setGraphic(null);
            return;
        }
        avatar.set(row.name, row.jid, row.group);
        name.set(row.name, "-fx-font-size: 15px;");
        time.setText(Times.list(row.lastTs));
        var unread = Live.of(data.hub).unread(row);
        time.getStyleClass().remove("chat-time-unread");
        if (unread > 0) time.getStyleClass().add("chat-time-unread");
        badge.setText(String.valueOf(unread));
        badge.setVisible(unread > 0);
        badge.setManaged(unread > 0);
        snippet.set(row.snippet == null ? "" : row.snippet, "-fx-fill: -wa-muted; -fx-font-size: 13px;");
        if (!row.asked) fetchSnippet(row);
        setGraphic(root);
    }

    /** Once per row, on the io thread, and drawn only if this cell still shows that row. */
    void fetchSnippet(ChatRow row) {
        row.asked = true;
        data.io.submit(() -> {
            row.snippet = data.snippet(row.jid);
            Platform.runLater(() -> {
                if (getItem() == row) snippet.set(row.snippet, "-fx-fill: -wa-muted; -fx-font-size: 13px;");
            });
        });
    }
}
