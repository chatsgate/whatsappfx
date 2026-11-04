package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.daemon.Unlocks;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import leasegate.frame.FKeys;
import leasegate.frame.TaskbarTheme;

import module java.base;

/**
 * The command line's own settings. "Wallet open" (F6): for up to four hours every request of every kind, groups included, passes
 * without the green window, still written to access.log and still shown as a card after each send; the tray says it is on. It is
 * turned on only here, in the app's own window, never from the command line. When the unlock walls are switched on
 * ({@link Unlocks#REQUIRED}, off for WhatsappFx) the window also takes an unlock code and the reason for it.
 * Reached from the tray; it will move under Settings when that window exists.
 */
public final class CliAccessWindow {

    static CliAccessWindow open;
    private static ScheduledFuture<?> expiry;
    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("wa-wallet-open").factory());

    final Hub hub;
    final Stage stage = new Stage();
    final Label state = new Label(), said = new Label(), featureName = new Label();
    final TextArea reason = new TextArea();
    final TextField code = new TextField();
    Unlocks.Feature feature = Unlocks.Feature.AUTOMATION;

    public static void show(Hub hub) {
        if (open == null) open = new CliAccessWindow(hub);
        open.refresh();
        open.stage.show();
        open.stage.toFront();
        if (Unlocks.REQUIRED) open.reason.requestFocus();
    }

    private CliAccessWindow(Hub hub) {
        this.hub = hub;
        stage.setTitle("Command-line access");
        stage.getIcons().setAll(App.BRAND.icons().window(TaskbarTheme.isDark()));
        var title = styled("Command-line access", "content-title");
        var intro = styled("Programs that use whatsappcli, such as an AI assistant, each need your approval (the green window). "
                + "Wallet open skips that window for up to four hours, for every kind of request, groups included. "
                + "Everything is still written to access.log and every message sent is shown as a notice.", "row-sub");
        intro.setWrapText(true);
        state.getStyleClass().add("row-title");
        state.setWrapText(true);
        said.getStyleClass().add("row-title");
        said.setWrapText(true);
        var form = new VBox(14, title, intro, state);
        if (Unlocks.REQUIRED) {
            reason.setPromptText("Why you need command-line sending");
            reason.setWrapText(true);
            reason.setPrefRowCount(4);
            reason.getStyleClass().add("search-box");
            code.setPromptText("Code from the developer");
            code.getStyleClass().add("search-box");
            code.setOnAction(e -> unlock());
            featureName.getStyleClass().add("row-title");
            form.getChildren().addAll(featureName, reason, code, said);
        }
        form.getStyleClass().add("settings-content");
        form.setMaxWidth(760);
        form.setMaxHeight(Region.USE_PREF_SIZE);
        var center = new StackPane(form);
        center.setPadding(new Insets(24, 40, 0, 40));
        var keys = new FKeys().bind(KeyCode.F6, "Wallet open, 4 hours / off", "#555", this::walletOpen);
        if (Unlocks.REQUIRED) keys.bind(KeyCode.F2, "Switch: sending / groups", "#555", this::switchFeature).bind(KeyCode.F4, "Unlock", "#555", this::unlock);
        keys.bind(KeyCode.ESCAPE, "Close", "#555", stage::hide);
        var legend = new HBox(keys.bar);
        legend.setPadding(new Insets(12, 40, 16, 40));
        var root = new VBox(center, legend);
        root.getStyleClass().add("settings-root");
        VBox.setVgrow(center, Priority.ALWAYS);
        var scene = new Scene(root, 900, Unlocks.REQUIRED ? 640 : 420);
        scene.getStylesheets().add(CliAccessWindow.class.getResource("/chatsgate/whatsappfx/ui/chat/chat.css").toExternalForm());
        scene.getStylesheets().add(CliAccessWindow.class.getResource("/chatsgate/whatsappfx/ui/settings.css").toExternalForm());
        keys.install(scene);
        stage.setScene(scene);
        stage.setOnShown(e -> chatsgate.whatsappfx.ui.chat.DarkFrame.paint(stage, 0x161717, 0xfafafa, 0x262828));
        stage.setOnHidden(e -> { open = null; App.trimWhenHidden(); });
    }

    static Label styled(String text, String style) {
        var l = new Label(text);
        l.getStyleClass().add(style);
        return l;
    }

    void switchFeature() {
        feature = feature == Unlocks.Feature.AUTOMATION ? Unlocks.Feature.GROUPS : Unlocks.Feature.AUTOMATION;
        said.setText("");
        refresh();
    }

    void unlock() {
        var err = hub.unlocks.add(feature, hub.ownUser(), reason.getText(), code.getText());
        said.setText(err == null ? "Unlocked." : err);
        if (err == null) { code.clear(); refresh(); }
    }

    void walletOpen() {
        hub.walletOpen(hub.walletOpen() ? 0 : 4);
        shown();
        refresh();
    }

    /** The tray says it is on, and says it is off again when the time is up. */
    void shown() {
        if (expiry != null) expiry.cancel(false);
        if (hub.walletOpen()) {
            Tray.tooltip(Tray.TIP + " - COMMAND LINE OPEN until " + until());
            expiry = TIMER.schedule(() -> Platform.runLater(() -> Tray.tooltip(Tray.TIP)), hub.walletOpenUntilMillis() - System.currentTimeMillis() + 1000, TimeUnit.MILLISECONDS);
        } else {
            Tray.tooltip(Tray.TIP);
        }
    }

    String until() {
        return java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(hub.walletOpenUntilMillis()), java.time.ZoneId.systemDefault()).withNano(0).toString();
    }

    void refresh() {
        state.setText(hub.walletOpen() ? "WALLET OPEN until " + until() + ": every request passes without a window."
                : "Wallet open is off: each program asks you.");
        if (Unlocks.REQUIRED) {
            var number = hub.ownUser();
            featureName.setText((feature == Unlocks.Feature.AUTOMATION ? "Sending from the command line" : "Sending to groups") + "  ·  number " + (number == null ? "not linked" : "+" + number)
                    + "  ·  " + (hub.unlocks.has(feature, number) ? "UNLOCKED" : "locked"));
        }
    }
}
