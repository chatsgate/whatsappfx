package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Os;

import java.util.List;

/**
 * The official WhatsApp Desktop app, opened for what whatsappfx cannot do yet and closed again to give its memory
 * back. Closing its window is not enough: it stays resident with its WebView2 processes (measured 2026-10-27: 8
 * processes, about 750 MB committed), so "close" ends the whole process tree. Opening goes through the whatsapp:
 * protocol, which the app registers; with a phone number it opens that chat.
 */
public final class OfficialApp {

    static final String EXE = "WhatsApp.Root.exe";

    private OfficialApp() {}

    public static boolean running() {
        return ProcessHandle.allProcesses().anyMatch(OfficialApp::isApp);
    }

    /** The main executable inside the app bundle on a Mac (its helpers live in other bundles and do not end like this). */
    static final String MAC_EXE = "/WhatsApp.app/Contents/MacOS/WhatsApp";

    static boolean isApp(ProcessHandle p) {
        return p.info().command().map(c -> Os.MAC ? c.endsWith(MAC_EXE) : c.endsWith("\\" + EXE)).orElse(false);
    }

    /** Opens it, on the chat with this phone number if one is given (digits only), else as it was. Never throws. */
    public static void open(String phoneDigits) {
        var uri = phoneDigits == null || phoneDigits.isBlank() ? "whatsapp://" : "whatsapp://send?phone=" + phoneDigits;
        Thread.ofVirtual().start(() -> {
            try {
                new ProcessBuilder(Os.MAC ? "open" : "explorer.exe", uri).start();
                Log.info("opened WhatsApp Desktop: " + uri);
            } catch (Exception e) {
                Log.warn("WhatsApp Desktop not opened: " + e.getMessage());
            }
        });
    }

    /** Ends WhatsApp Desktop and every process under it; returns how many were ended. Blocks briefly: not the FX thread. */
    public static int close() {
        var ended = 0;
        for (var app : ProcessHandle.allProcesses().filter(OfficialApp::isApp).toList()) {
            var tree = new java.util.ArrayList<ProcessHandle>(app.descendants().toList());
            tree.add(app);
            for (var p : tree) if (p.destroyForcibly()) ended++;
        }
        if (ended == 0 && running() && Os.WINDOWS) {
            // a packaged app's process may refuse a plain terminate from Java; taskkill takes the tree down
            try {
                new ProcessBuilder(List.of("taskkill", "/IM", EXE, "/T", "/F")).redirectErrorStream(true).start().waitFor();
                ended = running() ? 0 : 1;
            } catch (Exception e) {
                Log.warn("WhatsApp Desktop not closed: " + e.getMessage());
            }
        }
        Log.info("closed WhatsApp Desktop: " + ended + " process(es) ended");
        return ended;
    }
}
