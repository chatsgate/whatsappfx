package chatsgate.whatsappfx.ui.chat;

import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/** Initials on a circle whose colour comes from the jid, so a person keeps one colour. Groups get a muted circle. */
final class Avatar extends StackPane {

    static final String[] COLORS = {"#2f8f7a", "#3b7dd8", "#c0607a", "#b9853a", "#7a62c9", "#4b9a4f", "#c46a3c"};
    static final String GROUP = "#3c4a52";

    final Circle back;
    final Text initials = new Text();

    Avatar(double size) {
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setAlignment(Pos.CENTER);
        back = new Circle(size / 2);
        initials.setFont(Font.font("Segoe UI", FontWeight.BOLD, size * 0.38));
        initials.setFill(Color.web("#f2f5f6"));
        getChildren().addAll(back, initials);
    }

    Avatar set(String label, String jid, boolean group) {
        initials.setText(initials(label));
        back.setFill(Color.web(group ? GROUP : COLORS[Math.floorMod(jid == null ? 0 : jid.hashCode(), COLORS.length)]));
        return this;
    }

    static String initials(String label) {
        if (label == null || label.isBlank()) return "";
        var s = new StringBuilder();
        for (var w : label.strip().split("\\s+")) {
            if (s.length() < 2 && Character.isLetterOrDigit(w.codePointAt(0))) {
                s.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
            }
        }
        if (s.isEmpty()) return label.strip().startsWith("+") ? "#" : label.strip().substring(0, 1);
        return s.toString();
    }
}
