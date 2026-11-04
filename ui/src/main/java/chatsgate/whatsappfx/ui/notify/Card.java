package chatsgate.whatsappfx.ui.notify;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.ui.AccountBrand;
import chatsgate.whatsappfx.ui.App;
import chatsgate.whatsappfx.ui.Look;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One notification card: the app's mark, a title, the text, its actions as chips wearing their F-key, a reply box. A card
 * closes itself after a while unless the person is using it.
 */
abstract class Card {

    final Hub hub;
    final String key, chat;
    final VBox node = new VBox(6);
    final Label title = new Label(), text = new Label(), status = new Label();
    final TextField reply = new TextField();
    final FlowPane chips = new FlowPane(6, 6);
    final Map<KeyCode, Runnable> actions = new LinkedHashMap<>();
    final PauseTransition timer;
    boolean sending;

    Card(Hub hub, String key, String chat, double seconds) {
        this.hub = hub;
        this.key = key;
        this.chat = chat;
        node.getStyleClass().add("card");
        var mark = new ImageView(AccountBrand.of(hub).icons().header());
        mark.setFitWidth(16);
        mark.setFitHeight(16);
        // The card is in its number's colour: a deep shade of it behind, the colour itself on the left edge. The name says which number.
        var several = hub.accounts.count() > 1;
        var app = new Label(several ? "WhatsappFx  ·  " + hub.title() : "WhatsappFx");
        app.getStyleClass().add("app");
        var colour = javafx.scene.paint.Color.web(hub.colour());
        node.setStyle("-fx-background-color: " + Look.hex(Look.mix(javafx.scene.paint.Color.web("#161717"), colour, 0.30)) + "; -fx-border-color: " + hub.colour() + "; -fx-border-width: 0 0 0 6;");
        app.setStyle("-fx-text-fill: " + Look.hex(Look.mix(colour, javafx.scene.paint.Color.WHITE, 0.55)) + ";");
        var gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        var close = new Button("✕");
        close.getStyleClass().add("close");
        close.setFocusTraversable(false);
        close.setOnAction(e -> dismiss());
        var head = new HBox(6, mark, app, gap, close);
        head.setAlignment(Pos.CENTER_LEFT);
        title.getStyleClass().add("title");
        text.getStyleClass().add("text");
        text.setWrapText(true);
        text.setMaxHeight(64);
        status.getStyleClass().add("status");
        status.setWrapText(true);
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());
        reply.setPromptText("Type a reply, Enter sends");
        reply.getStyleClass().add("reply");
        reply.setOnAction(e -> send(reply.getText()));
        node.getChildren().addAll(head, title, text, chips, reply, status);
        timer = new PauseTransition(Duration.seconds(seconds));
        timer.setOnFinished(e -> { if (!busy()) dismiss(); else timer.playFromStart(); });
        node.hoverProperty().addListener((o, was, now) -> { if (!now) timer.playFromStart(); });
        if (seconds > 0) timer.play();
    }

    /** A chip that runs {@code action} on click or on its F-key. */
    void action(KeyCode k, String label, String style, Runnable action) {
        actions.put(k, action);
        var b = new Button(k.getName() + "  " + label);
        b.getStyleClass().addAll("chip", style);
        b.setFocusTraversable(false);
        b.setMnemonicParsing(false);
        b.setOnAction(e -> action.run());
        chips.getChildren().add(b);
    }

    boolean key(KeyCode k) {
        var a = actions.get(k);
        if (a == null) return false;
        a.run();
        return true;
    }

    /** In use: pointed at, typed in, or a reply on its way. Such a card does not close itself. */
    boolean busy() {
        return node.isHover() || hasFocus() || sending || !reply.getText().isBlank();
    }

    boolean hasFocus() {
        return reply.isFocused();
    }

    /** A card that must not be pushed out by newer ones. */
    boolean important() {
        return false;
    }

    void dismiss() {
        Cards.remove(this);
    }

    /** Called once when it leaves the screen. */
    void closed() {
        timer.stop();
    }

    /** Sends {@code text} to the chat. */
    void send(String text) {
        if (text == null || text.isBlank() || chat == null || sending) return;
        sending = true;
        reply.setDisable(true);
        status.setText("Sending…");
        Thread.ofVirtual().start(() -> {
            String err = null;
            try {
                hub.app.allow("send");
                var r = hub.connector.call("send.text", Map.of("chat", chat, "text", text), 120);
                if (!r.get("ok").getAsBoolean()) err = r.get("error").getAsString();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            var failed = err;
            if (failed != null) Log.warn("reply from a notification not sent: " + failed);
            Platform.runLater(() -> {
                sending = false;
                reply.setDisable(false);
                if (failed != null) { status.setText("NOT sent: " + failed); reply.setText(text); return; }
                reply.clear();
                status.setText("Sent: " + text);
                sent(text);
            });
        });
    }

    /** After a reply went out: by default the card closes a moment later. */
    void sent(String text) {
        var t = new PauseTransition(Duration.seconds(2));
        t.setOnFinished(e -> dismiss());
        t.play();
    }
}
