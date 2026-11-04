package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.ui.AccountIcon.Tones;
import module java.base;
import module java.desktop;

/// The icon for 20 px and below, drawn on a 16-unit grid (1 unit = 1 pixel at 16 px): a smooth ring and handset
/// around a padlock built from whole pixels, because scaling the large drawing down smears the lock and loses the handset.
final class AccountIconSmall {

    /// One character per pixel (X lock, K keyhole), rows 9..14 from column 0.
    static final String[] LOCK = {
        "...XX...........",
        "..X..X..........",
        ".XXXXXX.........",
        ".XXKKXX.........",
        ".XXXXXX.........",
        ".XXXXXX.........",
    };
    static final int ROW0 = 9;

    static void draw(Graphics2D g, Tones t) {
        var tile = new RoundRectangle2D.Double(0, 0, 16, 16, 7, 7);
        g.setPaint(new GradientPaint(2, 0, t.top(), 14, 16, t.bot()));
        g.fill(tile);
        var ring = new Area(new Ellipse2D.Double(1, 1, 14, 14));
        ring.subtract(new Area(new Ellipse2D.Double(3, 3, 10, 10)));
        var halo = halo(tile);
        ring.subtract(halo);
        ring.subtract(new Area(new Rectangle2D.Double(3, ROW0 + 1, 2, 1)));
        g.setColor(Color.WHITE); g.fill(ring);
        g.setColor(t.halo()); g.fill(halo);
        var hand = handset(); hand.subtract(halo);
        g.setColor(Color.WHITE); g.fill(hand);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (var r = 0; r < LOCK.length; r++) for (var c = 0; c < 16; c++) {
            var ch = LOCK[r].charAt(c);
            if (ch == '.') continue;
            g.setColor(ch == 'K' ? t.halo() : r < 2 ? t.lockTop() : t.lockBot());
            g.fillRect(c, r + ROW0, 1, 1);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    /// The handset, bolded a little, centred in the free upper right of the ring.
    static Area handset() {
        var hand = FaPath.handset();
        var hb = hand.getBounds2D();
        var hs = 4.8 / Math.max(hb.getWidth(), hb.getHeight());
        var at = new AffineTransform();
        at.translate(8.5, 6.5); at.scale(hs, hs); at.translate(-hb.getCenterX(), -hb.getCenterY());
        var shape = at.createTransformedShape(hand);
        var bold = new Area(shape);
        bold.add(new Area(new BasicStroke(0.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(shape)));
        return bold;
    }

    /// One pixel around the lock (not inside its shackle), kept inside the tile.
    static Area halo(Shape tile) {
        var halo = new Area();
        for (var r = -1; r <= 16; r++) for (var c = -1; c <= 16; c++) {
            var near = false;
            for (var dr = -1; dr <= 1; dr++) for (var dc = -1; dc <= 1; dc++) near |= lock(c + dc, r + dr);
            var hollow = (c == 3 || c == 4) && r == ROW0 + 1;
            if (near && !hollow) halo.add(new Area(new Rectangle2D.Double(c, r, 1, 1)));
        }
        halo.intersect(new Area(tile));
        return halo;
    }

    static boolean lock(int c, int r) {
        var i = r - ROW0;
        return i >= 0 && i < LOCK.length && c >= 0 && c < 16 && LOCK[i].charAt(c) != '.';
    }

    private AccountIconSmall() {}
}
