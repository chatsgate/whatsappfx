package chatsgate.whatsappfx.ui;

import chatsgate.whatsappfx.wire.DaemonClient;

import java.util.Map;

/**
 * A main class that is not the Application subclass.
 *
 * <p>Launching an Application directly from a shaded jar fails with "JavaFX runtime components are
 * missing": the launcher checks whether the main class extends Application and then insists on the
 * module path. One level of indirection sidesteps the check.
 */
public final class Launcher {

    public static void main(String[] args) {
        // An agent asking a GUI what it does should not be answered by a window opening on somebody's
        // screen, so --help prints and exits instead of launching.
        for (var a : args) {
            if (a.equals("--help") || a.equals("-h") || a.equals("/?")) {
                help();
                return;
            }
            if (a.equals("--version") || a.equals("-V")) {
                System.out.println("WhatsappFx 1.0");
                return;
            }
        }
        if (showRunning(args)) return;
        // the menu bar and the Dock name the app after this; without it a jar run says "java"
        if (chatsgate.whatsappfx.wire.Os.MAC) System.setProperty("apple.awt.application.name", "WhatsappFx");
        App.main(args);
    }

    /**
     * One whatsappfx at a time: a second launch asks the running one to show its chats and ends. Without this the second
     * one would find the index locked and die after the window had flashed up.
     */
    static boolean showRunning(String[] args) {
        try {
            var running = DaemonClient.ifRunning();
            if (running.isEmpty()) return false;
            if (!java.util.List.of(args).contains("--background")) running.get().callRaw("show", Map.of());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void help() {
        // Explicitly UTF-8, not System.out: under PowerShell the default console encoding turned every
        // dash in this text into a replacement character.
        var out = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8);
        out.println("""
                WhatsappFx — WhatsApp on this PC. Its window is the chats; it lives in the system tray and keeps
                receiving when the window is closed. It holds the ArcadeDB index, the WhatsApp session and the
                write gate, and serves whatsappcli, which needs it running.

                  whatsappfx                start it and open the chats (if running: just open the chats)
                  whatsappfx --background   start it in the tray only (how start-at-logon runs it)
                  whatsappcli status                 check it is up and linked
                  whatsappcli quit                   stop it cleanly, from anywhere

                Chats window: F1 settings, F2 search, F3 write, F5 refresh, F8 older messages,
                F11 maximize, Esc back / close. Enter opens a chat, then sends; Shift+Enter new line.

                Settings window (F1, or the tray menu): this PC's link, approved programs, attachments, and
                  F3 link by code (type the number first)   F4 link by QR
                  F5 programs approved to use whatsappcli    F6 revoke all approvals
                  F7 start at logon on/off                  F8 copy the state
                  F10 unlink this PC (press it twice)

                Tray: left click opens the chats (with several numbers it asks which one, or all); right click opens the menu
                (a number's chats and settings, approved programs, sound, test notification, quit). A notice for a new
                message or call opens that chat when clicked.

                Several numbers: each has its own window, name and colour (settings, "This number": F2 name, F3 colour,
                F4 add another number). whatsappcli --account <name> picks one; 'whatsappcli accounts' lists them.

                Linking: phone, WhatsApp, Linked devices, Link a device (or "Link with phone number
                instead" for F3). A linked device left offline about a fortnight is unlinked by WhatsApp
                and must be linked again, which costs another full history sync, so leave start at logon on.

                Everything else is done through whatsappcli. Run 'whatsappcli --help'.

                Data lives in ~\\chatsgate\\whatsappfx (config, journals, index, log, connector);
                attachments go to the folder set in the settings window (default: media under that folder).

                After stopping it, wait ~15s before starting it again: closing the index can take
                that long, and a second instance cannot open it while the first still holds the lock.
                """);
    }
}
