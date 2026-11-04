package chatsgate.whatsappfx.ui.chat;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import chatsgate.whatsappfx.core.Activity;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.ui.Look;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The WhatsApp-like chat window: chats on the left, the open chat on the right, function keys along the bottom.
 * Made on demand and dropped whole when closed (no timeline, no listener left on the hub), so idle trim gets the
 * memory back. Letters belong to the search and compose boxes; every action is a function key.
 */
public final class ChatWindow {

    /** One window per number, open or not. */
    static final java.util.Map<Hub, ChatWindow> OPEN = new java.util.concurrent.ConcurrentHashMap<>();

    final Hub hub;
    final ChatData data;
    final ChatList list;
    final MessagePane pane;
    final TextField search = new TextField();
    final Label status = new Label();
    /** The other numbers, as coloured buttons on the rail: a click jumps to that number's window. Hidden with one number. */
    final VBox numberRail = new VBox(8);
    javafx.scene.Node numbersChip;
    final Stage stage = new Stage();
    String pendingJid;
    /** A reply or an edit under way from the message menu. */
    Msg replyTo, editing;
    boolean liveDue, openChanged;

    /** The open window of this number, or null. */
    public static ChatWindow current(Hub hub) {
        return OPEN.get(hub);
    }

    /** Closes this number's chats window, if open (the number was removed). FX thread. */
    public static void close(Hub hub) {
        var w = OPEN.get(hub);
        if (w != null) w.stage.close();
    }

    /** Whether any number has its chats window open. */
    public static boolean anyOpen() {
        return !OPEN.isEmpty();
    }

    /** Opens the window on {@code jid} (null: as it was), or brings the open one forward there. FX thread. */
    public static void show(Hub hub, String jid) {
        show(hub);
        if (jid != null) OPEN.get(hub).goTo(jid);
    }

    /** A draft from the command line: the chat is opened with this text in its write box, and nothing is sent. FX thread. */
    public static void draft(Hub hub, String jid, String text) {
        show(hub, jid);
        var w = OPEN.get(hub);
        w.pane.compose.setText(text);
        w.pane.compose.positionCaret(text.length());
        w.pane.compose.requestFocus();
    }

    /** Opens this number's window, or brings it forward (un-minimised). FX thread. */
    public static void show(Hub hub) {
        var w = OPEN.get(hub);
        if (w == null) {
            w = new ChatWindow(hub);
            OPEN.put(hub, w);
            w.stage.setMaximized(true); // the user's choice, 2026-10-27: from the tray or anywhere, it fills the screen
        }
        w.stage.show();
        w.stage.setIconified(false);
        w.stage.toFront();
        w.search.requestFocus();
    }

