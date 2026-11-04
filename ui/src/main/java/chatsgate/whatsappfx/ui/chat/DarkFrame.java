package chatsgate.whatsappfx.ui.chat;

import javafx.stage.Stage;
import chatsgate.whatsappfx.core.Log;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Paints the native title bar and border dark through the DWM attributes (Windows 10 20H1+, colours on 11), as
 * a custom main frame does. Any failure leaves the ordinary frame: the window must open either way.
 */
public final class DarkFrame {

    static final int DARK_MODE = 20, BORDER_COLOR = 34, CAPTION_COLOR = 35, TEXT_COLOR = 36;

    private DarkFrame() {}

    /** 0xRRGGBB as the COLORREF Windows wants (0x00BBGGRR). */
    static int colorref(int rgb) {
        return ((rgb & 0xff) << 16) | (rgb & 0xff00) | ((rgb >> 16) & 0xff);
    }

    public static void paint(Stage stage, int caption, int text, int border) {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return;
        var title = stage.getTitle();
        // found by title, so for that instant the title is made unique
        var probe = title + " " + System.nanoTime();
        try (var arena = Arena.ofConfined()) {
            var linker = Linker.nativeLinker();
            var user32 = SymbolLookup.libraryLookup("user32", arena);
            var dwm = SymbolLookup.libraryLookup("dwmapi", arena);
            var find = linker.downcallHandle(user32.findOrThrow("FindWindowW"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            var set = linker.downcallHandle(dwm.findOrThrow("DwmSetWindowAttribute"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            stage.setTitle(probe);
            var hwnd = (MemorySegment) find.invoke(MemorySegment.NULL, arena.allocateFrom(probe, StandardCharsets.UTF_16LE));
            stage.setTitle(title);
            if (hwnd.equals(MemorySegment.NULL)) {
                Log.warn("chat window: dark title bar, window not found");
                return;
            }
            attribute(set, arena, hwnd, DARK_MODE, 1);
            attribute(set, arena, hwnd, CAPTION_COLOR, colorref(caption));
            attribute(set, arena, hwnd, TEXT_COLOR, colorref(text));
            attribute(set, arena, hwnd, BORDER_COLOR, colorref(border));
        } catch (Throwable t) {
            stage.setTitle(title);
            Log.warn("chat window: dark title bar not applied: " + t);
        }
    }

    static void attribute(MethodHandle set, Arena arena, MemorySegment hwnd, int what, int value) throws Throwable {
        var v = arena.allocate(JAVA_INT);
        v.set(JAVA_INT, 0, value);
        int hr = (int) set.invoke(hwnd, what, v, 4);
        if (hr != 0) Log.warn("DwmSetWindowAttribute " + what + " returned " + Integer.toHexString(hr));
    }
}
