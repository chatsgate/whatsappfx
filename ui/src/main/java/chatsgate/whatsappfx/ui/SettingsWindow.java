package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.core.Activity;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Autostart;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.ui.link.LinkWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import leasegate.frame.FKeys;
import leasegate.frame.TaskbarTheme;
import leasegate.ui.AccessListWindow;

import module java.base;

/**
 * Settings, shaped like WhatsApp's own: a list of sections on the left, one section at a time on the right, each row a title with
 * a grey line under it and, where there is something to switch, a switch at the end. Linking a device is not here: that is the
 * Link window, which opens by itself when the PC is not linked. Up and Down choose the section, the F-keys on the legend do the
 * section's actions, Esc closes. Made when opened and dropped when closed.
 */
public final class SettingsWindow {

    /** One settings window per number. */
    private static final Map<Hub, SettingsWindow> OPEN = new ConcurrentHashMap<>();

    final Hub hub;

    /** One action of a section: a key, its legend label and what it does. */
    record Act(KeyCode key, String label, Runnable run) {}

    enum Section {
        LINKED("Linked device", "This PC's link to your phone"),
        NUMBER("This number", "Its name and colour; add another number"),
        PROGRAMS("Command line", "Programs approved to use whatsappcli"),
        ATTACHMENTS("Attachments", "What was filed, and where"),
        GENERAL("General", "Start at logon, sounds"),
        ABOUT("About", "Version, data, licences");

        final String title, sub;

        Section(String title, String sub) {
            this.title = title;
            this.sub = sub;
        }
    }

    final Stage stage = new Stage();
    final VBox side = new VBox();
    final VBox content = new VBox();
    final StackPane legend = new StackPane();
    final Label said = new Label();
    Section current = Section.LINKED;
    List<Act> acts = List.of();
    String status = "";
    long removeAsked;

    static boolean isOpen() {
        return !OPEN.isEmpty();
    }

    /** FX thread. Opens this number's settings window, or brings it forward. */
    public static void show(Hub hub) {
        var w = OPEN.computeIfAbsent(hub, SettingsWindow::new);
        w.stage.show();
        w.stage.setIconified(false);
        w.stage.toFront();
        w.stage.requestFocus();
    }

    /** As {@link #show}, on the section where the number's name and colour are set. */
    public static void showNumber(Hub hub) {
        show(hub);
        OPEN.get(hub).choose(Section.NUMBER);
    }

    /** The one line that says how things stand, on top of the tray menu and in the chats window. */
    static String headline(Hub hub) {
        var s = hub.status();
        if (!Boolean.TRUE.equals(s.get("connectorAlive"))) return "The WhatsApp connector is not running";
        if (!Boolean.TRUE.equals(s.get("loggedIn"))) return "Not linked to WhatsApp: open settings to link this PC";
        var who = s.get("pushName") == null ? "" : " as " + s.get("pushName");
        var online = s.get("connectedSince") != null;
        return "Linked" + who + (online ? ", online" : ", offline")
                + (num(s, "approvals") == 0 ? "" : " · " + num(s, "approvals") + " program approval" + (num(s, "approvals") == 1 ? "" : "s"));
    }

