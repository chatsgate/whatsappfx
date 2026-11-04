package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.core.KeyStore;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;

/** Shown once, the first time the session key is made on this PC: the key as text to write down, so a lost key does not mean linking again. FX thread. */
final class RecoveryCodeDialog {

    private RecoveryCodeDialog() {}

    static void show() {
        var a = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
        a.setTitle("WhatsappFx recovery code");
        a.setHeaderText("Write this code down and keep it somewhere safe");
        var code = new TextArea(KeyStore.recoveryCode());
        code.setEditable(false);
        code.setWrapText(true);
        code.setPrefRowCount(2);
        code.setStyle("-fx-font-family: monospace; -fx-font-size: 16px;");
        a.getDialogPane().setContent(code);
        a.getDialogPane().setPrefWidth(520);
        a.setContentText(null);
        a.getDialogPane().setExpandableContent(null);
        a.setResizable(true);
        a.show();
    }
}
