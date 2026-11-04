package chatsgate.whatsappfx.ui;

import module java.base;

/**
 * A loader that serves {@code whatsappfx-<size>.png} (and the {@code -dark-} names, the same image) from a renderer instead of
 * from the jar. leasegate's GateIcons reads a brand's icons through {@code someClass.getResourceAsStream(...)}; handing it a
 * class defined by this loader gives the tray, windows, cards and the approval window an icon drawn for any number's colour.
 */
final class RuntimeIcons extends ClassLoader {

    private static final Pattern ICON = Pattern.compile("(?:^|/)whatsappfx-(?:dark-)?([0-9]+)[.]png$");

    private final IntFunction<byte[]> png;

    RuntimeIcons(IntFunction<byte[]> png) {
        super(RuntimeIcons.class.getClassLoader());
        this.png = png;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        var m = ICON.matcher(name);
        return m.find() ? new ByteArrayInputStream(png.apply(Integer.parseInt(m.group(1)))) : super.getResourceAsStream(name);
    }

    /** A class whose resources are this loader's. */
    Class<?> owner() {
        try (var in = RuntimeIcons.class.getResourceAsStream("IconOwner.class")) {
            var bytes = in.readAllBytes();
            return defineClass(IconOwner.class.getName(), bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