    private SettingsWindow(Hub hub) {
        this.hub = hub;
        var title = new Label("Settings");
        title.getStyleClass().add("settings-title");
        side.getStyleClass().add("settings-side");
        var sideBox = new VBox(title, side);
        sideBox.getStyleClass().add("settings-sidebox");
        sideBox.setMinWidth(300);
        sideBox.setMaxWidth(300);
        content.getStyleClass().add("settings-content");
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");
        said.getStyleClass().add("settings-said");
        said.setWrapText(true);
        var foot = new VBox(6, said, legend);
        foot.getStyleClass().add("settings-foot");
        var right = new BorderPane(scroll);
        right.setBottom(foot);
        var root = new BorderPane(right);
        root.setLeft(sideBox);
        root.getStyleClass().add("settings-root");
        var scene = new Scene(root, 980, 660);
        scene.getStylesheets().add(SettingsWindow.class.getResource("/chatsgate/whatsappfx/ui/chat/chat.css").toExternalForm());
        scene.getStylesheets().add(SettingsWindow.class.getResource("/chatsgate/whatsappfx/ui/settings.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
        scene.addEventFilter(KeyEvent.ANY, e -> Activity.touch());
        stage.setScene(scene);
        Look.dress(hub, stage, root, () -> "Settings - " + hub.title(), true);
        stage.setOnHidden(e -> { OPEN.remove(hub, this); App.trimWhenHidden(); });
        buildSide();
        new Refresher(stage, this::refresh);
    }

    void key(KeyEvent e) {
        var c = e.getCode();
        if (c == KeyCode.ESCAPE) { stage.hide(); e.consume(); return; }
        if (c == KeyCode.DOWN || c == KeyCode.UP) {
            var all = Section.values();
            var i = Math.max(0, Math.min(all.length - 1, current.ordinal() + (c == KeyCode.DOWN ? 1 : -1)));
            choose(all[i]);
            e.consume();
            return;
        }
        for (var a : acts) if (a.key() == c) { a.run().run(); e.consume(); return; }
    }

    void choose(Section s) {
        current = s;
        said.setText("");
        buildSide();
        refresh();
    }

    void buildSide() {
        side.getChildren().clear();
        for (var s : Section.values()) {
            var t = new Label(s.title);
            t.getStyleClass().add("side-title");
            var d = new Label(s.sub);
            d.getStyleClass().add("side-sub");
            var item = new VBox(2, t, d);
            item.getStyleClass().add("side-item");
            if (s == current) item.getStyleClass().add("selected");
            item.setOnMouseClicked(e -> choose(s));
            side.getChildren().add(item);
        }
    }

    /** Rebuilds the right-hand side from the app's state; every two seconds while the window shows. */
    void refresh() {
        var s = hub.status();
        status = StatusText.of(s);
        var rows = new ArrayList<Node>();
        var list = new ArrayList<Act>();
        rows.add(header(current.title));
        switch (current) {
            case LINKED -> linked(s, rows, list);
            case NUMBER -> NumberSection.build(this, rows, list);
            case PROGRAMS -> programs(s, rows, list);
            case ATTACHMENTS -> attachments(s, rows, list);
            case GENERAL -> general(s, rows, list);
            case ABOUT -> about(rows, list);
        }
        acts = List.copyOf(list);
        content.getChildren().setAll(rows);
        var keys = new FKeys().dark();
        for (var a : acts) keys.bind(a.key(), a.label(), "#aab4ba", a.run());
        keys.bind(KeyCode.ESCAPE, "Close", "#aab4ba", stage::hide);
        legend.getChildren().setAll(keys.bar);
    }

    void linked(Map<String, Object> s, List<Node> rows, List<Act> acts) {
        var linked = Boolean.TRUE.equals(s.get("loggedIn"));
        rows.add(row(linked ? String.valueOf(s.get("pushName")) : "Not linked",
                linked ? "+" + digits(s.get("jid")) : s.get("logoutReason") == null ? "Link this PC to your WhatsApp to get your chats here" : "WhatsApp ended the link: " + s.get("logoutReason"), null, !linked));
        rows.add(row("Connection", s.get("connectedSince") == null ? "offline" : "online for " + StatusText.ago((Long) s.get("connectedSince")), null, s.get("connectedSince") == null));
        var quiet = s.get("secondsSinceLastEvent") instanceof Long l ? l : null;
        rows.add(row("Last heard", quiet == null ? "never" : StatusText.human(quiet) + " ago" + StatusText.warning(quiet), null, quiet != null && quiet > 5 * 86400));
        var restarts = num(s, "restarts");
        var alive = Boolean.TRUE.equals(s.get("connectorAlive"));
        rows.add(row("Connector", alive ? "running" + (restarts > 0 ? ", restarted " + restarts + " times" : "") : "DOWN", null, !alive));
        if (s.get("banReason") != null) rows.add(row("Banned", String.valueOf(s.get("banReason")), null, true));
        if (!linked) acts.add(new Act(KeyCode.F4, "Link this PC", () -> LinkWindow.show(hub)));
        else acts.add(new Act(KeyCode.F10, "Unlink this PC", this::forget));
        rows.add(row(linked ? "Unlink this PC" : "Link this PC", linked ? "Ends the link; only the phone can link this PC again" : "Open the link window: a QR code, or a phone number", null, linked));
    }

    void programs(Map<String, Object> s, List<Node> rows, List<Act> acts) {
        var n = num(s, "approvals");
        rows.add(row("Approved now", n == 0 ? "None. A program using whatsappcli is asked about, and you approve it with F2 in a green window"
                : n + " program" + (n == 1 ? "" : "s") + ". An approval ends when that program closes or at its time", null, false));
        rows.add(row("Command-line access", hub.walletOpen() ? "WALLET OPEN: every request passes with no window until " + java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(hub.walletOpenUntilMillis()), java.time.ZoneId.systemDefault()).withNano(0)
                : "Each program asks you. Wallet open skips the window for up to four hours.", null, hub.walletOpen()));
        rows.add(row("Access log", chatsgate.whatsappfx.wire.Paths2.home().resolve("run").resolve("access.log").toString(), null, false));
        acts.add(new Act(KeyCode.F5, "Approved programs", () -> new AccessListWindow<>(hub.callers.engine, App.NO_PASSPHRASE, App.BRAND).show()));
        acts.add(new Act(KeyCode.F6, "Revoke all", () -> { hub.callers.revokeAll(); say("Every program approval revoked: the next use of whatsappcli asks again."); refresh(); }));
        acts.add(new Act(KeyCode.F2, "Wallet open…", () -> CliAccessWindow.show(hub)));
    }

    void attachments(Map<String, Object> s, List<Node> rows, List<Act> acts) {
        rows.add(row("Filed", num(s, "mediaFiled") + "  (queued " + num(s, "mediaQueued") + ")", null, false));
        rows.add(row("Not fetched", num(s, "mediaNotFetched") + "  (off per chat until whatsappcli policy turns them on)", null, false));
        rows.add(row("Lost", num(s, "mediaExpired") + " expired, " + num(s, "mediaFailed") + " failed, " + num(s, "mediaTooLarge") + " too large", null, false));
        rows.add(row("Folder", hub.config.mediaRoot() + (hub.config.mediaFolder().isBlank() ? "  (default)" : ""), null, false));
        acts.add(new Act(KeyCode.F9, "Change folder", this::chooseMediaFolder));
    }

    void general(Map<String, Object> s, List<Node> rows, List<Act> acts) {
        var auto = Boolean.TRUE.equals(s.get("autostart"));
        rows.add(row("Start at logon", auto ? "On, in the tray" : "Off: a PC offline for about 14 days is unlinked by WhatsApp", toggle(auto, this::toggleAutostart), !auto));
        rows.add(row("Sound for new messages", "A short chime when a message arrives", toggle(Sound.on(), () -> { Sound.on(!Sound.on()); refresh(); }), false));
        rows.add(row("Ring for incoming calls", "Rings until you accept or reject", toggle(Sound.ringOn(), () -> { Sound.ringOn(!Sound.ringOn()); refresh(); }), false));
        acts.add(new Act(KeyCode.F7, "Start at logon on/off", this::toggleAutostart));
        acts.add(new Act(KeyCode.F3, "Sound on/off", () -> { Sound.on(!Sound.on()); refresh(); }));
        rows.add(row("Add a number", "Another phone number in its own window and colour (up to " + chatsgate.whatsappfx.daemon.Accounts.MAX + "). Press F6: its window opens with a QR to scan from that phone", null, false));
        acts.add(new Act(KeyCode.F6, "Add a number", this::addNumber));
    }

    /** A new number, named and coloured by default; its window opens unlinked with the QR (App opens it when the number is added). */
    void addNumber() {
        try {
            hub.app.allow("add a number");
            var h = hub.accounts.add(null, null);
            say("Added \"" + h.title() + "\": its window is open, scan the QR from that phone.");
        } catch (Exception e) {
            say("Could not add a number: " + e.getMessage());
        }
    }

    void about(List<Node> rows, List<Act> acts) {
        rows.add(row("WhatsappFx " + chatsgate.whatsappfx.wire.Version.current(), "An opensource alternative to WhatsApp. It is an unofficial client and is not affiliated with WhatsApp.", null, false));
        rows.add(row("Data", chatsgate.whatsappfx.wire.Paths2.home().toString(), null, false));
        rows.add(row("Licences", "The app is Apache-2.0; the connector is GPL-3.0 because it links the Signal protocol library.", null, false));
        acts.add(new Act(KeyCode.F8, "Copy state", this::copy));
    }

    /**
     * Ends the link on purpose, after a question that names this window's number, its colour and its title (see {@link UnlinkConfirm}):
     * only the phone can link this PC again, and that costs a full history sync. When WhatsApp ends the link itself, the connector
     * rebuilds a fresh device without asking.
     */
    void forget() {
        UnlinkConfirm.ask(hub, stage, this::unlink);
    }

    private void unlink() {
        hub.app.allow("unlink this PC");
        say("Unlinking …");
        Thread.ofVirtual().start(() -> {
            try {
                var reply = hub.connector.call("logout", Map.of(), 60);
                Platform.runLater(() -> {
                    if (reply.get("ok").getAsBoolean()) { say("This PC is unlinked."); LinkWindow.show(hub); }
                    else say("Unlink refused: " + reply.get("error").getAsString());
                    refresh();
                });
            } catch (Exception e) {
                Log.error("logout failed", e);
                Platform.runLater(() -> say("Unlink failed: " + e.getMessage()));
            }
        });
    }

    void toggleAutostart() {
        try {
            var on = Boolean.TRUE.equals(hub.status().get("autostart"));
            var after = on ? Autostart.disable() : Autostart.enable();
            say(Boolean.TRUE.equals(after.get("enabled")) ? "WhatsappFx now starts at logon, in the tray."
                    : "Start at logon is off. Run it at least every fortnight, or WhatsApp unlinks this PC.");
        } catch (Exception e) {
            say("Start at logon unchanged: " + e.getMessage());
        }
        refresh();
    }

    /** Saved to whatsappfx_settings.toml at once; the next attachment filed goes there. Files already filed stay where they are. */
    void chooseMediaFolder() {
        var chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Folder for WhatsApp attachments");
        var now = hub.config.mediaRoot().toFile();
        if (now.isDirectory()) chooser.setInitialDirectory(now);
        else if (now.getParentFile() != null && now.getParentFile().isDirectory()) chooser.setInitialDirectory(now.getParentFile());
        var picked = chooser.showDialog(stage);
        if (picked == null) { say("Attachments folder unchanged."); return; }
        hub.config.mediaFolder(picked.getAbsolutePath());
        say("Attachments now go to " + picked.getAbsolutePath() + ". Files already filed stay where they are.");
        refresh();
    }

    void copy() {
        var c = new ClipboardContent();
        c.putString(status);
        Clipboard.getSystemClipboard().setContent(c);
        say("State copied.");
    }

    void say(String text) {
        said.setText(text);
    }

    static Node header(String text) {
        var l = new Label(text);
        l.getStyleClass().add("content-title");
        return l;
    }

    /** A title with a grey line under it, and something at the end if there is one; {@code warn} paints the line amber. */
    static Node row(String title, String sub, Node trailing, boolean warn) {
        var t = new Label(title);
        t.getStyleClass().add("row-title");
        var d = new Label(sub);
        d.getStyleClass().add(warn ? "row-sub-warn" : "row-sub");
        d.setWrapText(true);
        var text = new VBox(2, t, d);
        HBox.setHgrow(text, Priority.ALWAYS);
        var box = new HBox(12, text);
        box.getStyleClass().add("row");
        box.setAlignment(Pos.CENTER_LEFT);
        if (trailing != null) box.getChildren().add(trailing);
        return box;
    }

    /** WhatsApp's switch: a rounded track, green when on, with a round knob. */
    static Node toggle(boolean on, Runnable flip) {
        var knob = new Circle(9);
        knob.getStyleClass().add("knob");
        var track = new StackPane(knob);
        track.getStyleClass().addAll("switch", on ? "on" : "off");
        track.setAlignment(on ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        track.setPadding(new Insets(0, 3, 0, 3));
        track.setMinSize(46, 24);
        track.setMaxSize(46, 24);
        track.setOnMouseClicked(e -> flip.run());
        return track;
    }

    static String digits(Object jid) {
        return jid == null ? "" : String.valueOf(jid).split("[:@]")[0];
    }

    static long num(Map<String, Object> s, String k) {
        return s.get(k) instanceof Number n ? n.longValue() : 0;
    }
}
