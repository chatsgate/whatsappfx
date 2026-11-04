package chatsgate.whatsappfx.ui;

import module java.base;
import module java.desktop;

/// The app icon for one number, drawn in code from that number's colour: `render("#21c063", 16)`.
/// A tile whose top, bottom, halo and lock tones are all derived from the one colour; the lock turns white on warm
/// colours (amber, orange) where amber would vanish. 24 px and up come from {@link AccountIconLarge}, 20 px and below
/// from {@link AccountIconSmall}. The same image serves a dark or a light taskbar.
public final class AccountIcon {

    public static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    /// The five tones one number's colour gives (a throwaway tuple used only by the icon classes).
    record Tones(Color top, Color bot, Color halo, Color lockTop, Color lockBot) {}

    public static BufferedImage render(String hexColour, int size) { return render(tones(hexColour), size); }

    public static String svg(String hexColour) { return AccountIconSvg.svg(tones(hexColour)); }

    /// An .ico whose pictures are PNGs (Windows reads them from Vista on).
    public static byte[] ico(String hexColour) throws IOException {
        var tones = tones(hexColour);
        var pics = new ArrayList<byte[]>();
        for (var s : ICO_SIZES) { var out = new ByteArrayOutputStream(); ImageIO.write(render(tones, s), "png", out); pics.add(out.toByteArray()); }
        var buf = ByteBuffer.allocate(6 + 16 * ICO_SIZES.length + pics.stream().mapToInt(b -> b.length).sum()).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) ICO_SIZES.length);
        var offset = 6 + 16 * ICO_SIZES.length;
        for (var i = 0; i < ICO_SIZES.length; i++) {
            var s = ICO_SIZES[i];
            buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0)
                .putShort((short) 1).putShort((short) 32).putInt(pics.get(i).length).putInt(offset);
            offset += pics.get(i).length;
        }
        for (var b : pics) buf.put(b);
        return buf.array();
    }

    /// The tile runs from a shade of the number's colour down to a deep one; the halo is deeper still.
    static Tones tones(String hex) {
        var c = Color.decode(hex.startsWith("#") ? hex : "#" + hex);
        var hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        var s = Math.min(1f, hsb[1] * 1.2f);
        var warm = hsb[0] * 360 >= 18 && hsb[0] * 360 <= 62;
        return new Tones(
            Color.getHSBColor(hsb[0], Math.min(1f, hsb[1] * 1.12f), hsb[2] * 0.9f),
            Color.getHSBColor(hsb[0], s, hsb[2] * 0.5f),
            Color.getHSBColor(hsb[0], s, hsb[2] * 0.3f),
            warm ? Color.WHITE : new Color(0xFFD650),
            warm ? new Color(0xE2E6E4) : new Color(0xFFB400));
    }

    static BufferedImage render(Tones t, int size) {
        var img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        if (size <= 20) { g.scale(size / 16.0, size / 16.0); AccountIconSmall.draw(g, t); }
        else { g.scale(size / 64.0, size / 64.0); AccountIconLarge.draw(g, size, t); }
        g.dispose();
        return img;
    }

    private AccountIcon() {}
}
