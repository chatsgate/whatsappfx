package chatsgate.whatsappfx.ui.chat;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import chatsgate.whatsappfx.core.Log;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Text that draws emoji (see {@link Emoji}), links that open in the browser on a click, and named @mentions in the
 * mention colour. With a line height it is one clipped line; with 0 it wraps.
 */
final class RichText extends TextFlow {

    static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+");
    static final String ACCENT = "-fx-fill: #53bdeb;";

    /** Links open on a click only in a message; a one-line snippet in the chat list is for choosing the chat. */
    final boolean clickable;

    RichText(double lineHeight) {
        clickable = lineHeight <= 0;
        setMinWidth(0);
        if (lineHeight > 0) {
            setMinHeight(lineHeight);
            setPrefHeight(lineHeight);
            setMaxHeight(lineHeight);
            var clip = new Rectangle();
            clip.widthProperty().bind(widthProperty());
            clip.heightProperty().bind(heightProperty());
            setClip(clip);
        }
    }

    RichText set(String s, String style) {
        var base = "-fx-fill: -wa-text; " + style;
        var out = new ArrayList<Node>();
        var text = s == null ? "" : s;
        int at = 0;
        while (at < text.length()) {
            var open = text.indexOf(People.OPEN, at);
            var close = open < 0 ? -1 : text.indexOf(People.CLOSE, open);
            if (open < 0 || close < 0) { links(text.substring(at), base, out, clickable); break; }
            links(text.substring(at, open), base, out, clickable);
            out.addAll(Emoji.texts(text.substring(open + 1, close), base + ACCENT + " -fx-font-weight: bold;"));
            at = close + 1;
        }
        if (out.isEmpty()) out.addAll(Emoji.texts("", base));
        getChildren().setAll(out);
        return this;
    }

    /** Plain runs, with every link made a clickable run of its own. */
    static void links(String s, String base, List<Node> out, boolean clickable) {
        var m = LINK.matcher(s);
        int at = 0;
        while (m.find()) {
            var url = trim(m.group());
            var end = m.start() + url.length();
            if (m.start() > at) out.addAll(Emoji.texts(s.substring(at, m.start()), base));
            if (clickable) out.add(link(url, base));
            else out.addAll(Emoji.texts(url, base + ACCENT));
            at = end;
        }
        if (at < s.length()) out.addAll(Emoji.texts(s.substring(at), base));
    }

    /** A sentence's full stop or closing bracket is not part of the link. */
    static String trim(String url) {
        var u = url;
        while (!u.isEmpty() && ".,;:!?)]}'\"".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1);
        return u;
    }

    static Text link(String url, String base) {
        var t = new Text(url);
        t.setStyle(base + ACCENT + " -fx-underline: true;");
        t.setCursor(Cursor.HAND);
        t.setOnMouseClicked(e -> { if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) { e.consume(); open(url); } });
        return t;
    }

    /** In the default browser, off the FX thread. */
    static void open(String url) {
        var target = url.regionMatches(true, 0, "www.", 0, 4) ? "https://" + url : url;
        Thread.ofVirtual().start(() -> {
            try {
                java.awt.Desktop.getDesktop().browse(URI.create(target));
            } catch (Exception e) {
                try {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start();
                } catch (Exception again) {
                    Log.warn("link not opened: " + target + ": " + again.getMessage());
                }
            }
        });
    }

    /** Every link in the text, for the message menu. */
    static List<String> linksIn(String s) {
        var out = new ArrayList<String>();
        if (s == null) return out;
        var m = LINK.matcher(s);
        while (m.find()) out.add(trim(m.group()));
        return out;
    }
}
