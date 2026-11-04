package chatsgate.whatsappfx.daemon;

import module java.base;

/**
 * The colours a number's window can wear, as fxterm-tabs does for its windows: eight, far enough apart to tell at a glance on a
 * taskbar, the first one WhatsApp's own green (what the app looked like before there were several). A colour is never shared.
 */
public final class Palette {

    public static final List<String> COLOURS = List.of("#21c063", "#58a6ff", "#e5534b", "#d29922", "#bc8cff", "#39c5cf", "#f778ba", "#ff8c42");
    public static final List<String> NAMES = List.of("Green", "Blue", "Red", "Amber", "Violet", "Cyan", "Pink", "Orange");

    private Palette() {}

    /** The first palette colour that {@code taken} does not hold; when all eight are taken they are reused in turn. */
    public static String free(Collection<String> taken) {
        for (var c : COLOURS) if (!taken.contains(c)) return c;
        return COLOURS.get(taken.size() % COLOURS.size());
    }

    /** A colour typed or named by a person: a palette name ("blue"), or {@code #rrggbb}; null when it is neither. */
    public static String parse(String s) {
        if (s == null || s.isBlank()) return null;
        var t = s.strip();
        for (var i = 0; i < NAMES.size(); i++) if (NAMES.get(i).equalsIgnoreCase(t)) return COLOURS.get(i);
        if (t.matches("#[0-9a-fA-F]{6}")) return t.toLowerCase(Locale.ROOT);
        return null;
    }

    public static String nameOf(String colour) {
        var i = COLOURS.indexOf(colour);
        return i < 0 ? colour : NAMES.get(i);
    }
}