    ChatWindow(Hub hub) {
        this.hub = hub;
        data = new ChatData(hub);
        list = new ChatList(data);
        pane = new MessagePane(data);
        search.setPromptText("Search or start typing a name");
        search.getStyleClass().add("search-box");
        search.textProperty().addListener((o, was, now) -> list.filter(now));
        list.view.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == null || now.jid.equals(pane.jid)) return; // a live reload re-selects the same chat: not a read
            cancelContext();
            pane.open(now);
            Live.of(hub).read(now.jid);
            list.view.refresh();
        });
        var keys = new Legend()
                .bind(KeyCode.F1, "Settings", () -> chatsgate.whatsappfx.ui.SettingsWindow.show(hub))
                .bind(KeyCode.F2, "Search", search::requestFocus)
                .bind(KeyCode.F3, "Write", pane.compose::requestFocus)
                .bind(KeyCode.F4, "Numbers", this::nextNumber)
                .bind(KeyCode.F6, "Attach", this::chooseFiles)
                .bind(KeyCode.F5, "Refresh", this::reload)
                .bind(KeyCode.F8, "Older", () -> pane.older(status::setText))
                .bind(KeyCode.F9, null, this::openOfficial)
                .bind(KeyCode.F10, null, this::closeOfficial)
                .bind(KeyCode.F11, "Maximize", () -> stage.setMaximized(!stage.isMaximized()))
                .bind(KeyCode.ESCAPE, "Back", this::escape);
        var white = Color.web("#fafafa");
        var title = new Label(hub.title());
        title.getStyleClass().add("side-heading");
        var grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        var newChat = Icons.button("new-chat", 22, Color.web("#0b1a12"), "New chat: type a name (F2)");
        newChat.getStyleClass().add("new-chat-button");
        newChat.setOnMouseClicked(e -> search.requestFocus());
        var menu = Icons.button("more", 22, white, "Menu");
        menu.setOnMouseClicked(e -> mainMenu(menu));
        var sideHead = new HBox(6, title, grow, newChat, menu);
        sideHead.setAlignment(Pos.CENTER_LEFT);
        sideHead.setPadding(new Insets(0, 12, 0, 20));
        sideHead.setMinHeight(64);
        sideHead.setPrefHeight(64);
        var chips = new HBox(8);
        chips.setPadding(new Insets(10, 12, 8, 20));
        for (var c : new String[][]{{"all", "All"}, {"unread", "Unread"}, {"groups", "Groups"}}) {
            var chip = new Label(c[1]);
            chip.getStyleClass().add("filter-chip");
            if (c[0].equals("all")) chip.getStyleClass().add("on");
            chip.setOnMouseClicked(e -> {
                list.chip(c[0]);
                for (var n : chips.getChildren()) n.getStyleClass().remove("on");
                chip.getStyleClass().add("on");
            });
            chips.getChildren().add(chip);
        }
        var side = new VBox(0, sideHead, search, chips, list.view);
        VBox.setMargin(search, new Insets(0, 12, 0, 12));
        VBox.setVgrow(list.view, Priority.ALWAYS);
        side.getStyleClass().add("side");
        var chatsIcon = Icons.button("chat", 24, white, "Chats");
        chatsIcon.getStyleClass().add("rail-on");
        var settingsIcon = Icons.button("settings", 24, white, "Settings (F1)");
        settingsIcon.setOnMouseClicked(e -> chatsgate.whatsappfx.ui.SettingsWindow.show(hub));
        var railGrow = new Region();
        VBox.setVgrow(railGrow, Priority.ALWAYS);
        numberRail.setAlignment(Pos.TOP_CENTER);
        numbersChip = keys.bar.getChildren().stream().filter(n -> n instanceof HBox b && b.getChildren().getFirst() instanceof Label l && "F4".equals(l.getText())).findFirst().orElse(null);
        var rail = new VBox(8, chatsIcon, numberRail, railGrow, settingsIcon);
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setPadding(new Insets(12, 0, 14, 0));
        rail.setMinWidth(65);
        rail.setPrefWidth(65);
        rail.setMaxWidth(65);
        rail.getStyleClass().add("rail");
        pane.onSend = this::send;
        pane.onAttach = this::chooseFiles;
        pane.attach.onCancel = this::cancelAttach;
        // files and pictures dropped on the chat go to the card, as pasted ones do
        pane.node.setOnDragOver(e -> { var d = e.getDragboard(); if (pane.jid != null && (d.hasFiles() || d.hasImage())) e.acceptTransferModes(javafx.scene.input.TransferMode.COPY); e.consume(); });
        pane.node.setOnDragDropped(e -> { var got = pane.jid == null ? java.util.List.<java.nio.file.Path>of() : Attach.from(e.getDragboard()); attach(got); e.setDropCompleted(!got.isEmpty()); e.consume(); });
        var more = Icons.button("more", 22, white, "Menu");
        more.setOnMouseClicked(e -> chatMenu(more));
        pane.tools.getChildren().add(more);
        var panes = new HBox(rail, side, pane.node);
        HBox.setHgrow(pane.node, Priority.ALWAYS);
        VBox.setVgrow(panes, Priority.ALWAYS);
        status.getStyleClass().add("status-line");
        status.visibleProperty().bind(status.textProperty().isNotEmpty());
        status.managedProperty().bind(status.visibleProperty());
        // One quiet line: the keys on the left, the state on the right. The longer reminder is a tooltip, not a third row.
        var footGrow = new Region();
        HBox.setHgrow(footGrow, Priority.ALWAYS);
        Tooltip.install(keys.bar, new Tooltip("F9 opens and F10 closes the WhatsApp app. F4 goes to the next number. Up and Down choose a chat. Enter opens it, then sends. Shift+Enter is a new line. PgUp and PgDn scroll."));
        var foot = new HBox(16, keys.bar, footGrow, status);
        foot.setAlignment(Pos.CENTER_LEFT);
        foot.setMinHeight(34);
        foot.setPadding(new Insets(5, 16, 5, 20));
        foot.getStyleClass().add("foot");
        var root = new VBox(panes, foot);
        root.getStyleClass().add("wa-root");

        var scene = new Scene(root, 1100, 740);
        scene.setFill(Color.web("#161717"));
        scene.getStylesheets().add(ChatWindow.class.getResource("chat.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> { if (!keys.handle(e)) keys(e); });
        scene.addEventFilter(javafx.event.Event.ANY, e -> Activity.touch());
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(480);
        Look.dress(hub, stage, root, hub::title, true, () -> { title.setText(hub.title()); refreshNumbers(); });
        stage.setOnHidden(e -> release());
        stage.focusedProperty().addListener((o, was, now) -> {
            if (now && pane.jid != null) { Live.of(hub).read(pane.jid); list.view.refresh(); }
        });
        var area = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1100, area.getWidth() * 0.9));
        stage.setHeight(Math.min(740, area.getHeight() * 0.9));
        reload();
    }

    /** The rail's buttons for every number (this one ringed), and the F4 chip, shown only while there are several. FX thread. */
    void refreshNumbers() {
        var all = hub.accounts.all();
        var several = all.size() > 1;
        numberRail.getChildren().setAll(all.stream().map(h -> Look.badge(h, h == hub, 34, () -> show(h))).toList());
        numberRail.setVisible(several);
        numberRail.setManaged(several);
        if (numbersChip != null) { numbersChip.setVisible(several); numbersChip.setManaged(several); }
    }

    /** F4: the next number's window (opened if it is closed), round the list. */
    void nextNumber() {
        var all = hub.accounts.all();
        if (all.size() < 2) { status.setText("Only one number"); return; }
        show(all.get((all.indexOf(hub) + 1) % all.size()));
    }

    /** The menu on the chats list's heading: what the F-keys do, for someone using the mouse. */
    void mainMenu(javafx.scene.Node at) {
        var m = new ContextMenu();
        var settings = new MenuItem("Settings");
        settings.setOnAction(e -> chatsgate.whatsappfx.ui.SettingsWindow.show(hub));
        var refresh = new MenuItem("Refresh");
        refresh.setOnAction(e -> reload());
        var official = new MenuItem("Open the WhatsApp app");
        official.setOnAction(e -> openOfficial());
        var close = new MenuItem("Close the WhatsApp app");
        close.setOnAction(e -> closeOfficial());
        m.getItems().addAll(settings, refresh, new javafx.scene.control.SeparatorMenuItem(), official, close);
        var b = at.localToScreen(at.getBoundsInLocal());
        m.show(at, b.getMinX() - 150, b.getMaxY());
    }

    /** The menu on the open chat's header. */
    void chatMenu(javafx.scene.Node at) {
        var m = new ContextMenu();
        var older = new MenuItem("Older messages");
        older.setOnAction(e -> pane.older(status::setText));
        var refresh = new MenuItem("Refresh");
        refresh.setOnAction(e -> reload());
        var official = new MenuItem("Open this chat in the WhatsApp app");
        official.setOnAction(e -> openOfficial());
        m.getItems().addAll(older, refresh, official);
        var b = at.localToScreen(at.getBoundsInLocal());
        m.show(at, b.getMinX() - 190, b.getMaxY());
    }

    /** Selects that chat (a clicked notice); before the first load is in, it is selected when it arrives. */
    void goTo(String jid) {
        search.clear();
        pendingJid = jid;
        if (list.rows.stream().anyMatch(r -> r.jid.equals(jid))) { list.select(jid); pendingJid = null; }
        pane.compose.requestFocus();
    }

    void reload() {
        var keepJid = pane.jid;
        data.io.submit(() -> {
            var all = data.chats();
            Platform.runLater(() -> {
                list.show(all);
                if (pendingJid != null) { list.select(pendingJid); pendingJid = null; }
                pane.load(keepJid);
                status.setText(all.size() + " chats");
            });
        });
    }

    /**
     * A message arrived in {@code chat} (FX thread). Gathered for a moment, because a reconnect replays dozens: then
     * the list is re-read once and the open chat gets what is new.
     */
    void live(String chat) {
        if (chat.equals(pane.jid)) openChanged = true;
        if (liveDue) return;
        liveDue = true;
        CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
            liveDue = false;
            if (OPEN.get(hub) != this) return;
            if (openChanged) pane.newer();
            openChanged = false;
            if (stage.isFocused() && pane.jid != null) Live.of(hub).read(pane.jid);
            data.io.submit(() -> {
                var all = data.chats();
                Platform.runLater(() -> { if (OPEN.get(hub) == this) list.show(all); });
            });
        }));
    }

    void keys(KeyEvent e) {
        var c = e.getCode();
        // Ctrl+V (Cmd+V on a Mac) or Shift+Insert with files or a picture on the clipboard: they become an attachment, not text
        if (pane.jid != null && ((c == KeyCode.V && e.isShortcutDown()) || (c == KeyCode.INSERT && e.isShiftDown()))) {
            var got = Attach.from(javafx.scene.input.Clipboard.getSystemClipboard());
            if (!got.isEmpty()) { attach(got); e.consume(); }
            return;
        }
        if (c == KeyCode.ENTER && pane.compose.isFocused() && !e.isShiftDown() && pane.attach.pending()) { sendAttachments(); e.consume(); return; }
        if (c == KeyCode.ENTER && pane.compose.isFocused() && !e.isShiftDown()) send();
        else if (c == KeyCode.ENTER && pane.compose.isFocused()) pane.compose.insertText(pane.compose.getCaretPosition(), "\n");
        else if (c == KeyCode.ENTER && search.isFocused() && list.selected() != null) pane.compose.requestFocus();
        else if ((c == KeyCode.UP || c == KeyCode.DOWN) && search.isFocused()) list.move(c == KeyCode.UP ? -1 : 1);
        else if (c == KeyCode.PAGE_UP || c == KeyCode.PAGE_DOWN) Flows.page(pane.view, c == KeyCode.PAGE_UP ? -1 : 1);
        else return;
        e.consume();
    }

    /** F6 and the paperclip: choose files to send to the open chat. */
    void chooseFiles() {
        if (pane.jid == null) { status.setText("choose a chat first"); return; }
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Send files to this chat");
        var picked = chooser.showOpenMultipleDialog(stage);
        if (picked != null) attach(picked.stream().map(java.io.File::toPath).toList());
    }

    /** The files wait on a card above the write box until Enter sends them or Esc drops them. */
    void attach(java.util.List<java.nio.file.Path> files) {
        if (files.isEmpty()) return;
        if (pane.jid == null) { status.setText("choose a chat first"); return; }
        pane.attach.set(files);
        pane.compose.requestFocus();
        status.setText(files.size() + (files.size() == 1 ? " file" : " files") + " ready: Enter sends, Esc cancels");
    }

    void cancelAttach() {
        pane.attach.clear();
        status.setText("attachment cancelled");
        pane.compose.requestFocus();
    }

    /** Enter with files waiting: the text in the box is the caption of the first; each file goes as its own message. */
    void sendAttachments() {
        var jid = pane.jid;
        var files = java.util.List.copyOf(pane.attach.files);
        var caption = pane.compose.getText().strip();
        if (jid == null || files.isEmpty()) return;
        pane.attach.clear();
        pane.compose.clear();
        status.setText("sending " + files.size() + (files.size() == 1 ? " file" : " files") + "…");
        data.io.submit(() -> {
            var failed = new java.util.ArrayList<String>();
            for (var i = 0; i < files.size(); i++) {
                try {
                    data.sendFile(jid, files.get(i), i == 0 && !caption.isEmpty() ? caption : null);
                } catch (Exception ex) {
                    Log.warn("chat window file send failed: " + ex.getMessage());
                    failed.add(files.get(i).getFileName() + ": " + ex.getMessage());
                }
            }
            Platform.runLater(() -> {
                if (failed.isEmpty()) status.setText("sent");
                else {
                    status.setText("NOT sent (" + String.join("; ", failed) + ")");
                    if (!caption.isEmpty()) pane.compose.setText(caption);
                }
            });
            CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                    .execute(() -> Platform.runLater(() -> { if (OPEN.get(hub) == this) pane.newer(); }));
        });
    }

    void send() {
        var text = pane.compose.getText().strip();
        var jid = pane.jid;
        if (text.isEmpty() || jid == null) return;
        if (editing != null) {
            var id = editing.id();
            cancelContext();
            pane.compose.clear();
            write("editing", () -> data.edit(jid, id, text), () -> pane.load(jid));
            return;
        }
        if (replyTo != null) {
            var id = replyTo.id();
            cancelContext();
            pane.compose.clear();
            pane.pending(text);
            write("replying", () -> data.reply(jid, id, text), null);
            CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                    .execute(() -> Platform.runLater(() -> { if (OPEN.get(hub) == this) pane.newer(); }));
            return;
        }
        pane.compose.clear();
        pane.pending(text);
        status.setText("sending…");
        data.io.submit(() -> {
            String result;
            try {
                data.send(jid, text);
                result = null;
            } catch (Exception ex) {
                Log.warn("chat window send failed: " + ex.getMessage());
                result = ex.getMessage();
            }
            var err = result;
            Platform.runLater(() -> {
                status.setText(err == null ? "sent" : "NOT sent (" + err + "): the text is back in the box");
                if (err != null) pane.compose.setText(text);
            });
            // the connector echoes the sent message into the index a moment later
            CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS)
                    .execute(() -> Platform.runLater(() -> { if (OPEN.get(hub) == this) pane.newer(); }));
        });
    }

    /** A blocking write for the message menu. */
    interface Write {
        void run() throws Exception;
    }

    /**
     * Runs a write on the io thread, saying how it went in the status line. {@code then} runs on the FX thread when it succeeded.
     */
    void write(String what, Write body, Runnable then) {
        status.setText(what + "…");
        data.io.submit(() -> {
            String err = null;
            try {
                body.run();
            } catch (Exception ex) {
                err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Log.warn("chat window " + what + " failed: " + err);
            }
            var failed = err;
            Platform.runLater(() -> {
                status.setText(failed == null ? what + ": done" : what + " FAILED: " + failed);
                if (failed == null && then != null) then.run();
            });
        });
    }

    /** The official app, for what this one cannot do yet: on the open chat when it has a phone number. */
    void openOfficial() {
        chatsgate.whatsappfx.ui.OfficialApp.open(pane.phone);
        status.setText(pane.phone == null ? "opening the WhatsApp app…" : "opening this chat in the WhatsApp app…");
    }

    /** Ends the official app and its WebView2 processes, which closing its window leaves running. */
    void closeOfficial() {
        status.setText("closing the WhatsApp app…");
        Thread.ofVirtual().start(() -> {
            var n = chatsgate.whatsappfx.ui.OfficialApp.close();
            Platform.runLater(() -> status.setText(n == 0 ? "the WhatsApp app was not running" : "WhatsApp app closed (" + n + " processes ended)"));
        });
    }

    void startReply(Msg m) {
        editing = null;
        replyTo = m;
        var who = m.fromMe() ? "yourself" : m.who() == null ? "them" : m.who();
        var line = m.plain().replace('\n', ' ');
        pane.showContext("Replying to " + who + ":  " + (line.length() > 90 ? line.substring(0, 90) + "…" : line) + "      Esc cancels");
        pane.compose.requestFocus();
    }

    void startEdit(Msg m) {
        replyTo = null;
        editing = m;
        pane.compose.setText(m.plain());
        pane.compose.positionCaret(pane.compose.getText().length());
        pane.showContext("Editing your message: Enter saves, Esc cancels");
        pane.compose.requestFocus();
    }

    void cancelContext() {
        replyTo = null;
        editing = null;
        pane.showContext(null);
    }

    void react(Msg m, String emoji) {
        var jid = pane.jid;
        write(emoji.isEmpty() ? "removing the reaction" : "reacting " + emoji, () -> data.react(jid, m.id(), emoji), () -> pane.load(jid));
    }

    void star(Msg m, boolean on) {
        var jid = pane.jid;
        write(on ? "starring" : "unstarring", () -> data.star(jid, m.id(), on), () -> pane.load(jid));
    }

    void escape() {
        if (pane.attach.pending()) { cancelAttach(); return; }
        if (pane.context.isVisible()) { if (editing != null) pane.compose.clear(); cancelContext(); pane.compose.requestFocus(); }
        else if (pane.compose.isFocused()) search.requestFocus();
        else if (!search.getText().isEmpty()) search.clear();
        else stage.close();
    }

    void release() { // closed: drop everything so the memory can be trimmed
        data.close();
        stage.setScene(null);
        OPEN.remove(hub, this);
        chatsgate.whatsappfx.ui.App.trimWhenHidden();
        Log.info("chat window closed and released");
    }
}
