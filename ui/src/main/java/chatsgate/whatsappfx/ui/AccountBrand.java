package chatsgate.whatsappfx.ui;

import javafx.scene.paint.Color;
import leasegate.frame.Brand;
import leasegate.frame.GateIcons;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.daemon.Palette;

import module java.base;

/**
 * The app's brand (name, frame colour, icons) as one number wears it: leasegate's windows (the approval window, the access list)
 * and our own take a {@link Brand}, so a number's colour reaches all of them through this. The first palette colour is the shipped
 * art itself ({@link App#BRAND}); any other colour has its icons drawn at run time by {@link AccountIcon}.
 */
public final class AccountBrand {

    private static final Map<String, Brand> BRANDS = new ConcurrentHashMap<>();

    private AccountBrand() {}

    public static Brand of(Hub hub) {
        return of(hub.colour());
    }

    public static Brand of(String hex) {
        if (hex == null || hex.equalsIgnoreCase(Palette.COLOURS.getFirst())) return App.BRAND;
        return BRANDS.computeIfAbsent(hex, h -> new Brand(App.BRAND.product(), band(h),
                new GateIcons(new RuntimeIcons(size -> png(h, size)).owner(), "whatsappfx"), true));
    }

    /** The colour of a frame band (the approval window's top): the number's colour, deepened so white text reads on it. */
    static String band(String hex) {
        return Look.hex(Color.web(hex).deriveColor(0, 1, 0.62, 1));
    }

    /** The icon of one size in a number's colour, drawn in code (AccountIcon, PRP 19), as PNG bytes: JavaFX ignores a WritableImage among a stage's icons on Windows, so the image goes through PNG. */
    static byte[] png(String hex, int size) {
        try {
            var bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(AccountIcon.render(hex, size), "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
