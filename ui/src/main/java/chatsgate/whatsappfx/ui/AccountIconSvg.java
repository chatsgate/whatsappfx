package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.ui.AccountIcon.Tones;
import module java.base;
import module java.desktop;

/// The large drawing as an SVG, built from the very shapes the PNGs use so the two cannot drift apart.
final class AccountIconSvg {

    static String svg(Tones t) {
        var p = AccountIconLarge.parts(256);
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" width=\"64\" height=\"64\">"
            + "<defs><linearGradient id=\"t\" gradientUnits=\"userSpaceOnUse\" x1=\"10\" y1=\"2\" x2=\"54\" y2=\"62\"><stop offset=\"0\" stop-color=\"" + hex(t.top()) + "\"/><stop offset=\"1\" stop-color=\"" + hex(t.bot()) + "\"/></linearGradient>"
            + "<linearGradient id=\"l\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"" + n(p.lockTop()) + "\" x2=\"0\" y2=\"" + n(p.lockBottom()) + "\"><stop offset=\"0\" stop-color=\"" + hex(t.lockTop()) + "\"/><stop offset=\"1\" stop-color=\"" + hex(t.lockBot()) + "\"/></linearGradient></defs>"
            + "<rect width=\"64\" height=\"64\" rx=\"14\" fill=\"url(#t)\"/>"
            + shape(p.glyph(), "#fff") + shape(p.gap(), hex(t.halo())) + shape(p.lock(), "url(#l)") + "</svg>\n";
    }

    static String shape(Area a, String fill) {
        var evenOdd = a.getPathIterator(null).getWindingRule() == PathIterator.WIND_EVEN_ODD;
        return "<path d=\"" + path(a) + "\" fill=\"" + fill + "\" fill-rule=\"" + (evenOdd ? "evenodd" : "nonzero") + "\"/>";
    }

    static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    static String n(double v) { return String.format(Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", ""); }

    static String path(Area a) {
        var sb = new StringBuilder();
        var it = a.getPathIterator(null);
        var c = new double[6];
        for (; !it.isDone(); it.next()) {
            var k = it.currentSegment(c);
            var count = switch (k) { case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2; case PathIterator.SEG_QUADTO -> 4; case PathIterator.SEG_CUBICTO -> 6; default -> 0; };
            sb.append("MLQCZ".charAt(k));
            for (var i = 0; i < count; i++) sb.append(i > 0 ? " " : "").append(n(c[i]));
        }
        return sb.toString();
    }

    private AccountIconSvg() {}
}
