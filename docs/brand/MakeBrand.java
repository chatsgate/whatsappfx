import module java.base;
import module java.desktop;

import java.awt.geom.*;

/// Draws the repository social preview (1280x640). Run from this folder: java MakeBrand.java
/// The tile is the app icon (ui/icons/png/whatsappfx-256.png, made by ui/icons/MakeIcons.java, run that first).
public class MakeBrand {

    static final Color GREEN = new Color(0x1AA37A), DEEP = new Color(0x0B2A22), DARK = new Color(0x123F33),
            MINT = new Color(0xCDEFE3), AMBER = new Color(0xF2A93B), INK = new Color(0x16181D), WHITE = Color.WHITE;

    public static void main(String[] a) throws Exception {
        ImageIO.write(preview(), "png", new File("social-preview-1280x640.png"));
        System.out.println("written");
    }

    static Graphics2D g(BufferedImage img) {
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /// The app icon: the finished 256 px picture made by ui/icons/MakeIcons.java (the one source of truth for the drawing,
    /// run it first), scaled to `size`.
    static void icon(Graphics2D g, double size) {
        try {
            var img = ImageIO.read(new File("../../ui/icons/png/whatsappfx-256.png"));
            g.drawImage(img, 0, 0, (int) size, (int) size, null);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static BufferedImage preview() {
        var img = new BufferedImage(1280, 640, BufferedImage.TYPE_INT_RGB);
        var g = g(img);
        g.setPaint(new GradientPaint(0, 0, DEEP, 1280, 640, DARK));
        g.fillRect(0, 0, 1280, 640);
        var t = g.getTransform();
        g.translate(100, 150);
        icon(g, 340);
        g.setTransform(t);
        g.setColor(WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 100));
        g.drawString("WhatsappFx", 500, 290);
        g.setColor(MINT);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 28));
        g.drawString("An opensource alternative to WhatsApp,", 504, 360);
        g.drawString("a light desktop window for your chats,", 504, 410);
        g.drawString("with a command line you approve program by program.", 504, 460);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 26));
        g.setColor(new Color(0x8FC9B5));
        g.drawString("github.com/chatsgate/whatsappfx  ·  not affiliated with WhatsApp", 504, 560);
        g.dispose();
        return img;
    }
}
