package chatsgate.whatsappfx.ui;

import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import leasegate.frame.TaskbarTheme;
import leasegate.tray.TrayMenu;
import chatsgate.whatsappfx.ui.link.LinkWindow;
import littlejlib.traynotify.BalloonSender;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Accounts;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.daemon.Palette;
import chatsgate.whatsappfx.ui.chat.ChatWindow;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The tray: ONE icon for every number: a left click opens the chats (with several numbers, it asks
 * which one, or all), a right click opens the menu leasegate draws with JavaFX (in the app's colour, with its state on top), and a
 * notice that is clicked opens what it is about. The icon follows the taskbar between its light and dark sets. Every callback lands
 * on the FX thread.
 */
public final class Tray {

    static final long NOTICE_CLICK_MS = 30_000;
    static final String TIP = "WhatsappFx - WhatsApp";

    private static Tray tray;
    /** Every number, kept even when there is no tray, so a click on the Dock icon or a second launch can still ask which to open. */
    private static Accounts known;

    final Accounts accounts;
    final TrayIcon icon;
    final TrayMenu menu, chooser;
    volatile Runnable noticeAction;
    volatile long noticeAt;

    private Tray(Accounts accounts) {
        this.accounts = accounts;
        this.menu = new TrayMenu(App.BRAND.icon(), App.BRAND.product(), App.BRAND.keepCase(), App.BRAND.color(), this::headline, this::items);
        this.chooser = new TrayMenu(App.BRAND.icon(), App.BRAND.product(), App.BRAND.keepCase(), App.BRAND.color(), () -> "Which number?", this::choices);
        this.icon = new TrayIcon(App.BRAND.icons().tray(TaskbarTheme.readDark()), TIP);
        icon.setImageAutoSize(true);
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                double x = e.getXOnScreen(), y = e.getYOnScreen();
                if (e.getButton() == MouseEvent.BUTTON1) Platform.runLater(() -> choose(x, y));
                else if (e.getButton() == MouseEvent.BUTTON3 || e.isPopupTrigger()) Platform.runLater(() -> menu.toggle(x, y));
            }
        });
        // AWT fires this for a click on a notice (and a double-click on the icon): a notice of the last half
        // minute is taken to be the one clicked. A double-click on the icon is two clicks, which have asked already.
        icon.addActionListener(e -> {
            var a = noticeAction;
            noticeAction = null;
            if (a != null && System.currentTimeMillis() - noticeAt < NOTICE_CLICK_MS) Platform.runLater(a);
        });
    }

    /** FX thread, once. A desktop without a tray still gets the window; it just cannot hide to anywhere. */
    public static void install(Accounts accounts) {
        known = accounts;
        // The app lives on without a window: its tray icon (on a Mac the menu bar, and the Dock icon) is the way back to it.
        Platform.setImplicitExit(false);
        Dock.install();
        if (!SystemTray.isSupported()) { Log.warn("no system tray on this desktop: the Dock icon (Mac) or a second launch brings the windows back"); return; }
        try {
            tray = new Tray(accounts);
            SystemTray.getSystemTray().add(tray.icon);
            tray.menu.prewarm();
            TaskbarTheme.darkProperty().addListener((o, was, dark) -> tray.icon.setImage(App.BRAND.icons().tray(dark)));
        } catch (Exception e) {
            Log.warn("tray unavailable: " + e.getMessage());
            tray = null;
        }
    }

    /** What the tray says about one number whose link WhatsApp ended. */
    public static void linkLost(Hub hub) {
        var which = hub.accounts.count() > 1 ? hub.title() + ": " : "";
        tooltip(TIP + " - " + which + "NOT LINKED");
        warn(which + "WhatsApp unlinked this PC", "Nothing new arrives until it is linked again. Click to link.", () -> LinkWindow.show(hub));
    }

    /** The one line on top of the tray menu: one number says how it stands, several say how each does. */
    String headline() {
        var all = accounts.all();
        if (all.size() == 1) return SettingsWindow.headline(all.getFirst());
        var sb = new StringBuilder();
        for (var h : all) {
            var s = h.status();
            sb.append(sb.isEmpty() ? "" : "  ·  ").append(h.title()).append(Boolean.TRUE.equals(s.get("loggedIn")) ? (s.get("connectedSince") == null ? " offline" : " online") : " NOT LINKED");
        }
        return sb.toString();
    }

    /** What a click on the icon asks, with several numbers: which one to open, or all of them. */
    List<MenuItem> choices() {
        var out = new ArrayList<MenuItem>();
        for (var h : accounts.all()) out.add(item("Open  " + h.title() + "   ·   " + Palette.nameOf(h.colour()) + (h.ownUser() == null ? "   ·   not linked" : "   ·   +" + h.ownUser()), () -> ChatWindow.show(h)));
        out.add(new SeparatorMenuItem());
        out.add(item("Open all", Tray::openAll));
        return out;
    }

    /** One number: its chats at once. Several: the question, at the icon; {@code (x, y)} is where the icon is. */
    public static void choose(double x, double y) {
        if (known == null) return;
        if (known.count() == 1) ChatWindow.show(known.primary());
        else if (tray != null) tray.chooser.show(x, y);
        else openAll();
    }

    /** The same question without an icon to point at (a second launch of the app, or the app's own start): the middle of the screen. */
    public static void chooseCentred() {
        var b = javafx.stage.Screen.getPrimary().getVisualBounds();
        choose(b.getMinX() + b.getWidth() / 2 + 150, b.getMinY() + b.getHeight() / 2 + 150);
    }

    public static void openAll() {
        if (known == null) return;
        for (var h : known.all()) ChatWindow.show(h);
    }

    List<MenuItem> items() {
        var out = new ArrayList<MenuItem>();
        var all = accounts.all();
        var first = all.getFirst();
        if (all.size() == 1) {
            out.add(item("Open chats", () -> ChatWindow.show(first)));
            out.add(item("Settings…", () -> SettingsWindow.show(first)));
            out.add(item("Link a device…", () -> LinkWindow.show(first), Boolean.TRUE.equals(first.status().get("loggedIn"))));
        } else {
            out.add(item("Open all chats", Tray::openAll));
            for (var h : all) out.add(item("Open  " + h.title(), () -> ChatWindow.show(h)));
            for (var h : all) out.add(item("Settings  -  " + h.title() + "…", () -> SettingsWindow.show(h)));
            for (var h : all) if (!Boolean.TRUE.equals(h.status().get("loggedIn"))) out.add(item("Link  " + h.title() + "…", () -> LinkWindow.show(h)));
        }
        out.add(item("Add another number…", () -> SettingsWindow.showNumber(first), all.size() >= Accounts.MAX));
        out.add(OfficialApp.running()
                ? item("Close WhatsApp app (gives its memory back)", () -> Thread.ofVirtual().start(OfficialApp::close))
                : item("Open WhatsApp app", () -> OfficialApp.open(null)));
        out.add(new SeparatorMenuItem());
        out.add(item(accounts.walletOpen() ? "Command-line access (WALLET OPEN)…" : "Command-line access…", () -> CliAccessWindow.show(first)));
        out.add(item("Programs approved to use whatsappcli…", () -> new leasegate.ui.AccessListWindow<>(accounts.callers.engine, App.NO_PASSPHRASE, App.BRAND).show()));
        out.add(item(accounts.callers.approvals() == 0 ? "No program approvals to revoke" : "Revoke every program approval (" + accounts.callers.approvals() + ")", accounts.callers::revokeAll, accounts.callers.approvals() == 0));
        out.add(item(Sound.on() ? "Sound for new messages: on" : "Sound for new messages: off", () -> Sound.on(!Sound.on())));
        out.add(item(Sound.ringOn() ? "Ring for incoming calls: on" : "Ring for incoming calls: off", () -> Sound.ringOn(!Sound.ringOn())));
        out.add(item("Test notification", () -> testMessage(first)));
        out.add(item("Test call notification", () -> testCall(first)));
        out.add(new SeparatorMenuItem());
        out.add(item("Quit WhatsappFx (stops receiving)", Platform::exit));
        return out;
    }

    static MenuItem item(String text, Runnable action) {
        return item(text, action, false);
    }

    static MenuItem item(String text, Runnable action, boolean disabled) {
        var m = new MenuItem(text);
        m.setOnAction(e -> action.run());
        m.setDisable(disabled);
        return m;
    }

    static void testMessage(Hub hub) {
        chatsgate.whatsappfx.ui.notify.Notices.message(hub, "Test notification",
                "This is how a new message appears. Type a reply in a real one; F5 opens the chat.", null);
        Sound.chime();
    }

    /** A ringing call card with no chat behind it: to see and hear it. F4, F6, Esc or the close button stop it. */
    static void testCall(Hub hub) {
        var d = new com.google.gson.JsonObject();
        var call = new com.google.gson.JsonObject();
        call.addProperty("callId", "test-" + System.currentTimeMillis());
        call.addProperty("state", "offer");
        d.add("call", call);
        d.addProperty("fromMe", false);
        d.addProperty("sender", "test@s.whatsapp.net");
        Thread.ofVirtual().start(() -> chatsgate.whatsappfx.ui.notify.Notices.call(hub, d));
    }

    void show(String title, String text, TrayIcon.MessageType type, Runnable onClick) {
        noticeAction = onClick;
        noticeAt = System.currentTimeMillis();
        icon.displayMessage(title, text, type);
        if (chatsgate.whatsappfx.wire.Os.WINDOWS) BalloonSender.name("WhatsappFx", () -> Tray.class.getResourceAsStream("whatsappfx-256.png")); // names the sender of the Windows balloon
    }

    /** A notice that opens {@code onClick} (FX thread) when clicked. Quietly nothing without a tray. */
    public static void notice(String title, String text, Runnable onClick) {
        if (tray != null) tray.show(title, text, TrayIcon.MessageType.INFO, onClick);
    }

    public static void warn(String title, String text, Runnable onClick) {
        if (tray != null) tray.show(title, text, TrayIcon.MessageType.WARNING, onClick);
    }

    public static void tooltip(String text) {
        if (tray != null) tray.icon.setToolTip(text);
    }

    /** Called on the way out: an icon left behind is a dead app still advertising itself. */
    public static void remove() {
        if (tray == null) return;
        try {
            tray.menu.close();
            tray.chooser.close();
            SystemTray.getSystemTray().remove(tray.icon);
        } catch (Exception e) {
            Log.warn("tray icon not removed: " + e.getMessage());
        }
        tray = null;
    }
}
