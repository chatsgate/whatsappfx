package chatsgate.whatsappfx.ui.link;

import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.ui.AccountBrand;
import chatsgate.whatsappfx.ui.App;
import chatsgate.whatsappfx.ui.Look;
import chatsgate.whatsappfx.ui.Qr;
import chatsgate.whatsappfx.ui.chat.ChatWindow;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import leasegate.frame.FKeys;
import leasegate.frame.TaskbarTheme;

import module java.base;

/**
 * Links this PC to WhatsApp and nothing else: a QR to scan, or a phone number to get a code for. It opens by itself when
 * the PC is not linked and closes by itself, on the chats, once it is. Laid out like WhatsApp's own login screen.
 */
public final class LinkWindow {

    /** One link window per number. */
    private static final Map<Hub, LinkWindow> OPEN = new ConcurrentHashMap<>();

    final Hub hub;
    final Stage stage = new Stage();
    final Label title = new Label(), said = new Label(), altLink = new Label();
    final VBox left = new VBox(24), right = new VBox(16);
    final ImageView qr = new ImageView();
    final StackPane qrBox = new StackPane();
    final TextField phone = new TextField();
    final Label code = new Label();
    final Button getCode = new Button("Get code   Enter");
    boolean byPhone;
    String shownQr, shownCode;
    long qrAskedAt;

    /** FX thread. Opens the window, or brings it forward. */
    public static void show(Hub hub) {
        var w = OPEN.computeIfAbsent(hub, LinkWindow::new);
        w.stage.show();
        w.stage.setIconified(false);
        w.stage.toFront();
        w.stage.requestFocus();
    }

    public static boolean isOpen() {
        return !OPEN.isEmpty();
    }

