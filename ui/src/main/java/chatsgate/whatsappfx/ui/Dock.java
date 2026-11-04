package chatsgate.whatsappfx.ui;

import javafx.application.Platform;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Os;

import java.awt.Desktop;
import java.awt.Taskbar;

/**
 * What a Mac needs beyond the tray: the Dock icon shows our mark rather than Java's cup, a click on the Dock icon when no window is
 * open asks which number to open, and Cmd+Q stops the app the way the tray's Quit does. The Dock icon is also the way back when the
 * menu-bar icon is hidden (a notch, or too many menu-bar items) or no tray is available. Every call is best-effort: nothing here may
 * stop the app from starting. Not a Mac: does nothing. NOT run on a Mac yet.
 */
final class Dock {

    private Dock() {}

    static void install() {
        if (!Os.MAC) return;
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
                try (var in = Dock.class.getResourceAsStream("whatsappfx-256.png")) { Taskbar.getTaskbar().setIconImage(javax.imageio.ImageIO.read(in)); }
            if (Desktop.isDesktopSupported()) {
                var d = Desktop.getDesktop();
                if (d.isSupported(Desktop.Action.APP_EVENT_REOPENED)) d.addAppEventListener((java.awt.desktop.AppReopenedListener) e -> Platform.runLater(Tray::chooseCentred));
                if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler((e, response) -> { response.cancelQuit(); Platform.runLater(Platform::exit); });
            }
        } catch (Throwable t) {
            Log.warn("Dock integration not set up: " + t);
        }
    }
}
