package chatsgate.whatsappfx.ui;

import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import leasegate.frame.TaskbarTheme;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.ui.chat.DarkFrame;

import module java.base;

/**
 * What tells one number's windows from another's, as fxterm-tabs does for its windows: the number's colour (its accent, its
 * outgoing bubbles, the title bar and edge of the window by DWM, the taskbar icon repainted in it) and its name in the title. A
 * window is dressed once when it is made ({@link #dress}) and again whenever the person changes the name or the colour, while it
 * is open.
 */
public final class Look {

    private static final Color BASE = Color.web("#161717");
    private static final Map<Hub, List<Dressed>> OPEN = new ConcurrentHashMap<>();
    private static final Map<String, List<Image>> ICONS = new ConcurrentHashMap<>();

    private record Dressed(Stage stage, Parent root, Supplier<String> title, boolean dark, Runnable after) {}

    private Look() {}

    /** The window's title, its colours and its icon now, and again on every change of the number's name or colour, until it is hidden. FX thread. */
    public static void dress(Hub hub, Stage stage, Parent root, Supplier<String> title, boolean dark) {
        dress(hub, stage, root, title, dark, () -> {});
    }

    /** As above; {@code after} runs on every re-dressing too, for what the window itself shows of the name (a heading). */
    public static void dress(Hub hub, Stage stage, Parent root, Supplier<String> title, boolean dark, Runnable after) {
        var d = new Dressed(stage, root, title, dark, after);
        OPEN.computeIfAbsent(hub, h -> new CopyOnWriteArrayList<>()).add(d);
        paint(hub, d);
        stage.setOnShown(e -> frame(hub, d));
        stage.showingProperty().addListener((o, was, now) -> { if (!now) OPEN.getOrDefault(hub, new CopyOnWriteArrayList<>()).remove(d); });
    }

    /** The name or the colour of this number changed: every open window of it is dressed again. FX thread. */
    public static void restyle(Hub hub) {
        for (var d : OPEN.getOrDefault(hub, List.of())) { paint(hub, d); frame(hub, d); }
    }

    /** Every window of every number, after a number was added or removed: the taskbar icons are tinted only while there are several. */
    public static void restyleAll() {
        for (var h : OPEN.keySet()) restyle(h);
    }

    private static void paint(Hub hub, Dressed d) {
        d.stage().setTitle(d.title().get());
        d.root().setStyle(style(hub));
        d.stage().getIcons().setAll(icons(hub));
        d.after().run();
    }

    private static void frame(Hub hub, Dressed d) {
        if (!d.stage().isShowing()) return;
        var colour = Color.web(hub.colour());
        var caption = mix(d.dark() ? BASE : Color.web("#f0f2f5"), colour, d.dark() ? 0.38 : 0.55);
        DarkFrame.paint(d.stage(), rgb(caption), rgb(textOn(caption)), rgb(colour));
    }

    /** The colour names of the chats stylesheet, set on a window's root: its accent, the text on it, the outgoing bubble and the chips. */
    public static String style(Hub hub) {
        var a = Color.web(hub.colour());
        return "-wa-accent: " + hex(a) + "; -wa-accent-hover: " + hex(a.deriveColor(0, 1, 0.9, 1)) + "; -wa-on-accent: " + hex(textOn(a))
                + "; -wa-out: " + hex(mix(BASE, a, 0.30)) + "; -wa-time-out: " + hex(mix(Color.web("#a2a3a3"), a, 0.35)) + "; -wa-chip-on: " + hex(mix(BASE, a, 0.20)) + ";";
    }

    /** The taskbar icon in the number's colour (the shipped icon for the first palette colour). */
    public static List<Image> icons(Hub hub) {
        return ICONS.computeIfAbsent(hub.colour() + TaskbarTheme.isDark(), k -> AccountBrand.of(hub).icons().window(TaskbarTheme.isDark()));
    }

    public static Color mix(Color a, Color b, double t) { return a.interpolate(b, t); }

    /** Black or white text, whichever reads better on this colour. */
    public static Color textOn(Color c) {
        var l = 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
        return l > 0.55 ? Color.web("#0b1a12") : Color.web("#fafafa");
    }

    public static String hex(Color c) {
        return String.format("#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }

    static int rgb(Color c) {
        return (int) (Math.round(c.getRed() * 255) << 16 | Math.round(c.getGreen() * 255) << 8 | Math.round(c.getBlue() * 255));
    }

    /** A round button for a number: its colour, the first letter of its name on it, ringed while it is the one shown. A click runs {@code jump}. */
    public static javafx.scene.Node badge(Hub hub, boolean current, double size, Runnable jump) {
        var colour = Color.web(hub.colour());
        var dot = new javafx.scene.shape.Circle(size / 2 - 2, colour);
        dot.setStroke(current ? Color.web("#fafafa") : Color.TRANSPARENT);
        dot.setStrokeWidth(2.5);
        var letter = new javafx.scene.control.Label(hub.title().isBlank() ? "?" : hub.title().substring(0, hub.title().offsetByCodePoints(0, 1)).toUpperCase());
        letter.setStyle("-fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.42) + "; -fx-text-fill: " + hex(textOn(colour)) + ";");
        var box = new javafx.scene.layout.StackPane(dot, letter);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);
        box.setStyle("-fx-cursor: hand;");
        javafx.scene.control.Tooltip.install(box, new javafx.scene.control.Tooltip(hub.title() + (hub.ownUser() == null ? "  (not linked)" : "  +" + hub.ownUser()) + (current ? "  (this window)" : "  (F4: next number)")));
        box.setOnMouseClicked(e -> jump.run());
        return box;
    }

    /** A small round swatch of the number's colour, for menus and cards. */
    public static javafx.scene.Node swatch(String colour, double size) {
        var c = new javafx.scene.shape.Circle(size / 2);
        c.setFill(Color.web(colour));
        return c;
    }
}
