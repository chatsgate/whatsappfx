package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.ui.AccountIcon.Tones;
import module java.base;
import module java.desktop;

/// The icon for 24 px and up, on a 64-unit grid: a rounded tile, a complete white ring with the handset, and a padlock
/// badge at the lower left whose shackle has a dark border only.
final class AccountIconLarge {

    static final Shape TILE = new RoundRectangle2D.Double(0, 0, 64, 64, 28, 28);

    record Parts(Area glyph, Area gap, Area lock, double lockTop, double lockBottom) {}

    static Parts parts(int size) {
        var sw = size <= 24 ? 4.0 : 3.5;
        double shW = 11.6, shH = 7.6, w = 20, h = 15.5, lx = 16.6, lt = 36.4;
        var glyph = glyph(32, 32, 25, size <= 24 ? 4.6 : 4.0, size <= 24 ? 1.08 : 1.0);
        var lock = new Area(padlock(lx, lt, w, h, sw, shW, shH, size > 24));
        var gap = new Area(new BasicStroke(size <= 24 ? 4.2f : 3.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(lock));
        gap.add(lock);
        gap.intersect(new Area(TILE));
        glyph.subtract(gap);
        return new Parts(glyph, gap, lock, lt, lt + shH + h);
    }

    /// The ring (a plain circle) with the handset, centred on (cx, cy), outer radius r.
    static Area glyph(double cx, double cy, double r, double ringW, double handsetScale) {
        var s = r / 223.0;
        var at = new AffineTransform();
        at.translate(cx, cy); at.scale(s, s); at.translate(-224.5, -254.5);
        var inner = 223 - ringW / s;
        var ring = new Area(new Ellipse2D.Double(224.5 - 223, 254.5 - 223, 446, 446));
        ring.subtract(new Area(new Ellipse2D.Double(224.5 - inner, 254.5 - inner, inner * 2, inner * 2)));
        var h = FaPath.handset();
        var hb = h.getBounds2D();
        var hs = new AffineTransform();
        hs.translate(hb.getCenterX(), hb.getCenterY()); hs.scale(handsetScale, handsetScale); hs.translate(-hb.getCenterX(), -hb.getCenterY());
        ring.add(new Area(hs.createTransformedShape(h)));
        return new Area(at.createTransformedShape(ring));
    }

    /// Body, shackle and (when there is room) a keyhole knocked through. `top` is the top of the shackle's centre line.
    static Shape padlock(double cx, double top, double w, double h, double sw, double shW, double shH, boolean hole) {
        var lock = new Area();
        var by = top + shH;
        var sh = new Path2D.Double();
        sh.moveTo(cx - shW / 2, by + 1); sh.lineTo(cx - shW / 2, by - shH + shW / 2);
        sh.append(new Arc2D.Double(cx - shW / 2, by - shH, shW, shW, 180, -180, Arc2D.OPEN), true);
        sh.lineTo(cx + shW / 2, by + 1);
        lock.add(new Area(new BasicStroke((float) sw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(sh)));
        lock.add(new Area(new RoundRectangle2D.Double(cx - w / 2, by, w, h, w * 0.26, w * 0.26)));
        if (hole) {
            var ky = by + h * 0.42;
            var k = new Area(new Ellipse2D.Double(cx - 1.8, ky - 1.8, 3.6, 3.6));
            k.add(new Area(new RoundRectangle2D.Double(cx - 0.85, ky + 0.6, 1.7, h * 0.34, 0.85, 0.85)));
            lock.subtract(k);
        }
        return lock;
    }

    static void draw(Graphics2D g, int size, Tones t) {
        var p = parts(size);
        g.setPaint(new GradientPaint(10, 2, t.top(), 54, 62, t.bot()));
        g.fill(TILE);
        g.setColor(Color.WHITE); g.fill(p.glyph());
        g.setColor(t.halo()); g.fill(p.gap());
        g.setPaint(new GradientPaint(0, (float) p.lockTop(), t.lockTop(), 0, (float) p.lockBottom(), t.lockBot()));
        g.fill(p.lock());
    }

    private AccountIconLarge() {}
}
