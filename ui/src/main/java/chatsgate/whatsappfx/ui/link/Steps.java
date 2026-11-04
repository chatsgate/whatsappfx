package chatsgate.whatsappfx.ui.link;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;

/** Numbered steps: a circle per step, joined by a thin line, as on WhatsApp's own login screen. */
final class Steps {

    private Steps() {}

    static Node of(String... texts) {
        var box = new VBox();
        for (var i = 0; i < texts.length; i++) {
            var number = new Label(String.valueOf(i + 1));
            number.getStyleClass().add("step-number");
            var column = new VBox(number);
            column.setAlignment(Pos.TOP_CENTER);
            if (i < texts.length - 1) {
                var line = new Region();
                line.getStyleClass().add("step-line");
                line.setMinHeight(22);
                VBox.setVgrow(line, Priority.ALWAYS);
                column.getChildren().add(line);
            }
            var text = new Label(texts[i]);
            text.setWrapText(true);
            text.getStyleClass().add("step-text");
            text.setMinHeight(26);
            var row = new HBox(16, column, text);
            row.setAlignment(Pos.TOP_LEFT);
            box.getChildren().add(row);
        }
        return box;
    }
}
