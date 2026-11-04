package chatsgate.whatsappfx.ui.chat;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * The few icons the windows draw, as vector paths on a 24-unit grid. They are the Material Design icon paths (Apache-2.0,
 * Google); WhatsApp's own icon font is its artwork and is not copied. Each icon is a fixed-size box, so a row of them lines up.
 */
public final class Icons {

    private Icons() {}

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("chat", "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z"),
            Map.entry("new-chat", "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 9h-3v3h-2v-3H9V9h4V6h2v3h3v2z"),
            Map.entry("more", "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"),
            Map.entry("search", "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"),
            Map.entry("call", "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"),
            Map.entry("video", "M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4z"),
            Map.entry("attach", "M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5c0-1.38 1.12-2.5 2.5-2.5s2.5 1.12 2.5 2.5v10.5c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5c0 1.38 1.12 2.5 2.5 2.5s2.5-1.12 2.5-2.5V5c0-2.21-1.79-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z"),
            Map.entry("emoji", "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z"),
            Map.entry("mic", "M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"),
            Map.entry("send", "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"),
            Map.entry("settings", "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"));

    /** An icon of {@code size} pixels in {@code color}. */
    public static Node of(String name, double size, Color color) {
        var p = new SVGPath();
        p.setContent(PATHS.get(name));
        p.setFill(color);
        var scale = size / 24.0;
        p.setScaleX(scale);
        p.setScaleY(scale);
        var box = new StackPane(p);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setAlignment(Pos.CENTER);
        // The path is laid out at 24 units and scaled about its centre, so the box clips nothing it should show.
        p.setManaged(false);
        p.setLayoutX(size / 2 - 12);
        p.setLayoutY(size / 2 - 12);
        return box;
    }

    /** A button marked as not available: dimmed, with a red strike across the icon. */
    public static StackPane crossed(StackPane button) {
        button.setOpacity(0.5);
        var strike = new javafx.scene.shape.Line(0, 20, 20, 0);
        strike.setStroke(Color.web("#e5534b"));
        strike.setStrokeWidth(2.4);
        strike.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        button.getChildren().add(strike);
        return button;
    }

    /** Fills an icon (as {@link #of} or {@link #button} made it) with the window's accent: a colour name resolved against the window's root, so it follows a change of colour. */
    public static void accent(Node icon) {
        if (icon instanceof SVGPath p) p.setStyle("-fx-fill: -wa-accent;");
        else if (icon instanceof javafx.scene.Parent parent) for (var n : parent.getChildrenUnmodifiable()) accent(n);
    }

    /** A round button the size of WhatsApp's header buttons: the icon in the middle, a soft circle under the pointer. */
    public static StackPane button(String name, double icon, Color color, String tip) {
        var b = new StackPane(of(name, icon, color));
        b.getStyleClass().add("icon-button");
        b.setMinSize(40, 40);
        b.setPrefSize(40, 40);
        b.setMaxSize(40, 40);
        if (tip != null) javafx.scene.control.Tooltip.install(b, new javafx.scene.control.Tooltip(tip));
        return b;
    }
}
