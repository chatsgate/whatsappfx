import module java.base;
import module java.compiler;
import module java.desktop;

/// Writes the icon files. The DRAWING lives in the ui module, `chatsgate.whatsappfx.ui.AccountIcon` (the app renders its
/// icons from it at run time for each number's colour); this program compiles those classes itself, so no prior build
/// is needed, and writes what has to exist as files: the PNGs the app still loads for its default colour, the exe's .ico,
/// and a reference PNG / SVG / .ico set per colour of the palette.
/// Run from the repository root: java ui/icons/MakeIcons.java
public class MakeIcons {

    static final String DEFAULT = "#21c063";
    static final String[][] ACCENTS = {
        {"green", DEFAULT}, {"blue", "#58a6ff"}, {"red", "#e5534b"}, {"amber", "#d29922"},
        {"violet", "#bc8cff"}, {"cyan", "#39c5cf"}, {"pink", "#f778ba"}, {"orange", "#ff8c42"},
    };
    static final int[] RES_SIZES = {16, 24, 32, 48, 256}, REF_SIZES = {16, 24, 32, 48, 64, 128, 256};

    public static void main(String[] a) throws Exception {
        var icon = load();
        var render = icon.getMethod("render", String.class, int.class);
        var svg = icon.getMethod("svg", String.class);
        var ico = icon.getMethod("ico", String.class);
        var res = Path.of("ui/src/main/resources/chatsgate/whatsappfx/ui");
        var icons = Path.of("ui/icons");
        var png = icons.resolve("png");
        Files.createDirectories(res); Files.createDirectories(png);
        for (var name : new String[] {"whatsappfx", "whatsappfx-dark"}) {      // same image: the tile reads on a dark or a light taskbar
            for (var s : RES_SIZES) write(res.resolve(name + "-" + s + ".png"), render(render, DEFAULT, s));
            for (var s : REF_SIZES) write(png.resolve(name + "-" + s + ".png"), render(render, DEFAULT, s));
            Files.writeString(icons.resolve(name + ".svg"), (String) svg.invoke(null, DEFAULT));
        }
        Files.write(Path.of("ui/src/main/jr/whatsappfx.ico"), (byte[]) ico.invoke(null, DEFAULT));
        var accents = icons.resolve("accents");
        if (Files.exists(accents)) try (var old = Files.walk(accents)) { old.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        for (var ac : ACCENTS) {
            var dir = Files.createDirectories(accents.resolve(ac[0]));
            var base = "whatsappfx-" + ac[0];
            for (var s : REF_SIZES) write(dir.resolve(base + "-" + s + ".png"), render(render, ac[1], s));
            Files.writeString(dir.resolve(base + ".svg"), (String) svg.invoke(null, ac[1]));
            Files.write(dir.resolve(base + ".ico"), (byte[]) ico.invoke(null, ac[1]));
        }
        System.out.println("icons written");
    }

    static BufferedImage render(Method render, String hex, int size) throws Exception { return (BufferedImage) render.invoke(null, hex, size); }

    static void write(Path file, BufferedImage img) throws IOException { ImageIO.write(img, "png", file.toFile()); }

    /// Compiles the AccountIcon sources into a temp folder and loads them.
    static Class<?> load() throws Exception {
        var src = Path.of("ui/src/main/java/chatsgate/whatsappfx/ui");
        var out = Files.createTempDirectory("accounticon");
        var args = new ArrayList<>(java.util.List.of("-d", out.toString(), "-proc:none"));
        for (var n : new String[] {"AccountIcon", "AccountIconLarge", "AccountIconSmall", "AccountIconSvg", "FaPath"}) args.add(src.resolve(n + ".java").toString());
        if (javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)) != 0) throw new IllegalStateException("AccountIcon does not compile");
        return new URLClassLoader(new URL[] {out.toUri().toURL()}).loadClass("chatsgate.whatsappfx.ui.AccountIcon");
    }
}
