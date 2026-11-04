package chatsgate.whatsappfx.ui.chat;

import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX's fallback font lacks newer emoji (they draw as empty boxes), so emoji runs get their own Text in Segoe UI
 * Emoji. A joiner, variation selector or keycap mark only continues an emoji run: Indic scripts use the joiner
 * inside ordinary text, and splitting there would break their shaping.
 */
final class Emoji {

    static final String FONT = "-fx-font-family: 'Segoe UI Emoji';";

    private Emoji() {}

    static boolean base(int cp) {
        return cp >= 0x1F000 && cp <= 0x1FAFF || cp >= 0x2600 && cp <= 0x27BF || cp >= 0x2B00 && cp <= 0x2BFF
                || cp >= 0x231A && cp <= 0x231B || cp >= 0x23E9 && cp <= 0x23FA;
    }

    static boolean extender(int cp) {
        return cp == 0x200D || cp == 0xFE0F || cp == 0x20E3;
    }

    static List<Text> texts(String s, String style) {
        var out = new ArrayList<Text>();
        var run = new StringBuilder();
        var inEmoji = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            var emoji = base(cp) || inEmoji && extender(cp);
            if (emoji != inEmoji && !run.isEmpty()) {
                out.add(node(run.toString(), style, inEmoji));
                run.setLength(0);
            }
            inEmoji = emoji;
            run.appendCodePoint(cp);
        }
        if (!run.isEmpty() || out.isEmpty()) out.add(node(run.toString(), style, inEmoji));
        return out;
    }

    static Text node(String s, String style, boolean emoji) {
        var t = new Text(s);
        t.setStyle(emoji ? style + FONT : style);
        return t;
    }
}
