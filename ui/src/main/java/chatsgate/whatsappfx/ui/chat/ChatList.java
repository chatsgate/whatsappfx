package chatsgate.whatsappfx.ui.chat;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.ListView;

import java.util.List;

/** The left pane: every conversation, newest activity first, filtered by the search box. */
final class ChatList {

    final ObservableList<ChatRow> rows = FXCollections.observableArrayList();
    final FilteredList<ChatRow> shown = new FilteredList<>(rows);
    final ListView<ChatRow> view = new ListView<>(shown);

    ChatList(ChatData data) {
        view.getStyleClass().add("chat-list");
        view.setFocusTraversable(false);
        view.setCellFactory(v -> new ChatCell(data));
        view.setMinWidth(300);
        view.setPrefWidth(380);
    }

    /** Keeps the selected chat selected across a reload. */
    void show(List<ChatRow> all) {
        var keep = selected();
        rows.setAll(all);
        if (keep != null) select(keep.jid);
        else if (!shown.isEmpty()) view.getSelectionModel().select(0);
    }

    void select(String jid) {
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).jid.equals(jid)) {
                view.getSelectionModel().select(i);
                Flows.reveal(view, i);
                return;
            }
        }
    }

    String needle = "";
    /** Which chips is on: "all", "unread" or "groups". */
    String chip = "all";

    void filter(String text) {
        needle = text == null ? "" : text.strip();
        refilter();
    }

    void chip(String which) {
        chip = which;
        refilter();
    }

    private void refilter() {
        var keep = selected();
        var t = needle;
        shown.setPredicate(r -> (t.isEmpty() || r.matches(t))
                && switch (chip) { case "unread" -> r.unread > 0; case "groups" -> r.group; default -> true; });
        if (keep != null && shown.contains(keep)) select(keep.jid);
        else if (!shown.isEmpty()) view.getSelectionModel().select(0);
    }

    void move(int by) {
        if (shown.isEmpty()) return;
        var sm = view.getSelectionModel();
        var i = Math.max(0, Math.min(shown.size() - 1, sm.getSelectedIndex() + by));
        sm.select(i);
        Flows.reveal(view, i);
    }

    ChatRow selected() {
        return view.getSelectionModel().getSelectedItem();
    }
}
