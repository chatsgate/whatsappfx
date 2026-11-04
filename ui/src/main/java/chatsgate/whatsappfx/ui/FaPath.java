package chatsgate.whatsappfx.ui;

import module java.base;
import module java.desktop;

/// The "whatsapp" glyph of Font Awesome Free 6.5.2 (https://fontawesome.com), icons licensed CC BY 4.0, Copyright 2024 Fonticons, Inc.
/// Only its handset (subpath 2) is used; the ring is redrawn as a plain circle. Credited in the README.
final class FaPath {

    static final String WHATSAPP = "M380.9 97.1C339 55.1 283.2 32 223.9 32c-122.4 0-222 99.6-222 222 0 39.1 10.2 77.3 29.6 111L0 480l117.7-30.9c32.4 17.7 68.9 27 106.1 27h.1c122.3 0 224.1-99.6 224.1-222 0-59.3-25.2-115-67.1-157zm-157 341.6c-33.2 0-65.7-8.9-94-25.7l-6.7-4-69.8 18.3L72 359.2l-4.4-7c-18.5-29.4-28.2-63.3-28.2-98.2 0-101.7 82.8-184.5 184.6-184.5 49.3 0 95.6 19.2 130.4 54.1 34.8 34.9 56.2 81.2 56.1 130.5 0 101.8-84.9 184.6-186.6 184.6zm101.2-138.2c-5.5-2.8-32.8-16.2-37.9-18-5.1-1.9-8.8-2.8-12.5 2.8-3.7 5.6-14.3 18-17.6 21.8-3.2 3.7-6.5 4.2-12 1.4-32.6-16.3-54-29.1-75.5-66-5.7-9.8 5.7-9.1 16.3-30.3 1.8-3.7.9-6.9-.5-9.7-1.4-2.8-12.5-30.1-17.1-41.2-4.5-10.8-9.1-9.3-12.5-9.5-3.2-.2-6.9-.2-10.6-.2-3.7 0-9.7 1.4-14.8 6.9-5.1 5.6-19.4 19-19.4 46.3 0 27.3 19.9 53.7 22.6 57.4 2.8 3.7 39.1 59.7 94.8 83.8 35.2 15.2 49 16.5 66.6 13.9 10.7-1.6 32.8-13.4 37.4-26.4 4.6-13 4.6-24.1 3.2-26.4-1.3-2.5-5-3.9-10.5-6.6z";

    static final java.util.List<Path2D.Double> PARTS = parse(WHATSAPP);

    static Area handset() { return new Area(PARTS.get(2)); }

    /// One Path2D per subpath of an SVG path made of M/m, L/l, H/h, V/v, C/c, S/s, Z/z.
    static java.util.List<Path2D.Double> parse(String d) {
        var out = new ArrayList<Path2D.Double>();
        var tok = java.util.regex.Pattern.compile("[MmLlHhVvCcSsZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)").matcher(d);
        var t = new ArrayList<String>();
        while (tok.find()) t.add(tok.group());
        var i = 0; var cmd = (char) 0;
        double x = 0, y = 0, sx = 0, sy = 0, cx2 = 0, cy2 = 0;
        var cur = (Path2D.Double) null;
        while (i < t.size()) {
            if (Character.isLetter(t.get(i).charAt(0))) cmd = t.get(i++).charAt(0);
            var rel = Character.isLowerCase(cmd);
            var c = Character.toUpperCase(cmd);
            switch (c) {
                case 'M' -> {
                    var nx = Double.parseDouble(t.get(i++)) + (rel ? x : 0); var ny = Double.parseDouble(t.get(i++)) + (rel ? y : 0);
                    cur = new Path2D.Double(Path2D.WIND_NON_ZERO); out.add(cur);
                    cur.moveTo(nx, ny); x = sx = nx; y = sy = ny; cmd = rel ? 'l' : 'L';
                }
                case 'L' -> { var nx = Double.parseDouble(t.get(i++)) + (rel ? x : 0); var ny = Double.parseDouble(t.get(i++)) + (rel ? y : 0); cur.lineTo(nx, ny); x = nx; y = ny; }
                case 'H' -> { var nx = Double.parseDouble(t.get(i++)) + (rel ? x : 0); cur.lineTo(nx, y); x = nx; }
                case 'V' -> { var ny = Double.parseDouble(t.get(i++)) + (rel ? y : 0); cur.lineTo(x, ny); y = ny; }
                case 'C' -> {
                    var q = new double[6]; for (var k = 0; k < 6; k++) q[k] = Double.parseDouble(t.get(i++));
                    if (rel) for (var k = 0; k < 6; k += 2) { q[k] += x; q[k + 1] += y; }
                    cur.curveTo(q[0], q[1], q[2], q[3], q[4], q[5]); cx2 = q[2]; cy2 = q[3]; x = q[4]; y = q[5];
                }
                case 'S' -> {
                    var q = new double[4]; for (var k = 0; k < 4; k++) q[k] = Double.parseDouble(t.get(i++));
                    if (rel) for (var k = 0; k < 4; k += 2) { q[k] += x; q[k + 1] += y; }
                    cur.curveTo(2 * x - cx2, 2 * y - cy2, q[0], q[1], q[2], q[3]); cx2 = q[0]; cy2 = q[1]; x = q[2]; y = q[3];
                }
                case 'Z' -> { cur.closePath(); x = sx; y = sy; }
                default -> throw new IllegalStateException("path command " + cmd);
            }
            if (c != 'C' && c != 'S') { cx2 = x; cy2 = y; }
        }
        return out;
    }

    private FaPath() {}
}