    private LinkWindow(Hub hub) {
        this.hub = hub;
        title.getStyleClass().add("link-title");
        said.getStyleClass().add("said");
        code.getStyleClass().add("code-text");
        phone.getStyleClass().add("phone-field");
        phone.setPromptText("Phone number, with country code");
        phone.setPrefWidth(340);
        phone.setMinWidth(340);
        getCode.getStyleClass().add("go-button");
        getCode.setFocusTraversable(false);
        getCode.setOnAction(e -> getCode());
        phone.setOnAction(e -> getCode());
        phone.textProperty().addListener((o, was, now) -> { if (!now.matches("[0-9]*")) phone.setText(now.replaceAll("[^0-9]", "")); });
        qr.setFitWidth(206);
        qr.setFitHeight(206);
        qrBox.getStyleClass().add("qr-box");
        qrBox.getChildren().add(new ProgressIndicator());
        altLink.getStyleClass().add("alt-link");
        altLink.setOnMouseClicked(e -> toggle());

        var brandName = label("WhatsappFx", "brand-name");
        var logo = icon(hub);
        var brand = new HBox(10, logo, brandName);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(22, 40, 0, 40));
        var card = new HBox(60, left, right);
        card.getStyleClass().add("link-card");
        HBox.setHgrow(left, Priority.ALWAYS);
        var bottom = new HBox(new Region(), altLink);
        HBox.setHgrow(bottom.getChildren().get(0), Priority.ALWAYS);
        var cardBox = new VBox(26, card, bottom);
        cardBox.setMaxWidth(880);
        cardBox.setMaxHeight(Region.USE_PREF_SIZE);
        var center = new StackPane(cardBox);
        StackPane.setAlignment(cardBox, Pos.CENTER);
        center.setPadding(new Insets(10, 40, 0, 40));
        var notice = label("WhatsappFx is an opensource alternative to WhatsApp. It is an unofficial client and is not affiliated with WhatsApp.", "notice");
        var keys = new FKeys()
                .bind(KeyCode.F2, "Phone number / QR", "#555", this::toggle)
                .bind(KeyCode.F4, "New QR", "#555", this::newQr)
                .bind(KeyCode.ESCAPE, "Close", "#555", stage::hide);
        var legend = new HBox(keys.bar);
        legend.setPadding(new Insets(12, 40, 16, 40));
        var noticeRow = new HBox(notice);
        noticeRow.setPadding(new Insets(8, 40, 0, 40));
        var root = new VBox(brand, center, noticeRow, legend);
        root.getStyleClass().add("link-root");
        VBox.setVgrow(center, Priority.ALWAYS);
        var scene = new Scene(root, 980, 660);
        scene.getStylesheets().add(LinkWindow.class.getResource("/chatsgate/whatsappfx/ui/link/link.css").toExternalForm());
        keys.install(scene);
        stage.setScene(scene);
        Look.dress(hub, stage, root, () -> "Link " + hub.title() + " to WhatsApp", false,
                () -> { logo.setImage(AccountBrand.of(hub).icon()); brandName.setText(hub.accounts.count() > 1 ? "WhatsappFx  ·  " + hub.title() : "WhatsappFx"); root.setStyle(root.getStyle() + " -link-accent: " + Look.hex(javafx.scene.paint.Color.web(hub.colour())) + "; -link-ink: " + Look.hex(javafx.scene.paint.Color.web(hub.colour()).deriveColor(0, 1, 0.75, 1)) + ";"); });
        render();
        stage.showingProperty().addListener((o, was, now) -> { if (now) tick(); });
        var ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("wa-link-refresh").factory());
        ticker.scheduleWithFixedDelay(() -> Platform.runLater(this::tick), 1, 2, TimeUnit.SECONDS);
        stage.setOnHidden(e -> { OPEN.remove(hub, this); ticker.shutdownNow(); App.trimWhenHidden(); });
    }

    /** The mark in this number's colour. */
    static ImageView icon(Hub hub) {
        var v = new ImageView(AccountBrand.of(hub).icon());
        v.setFitWidth(40);
        v.setFitHeight(40);
        v.setSmooth(true);
        return v;
    }

    static Label label(String text, String style) {
        var l = new Label(text);
        l.getStyleClass().add(style);
        return l;
    }

    void toggle() {
        byPhone = !byPhone;
        said.setText("");
        render();
        if (byPhone) phone.requestFocus(); else newQr();
    }

    void render() {
        left.getChildren().clear();
        right.getChildren().clear();
        right.setAlignment(Pos.CENTER);
        if (byPhone) {
            title.setText("Link with phone number");
            left.getChildren().addAll(title, Steps.of("Type your phone number with the country code",
                    "Press Enter to get a code from WhatsApp",
                    "On the phone: Settings, Linked devices, Link a device, Link with phone number instead, then type the code"), said);
            if (code.getText().isBlank()) right.getChildren().addAll(phone, getCode);
            else right.getChildren().addAll(code, label("Type this code on your phone", "hint"));
            altLink.setText("Scan a QR code instead   ›   F2");
        } else {
            title.setText("Scan to link");
            left.getChildren().addAll(title, Steps.of("Open WhatsApp on your phone", "Go to Settings, then Linked devices, then Link a device",
                    "Point your phone at this screen to scan the QR code"), said);
            right.getChildren().add(qrBox);
            altLink.setText("Link with phone number instead   ›   F2");
        }
    }

    void tick() {
        var s = hub.status();
        if (Boolean.TRUE.equals(s.get("loggedIn"))) { stage.hide(); ChatWindow.show(hub); return; }
        var payload = s.get("pendingQr") == null ? null : String.valueOf(s.get("pendingQr"));
        if (!Objects.equals(payload, shownQr)) {
            shownQr = payload;
            qrBox.getChildren().setAll(payload == null ? new ProgressIndicator() : qrView(payload));
        }
        var pair = s.get("pairCode") == null ? "" : String.valueOf(s.get("pairCode"));
        if (!pair.equals(shownCode)) {
            shownCode = pair;
            code.setText(pair.length() == 8 ? pair.substring(0, 4) + "-" + pair.substring(4) : pair);
            if (byPhone) render();
        }
        if (!byPhone && payload == null && System.currentTimeMillis() - qrAskedAt > 30_000 && Boolean.TRUE.equals(s.get("connectorAlive"))) newQr();
    }

    Node qrView(String payload) {
        qr.setImage(Qr.image(payload, 206));
        return qr;
    }

    void newQr() {
        qrAskedAt = System.currentTimeMillis();
        said.setText("Asking WhatsApp for a QR code…");
        ask("pair.qr", Map.of(), () -> said.setText(""));
    }

    void getCode() {
        var number = phone.getText().replaceAll("[^0-9]", "");
        if (number.isBlank()) { said.setText("Type the phone number first, with the country code."); return; }
        said.setText("Asking WhatsApp for a code for +" + number + " …");
        ask("pair.code", Map.of("phone", number, "notify", true), () -> said.setText(""));
    }

    void ask(String cmd, Map<String, Object> args, Runnable then) {
        Thread.ofVirtual().start(() -> {
            try {
                var reply = hub.connector.call(cmd, args, 60);
                if (reply.get("ok").getAsBoolean()) Platform.runLater(then);
                else Platform.runLater(() -> said.setText("WhatsApp refused: " + reply.get("error").getAsString()));
            } catch (Exception e) {
                Log.error(cmd + " failed", e);
                Platform.runLater(() -> said.setText(cmd + " failed: " + e.getMessage()));
            }
        });
    }
}
