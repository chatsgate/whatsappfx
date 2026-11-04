package chatsgate.whatsappfx.ui.notify;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import chatsgate.whatsappfx.core.Activity;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * WhatsApp-style notification cards, bottom right above the taskbar: newest on top, at most {@link #MAX}. They live in
 * ONE window, made the first time a card is needed and never hidden afterwards (it moves off screen instead), because
 * showing a JavaFX window activates it and would take the keyboard from whatever the person is typing in. With
 * WS_EX_NOACTIVATE it appears without that; a click on a card hands it the keyboard, so the reply box can be typed in.
 * Everything here runs on the FX thread.
 */
public final class Cards {

    static final String TITLE = "WhatsappFx notification";
    static final int MAX = 3;
    static final double WIDTH = 380, MARGIN = 14, OFF = -32000;

    private static Stage owner, stage;
    private static VBox stack;
    private static MemorySegment hwnd;
    private static boolean active;
    private static final List<Card> cards = new ArrayList<>();

    private Cards() {}

    static void add(Card c) {
        ensure();
        cards.addFirst(c);
        stack.getChildren().addFirst(c.node);
        while (cards.size() > MAX) {
            // the oldest one that is not a call still ringing goes first
            var drop = cards.reversed().stream().filter(x -> !x.important()).findFirst().orElse(cards.getLast());
            remove(drop);
        }
        place();
    }

    static void remove(Card c) {
        if (!cards.remove(c)) return;
        c.closed();
        stack.getChildren().remove(c.node);
        if (cards.isEmpty()) park();
        else place();
    }

    /** The card for this key (a chat or a call id), if one is showing. */
    static Card find(String key) {
        return cards.stream().filter(c -> key.equals(c.key)).findFirst().orElse(null);
    }

    /** A card was clicked or asked for the keyboard: take it, so its box can be typed in. */
    static void activate() {
        if (stage == null) return;
        if (!active) {
            active = true;
            Win.noActivate(hwnd, false);
            Win.grab(hwnd);
        }
        stage.requestFocus();
    }

    static void place() {
        if (hwnd == null) { hwnd = Win.find(TITLE); if (!active) Win.noActivate(hwnd, true); }
        stack.applyCss();
        stack.layout();
        stage.sizeToScene();
        var area = Screen.getPrimary().getVisualBounds();
        stage.setX(area.getMaxX() - stage.getWidth() - MARGIN);
        stage.setY(area.getMaxY() - stage.getHeight() - MARGIN);
        stage.setOpacity(1);
    }

    private static void park() {
        stage.setOpacity(0);
        stage.setX(OFF);
        stage.setY(OFF);
        deactivate();
    }

    private static void deactivate() {
        if (!active) return;
        active = false;
        Win.noActivate(hwnd, true);
    }

    /**
     * Made once. The two windows are shown at once while invisible and off screen; the owner (a utility window) keeps the
     * cards off the taskbar and out of Alt+Tab, and whatever had the keyboard gets it straight back.
     */
    private static void ensure() {
        if (stage != null) return;
        var before = Win.foreground();
        owner = new Stage(StageStyle.UTILITY);
        owner.setOpacity(0);
        owner.setWidth(1);
        owner.setHeight(1);
        owner.setX(OFF);
        owner.setY(OFF);
        owner.show();
        stage = new Stage(StageStyle.UNDECORATED);
        stage.initOwner(owner);
        stage.setAlwaysOnTop(true);
        stage.setTitle(TITLE);
        stack = new VBox(3);
        stack.setPadding(new Insets(2));
        stack.setPrefWidth(WIDTH + 4);
        stack.setStyle("-fx-background-color: #111212;");
        var scene = new Scene(stack); // no explicit size: sizeToScene must follow the cards
        scene.setFill(Color.web("#111212"));
        scene.getStylesheets().add(Cards.class.getResource("cards.css").toExternalForm());
        // Only a click into a text box takes the keyboard. Taking it on every press changed the window's activation in the
        // middle of the click, and JavaFX then dropped the click: the close button and the chips did nothing (2026-10-27).
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            var box = textBox(e.getTarget());
            if (box != null) { activate(); javafx.application.Platform.runLater(box::requestFocus); }
        });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, Cards::key);
        scene.addEventFilter(javafx.event.Event.ANY, e -> Activity.touch());
        stage.setScene(scene);
        stage.setOpacity(0);
        stage.setX(OFF);
        stage.setY(OFF);
        stage.show();
        hwnd = Win.find(TITLE);
        Win.noActivate(hwnd, true);
        stage.focusedProperty().addListener((o, was, now) -> { if (!now) deactivate(); });
        Win.restore(before);
    }

    static javafx.scene.control.TextInputControl textBox(Object target) {
        for (var n = target instanceof javafx.scene.Node x ? x : null; n != null; n = n.getParent())
            if (n instanceof javafx.scene.control.TextInputControl t) return t;
        return null;
    }

    /** Keys go to the top card: Esc closes it, the rest are its own F-keys. */
    private static void key(KeyEvent e) {
        if (cards.isEmpty()) return;
        var top = cards.stream().filter(Card::hasFocus).findFirst().orElse(cards.getFirst());
        if (e.getCode() == KeyCode.ESCAPE) { top.dismiss(); e.consume(); return; }
        if (top.key(e.getCode())) e.consume();
    }
}
