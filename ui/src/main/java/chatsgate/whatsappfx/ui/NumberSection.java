package chatsgate.whatsappfx.ui;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Accounts;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.daemon.Palette;

import module java.base;

/**
 * The settings section of one number: what the person calls it, the colour of its windows (one of eight, never one that another
 * number wears), adding another number and removing this one. The choosing is what fxterm-tabs offers for its windows: a name, a
 * colour menu with a swatch each, and the other windows' colours marked.
 */
final class NumberSection {

    private NumberSection() {}

    static void build(SettingsWindow w, List<Node> rows, List<SettingsWindow.Act> acts) {
        var hub = w.hub;
        rows.add(SettingsWindow.row("Name", hub.title(), null, false));
        var same = hub.accounts.duplicateOf(hub);
        if (same != null) rows.add(SettingsWindow.row("Same phone number as " + same.title(), "Both windows are linked to +" + hub.ownUser() + ". That is only sensible for a test: on the command line the two can only be told apart with --account " + hub.account + " or --account " + same.account + ". Unlink one of them (the phone's Linked devices) and remove it here when the test is done.", null, true));
        rows.add(SettingsWindow.row("Colour", Palette.nameOf(hub.colour()) + ". Its window, its notices and its taskbar icon wear it; no other number can wear the same.", swatches(w), false));
        acts.add(new SettingsWindow.Act(KeyCode.F2, "Rename", () -> rename(w)));
        rows.add(SettingsWindow.row("Short name", hub.shortName() + ". What 'whatsappcli --account' calls this number (so does its phone number).", null, false));
        acts.add(new SettingsWindow.Act(KeyCode.F5, "Short name", () -> shortName(w)));
        acts.add(new SettingsWindow.Act(KeyCode.F3, "Colour", () -> colours(w).show(w.stage, w.stage.getX() + 340, w.stage.getY() + 230)));
        var others = hub.accounts.all().stream().filter(h -> h != hub).toList();
        for (var h : others) rows.add(SettingsWindow.row(h.title(), Palette.nameOf(h.colour()) + "  ·  " + state(h), Look.swatch(h.colour(), 16), false));
        acts.add(new SettingsWindow.Act(KeyCode.F4, "Add another number", () -> add(w)));
        rows.add(SettingsWindow.row("Add another number", "Another phone number gets its own window, name and colour, and is linked from that phone (Link a device).", null, false));
        if (others.isEmpty()) return;
        acts.add(new SettingsWindow.Act(KeyCode.F10, "Remove this number", () -> remove(w)));
        rows.add(SettingsWindow.row("Remove this number", "Takes it off the list and stops it. Its messages and files stay on disk; it can be added back with the same key.", null, false));
    }

    static String state(Hub h) {
        var s = h.status();
        if (!Boolean.TRUE.equals(s.get("loggedIn"))) return "not linked";
        return "+" + h.ownUser() + (s.get("connectedSince") == null ? ", offline" : ", online");
    }

    /** The eight colours as swatches: the one in use ringed, those another number wears dimmed and named. */
    static Node swatches(SettingsWindow w) {
        var box = new HBox(8);
        for (var i = 0; i < Palette.COLOURS.size(); i++) {
            var hex = Palette.COLOURS.get(i);
            var dot = new Circle(11, Color.web(hex));
            var other = w.hub.accounts.all().stream().filter(h -> h != w.hub && hex.equals(h.colour())).findFirst().orElse(null);
            if (hex.equals(w.hub.colour())) { dot.setStroke(Color.web("#fafafa")); dot.setStrokeWidth(2.5); }
            if (other != null) dot.setOpacity(0.25);
            Tooltip.install(dot, new Tooltip(Palette.NAMES.get(i) + (other != null ? "  (" + other.title() + ")" : hex.equals(w.hub.colour()) ? "  (this one)" : "")));
            dot.setStyle("-fx-cursor: hand;");
            dot.setOnMouseClicked(e -> choose(w, hex));
            box.getChildren().add(dot);
        }
        return box;
    }

    static ContextMenu colours(SettingsWindow w) {
        var menu = new ContextMenu();
        for (var i = 0; i < Palette.COLOURS.size(); i++) {
            var hex = Palette.COLOURS.get(i);
            var other = w.hub.accounts.all().stream().filter(h -> h != w.hub && hex.equals(h.colour())).findFirst().orElse(null);
            var item = new MenuItem(Palette.NAMES.get(i) + (hex.equals(w.hub.colour()) ? "   ✓" : other != null ? "   (" + other.title() + ")" : ""), Look.swatch(hex, 14));
            item.setDisable(other != null);
            item.setOnAction(e -> choose(w, hex));
            menu.getItems().add(item);
        }
        return menu;
    }

    static void choose(SettingsWindow w, String hex) {
        try {
            w.hub.accounts.update(w.hub, null, hex, null);
            w.say("Colour: " + Palette.nameOf(hex) + ".");
        } catch (RuntimeException e) {
            w.say(e.getMessage());
        }
        w.refresh();
    }

    static void rename(SettingsWindow w) {
        var d = new TextInputDialog(w.hub.title());
        d.initOwner(w.stage);
        d.setTitle("Name this number");
        d.setHeaderText("What you call this number: shown in its window, in the tray and on its notices");
        d.showAndWait().map(String::strip).filter(n -> !n.isBlank()).ifPresent(n -> {
            try {
                w.hub.accounts.update(w.hub, n, null, null);
                w.say("Named " + n + ".");
            } catch (RuntimeException e) {
                w.say(e.getMessage());
            }
            w.refresh();
        });
    }

    static void shortName(SettingsWindow w) {
        var d = new TextInputDialog(w.hub.shortName());
        d.initOwner(w.stage);
        d.setTitle("Short name");
        d.setHeaderText("What the command line calls this number (whatsappcli --account <this>): 1 to 16 lower-case letters, digits or dashes");
        d.showAndWait().map(String::strip).filter(n -> !n.isBlank()).ifPresent(n -> {
            try {
                w.hub.accounts.update(w.hub, null, null, n);
                w.say("Short name: " + n + ".");
            } catch (RuntimeException e) {
                w.say(e.getMessage());
            }
            w.refresh();
        });
    }

    static void add(SettingsWindow w) {
        if (w.hub.accounts.count() >= Accounts.MAX) { w.say("At most " + Accounts.MAX + " numbers."); return; }
        var d = new TextInputDialog("");
        d.initOwner(w.stage);
        d.setTitle("Add another number");
        d.setHeaderText("What do you call it? (for instance Work). It opens in its own window, to be linked from that phone.");
        d.showAndWait().map(String::strip).filter(n -> !n.isBlank()).ifPresent(n -> {
            w.say("Adding " + n + " …");
            Thread.ofVirtual().start(() -> {
                try {
                    w.hub.accounts.add(n, null);
                    javafx.application.Platform.runLater(() -> { w.say("Added " + n + ": its window is open."); w.refresh(); });
                } catch (Exception e) {
                    Log.warn("number not added: " + e.getMessage());
                    javafx.application.Platform.runLater(() -> w.say("Not added: " + e.getMessage()));
                }
            });
        });
    }

    /** F10 twice within five seconds, as unlinking is. */
    static void remove(SettingsWindow w) {
        var now = System.currentTimeMillis();
        if (now - w.removeAsked > 5000) {
            w.removeAsked = now;
            w.say("Press F10 again within five seconds to remove " + w.hub.title() + " from the list. Its messages and files stay on disk.");
            return;
        }
        w.removeAsked = 0;
        var hub = w.hub;
        w.stage.hide();
        hub.accounts.remove(hub);
    }
}
