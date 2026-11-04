package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.daemon.Palette;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * The question before a number is unlinked. Unlinking ends the link on the phone too and only the phone can undo it, and with
 * several numbers it is easy to press it in the wrong window, so the question names the number, its colour and its window title,
 * and the focus starts on Cancel: Enter at once, or Esc, changes nothing. To unlink, move to "Unlink" (Tab or an arrow) and press
 * Enter. It acts only on the number of the window it was asked from, and asking again while it is open does nothing.
 */
final class UnlinkConfirm {

    private static final java.util.Set<Hub> OPEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private UnlinkConfirm() {}

    /** Asks about {@code hub}; {@code yes} runs only on a confirmed unlink. A second ask for the same number while one is open is ignored. */
    static void ask(Hub hub, Stage owner, Runnable yes) {
        if (!OPEN.add(hub)) return;
        var stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setResizable(false);
        stage.setOnHidden(e -> OPEN.remove(hub));

        var accent = Look.hex(javafx.scene.paint.Color.web(hub.colour()));
        var number = hub.ownUser() == null ? "(not linked)" : "+" + hub.ownUser();
        var swatch = Look.swatch(hub.colour(), 22);
        var head = new Label("Unlink this number?");
        head.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;");
        var top = new HBox(10, swatch, head);
        top.setAlignment(Pos.CENTER_LEFT);

        var facts = new VBox(4,
                line("Number", number),
                line("Window", hub.title()),
                line("Colour", Palette.nameOf(hub.colour())));
        var warn = new Label("This also removes the link on the phone. Only the phone can link it again, and that costs a full history sync.");
        warn.setWrapText(true);
        warn.setStyle("-fx-text-fill: #d8d9d9; -fx-font-size: 13px;");

        var cancel = new Button("Cancel");
        var unlink = new Button("Unlink");
        for (var b : new Button[]{cancel, unlink}) b.setStyle("-fx-font-size: 14px; -fx-padding: 6 22 6 22; -fx-cursor: hand;");
        cancel.setStyle(cancel.getStyle() + " -fx-background-color: " + accent + "; -fx-text-fill: " + Look.hex(Look.textOn(javafx.scene.paint.Color.web(hub.colour()))) + ";");
        unlink.setStyle(unlink.getStyle() + " -fx-background-color: #5a2424; -fx-text-fill: white;");
        var hint = new Label("Focus is on Cancel. Tab or an arrow moves to Unlink, Enter chooses, Esc cancels.");
        hint.setStyle("-fx-text-fill: #8a8b8b; -fx-font-size: 12px;");
        var spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        for (var b : new Button[]{cancel, unlink}) b.setMinWidth(Region.USE_PREF_SIZE);
        var buttons = new HBox(10, spacer, cancel, unlink);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        var box = new VBox(14, top, facts, warn, hint, buttons);
        box.setPadding(new Insets(20, 24, 18, 24));
        box.setStyle("-fx-background-color: #161717;");
        box.setPrefWidth(620);
        // Look.dress sets the style of the root it is given, so the background sits one level in
        var root = new javafx.scene.layout.StackPane(box);

        var scene = new Scene(root, javafx.scene.paint.Color.web("#161717"));
        stage.setScene(scene);
        cancel.setOnAction(e -> stage.close());
        unlink.setOnAction(e -> { stage.close(); yes.run(); });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            var c = e.getCode();
            if (c == KeyCode.ESCAPE) { stage.close(); e.consume(); }
            else if (c == KeyCode.ENTER) { (unlink.isFocused() ? unlink : cancel).fire(); e.consume(); }
            else if (c == KeyCode.LEFT) { cancel.requestFocus(); e.consume(); }
            else if (c == KeyCode.RIGHT) { unlink.requestFocus(); e.consume(); }
            // an F-key meant for the window underneath (F10 again) does nothing here
            else if (c.isFunctionKey()) e.consume();
        });
        Look.dress(hub, stage, root, () -> "Unlink " + hub.title(), true);
        stage.show();
        cancel.requestFocus();
    }

    private static HBox line(String name, String value) {
        var n = new Label(name);
        n.setMinWidth(70);
        n.setStyle("-fx-text-fill: #8a8b8b; -fx-font-size: 14px;");
        var v = new Label(value);
        v.setStyle("-fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold;");
        return new HBox(8, n, v);
    }
}
