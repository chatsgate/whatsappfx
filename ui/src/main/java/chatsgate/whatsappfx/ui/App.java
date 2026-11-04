package chatsgate.whatsappfx.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import leasegate.core.IdleTrim;
import leasegate.core.access.PassphraseGate;
import leasegate.frame.Brand;
import leasegate.frame.GateIcons;
import leasegate.ui.FxApproval;
import chatsgate.whatsappfx.core.Activity;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.DaemonMain;
import chatsgate.whatsappfx.daemon.Accounts;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.daemon.WaRules;
import chatsgate.whatsappfx.ui.chat.ChatWindow;
import chatsgate.whatsappfx.ui.chat.Live;
import chatsgate.whatsappfx.ui.link.LinkWindow;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * whatsappfx: this PC's WhatsApp, resident. It hosts the daemon in-process (one index lock, one WhatsApp session, the
 * approval of programs that use whatsappcli, and the loopback service whatsappcli talks to), lives in the tray, and its window is the chats. Settings and the
 * linking of this PC are a second window, SettingsWindow. {@code --background} starts it in the tray only, which is
 * how the logon shortcut starts it.
 */
public final class App extends Application {

    /** WhatsApp-like green, a shade darker than the icon so white text on the band reads. */
    public static final Brand BRAND = new Brand("WhatsappFx", "#12866a", new GateIcons(App.class, "whatsappfx"), true);

    /** The approval window never asks for a passphrase: a person approves a program with F2, and that is all. */
    public static final PassphraseGate NO_PASSPHRASE = PassphraseGate.external(s -> false);

    private Accounts accounts;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage unused) throws Exception {
        accounts = DaemonMain.start();
        for (var f : new String[]{"Roboto-Regular", "Roboto-Bold"})
            try (var in = App.class.getResourceAsStream("/chatsgate/whatsappfx/ui/fonts/" + f + ".ttf")) { javafx.scene.text.Font.loadFont(in, 14); } catch (Exception e) { Log.warn("font " + f + ": " + e.getMessage()); }
        // The green window wears the colour (and the icon) of the number that is asked about: one window per colour, made when first needed.
        var rules = new WaRules();
        var windows = new java.util.concurrent.ConcurrentHashMap<String, FxApproval<chatsgate.whatsappfx.daemon.WaScope>>();
        accounts.callers.approval(req -> {
            var hub = hubOf(req.requested().account());
            return windows.computeIfAbsent(hub.colour(), c -> new FxApproval<>(NO_PASSPHRASE, rules, AccountBrand.of(c))).decide(req);
        });
        if (testApprove()) {
            // CI only: see testApprove. A real person is never skipped, because this needs a scratch home AND the variable.
            accounts.callers.approval(req -> java.util.Optional.of(new leasegate.core.access.Approval.Decision<>(req.chain().get(0), 60, 480, req.requested())));
            Log.warn("TEST MODE: every program is approved automatically (scratch home, WHATSAPPFX_TEST_APPROVE)");
        }
        // So `whatsappcli quit` unwinds the FX lifecycle rather than halting the VM under it.
        accounts.quitHook = () -> Platform.runLater(Platform::exit);
        // A second launch finds this one running and asks it to come forward (Launcher): with several numbers, which one.
        accounts.showHook = () -> Platform.runLater(Tray::chooseCentred);
        accounts.onAdded = h -> Platform.runLater(() -> { wire(h); Look.restyleAll(); LinkWindow.show(h); });
        accounts.onDuplicate = (h, other) -> Platform.runLater(() -> Tray.warn("Same phone number in two windows", h.title() + " and " + other.title() + " are both +" + h.ownUser() + ". Fine for a test; on the command line they need --account.", () -> SettingsWindow.showNumber(h)));
        accounts.onChanged = h -> Platform.runLater(() -> Look.restyle(h));
        accounts.onRemoved = h -> Platform.runLater(() -> { ChatWindow.close(h); Look.restyleAll(); });
        Tray.install(accounts);
        if (chatsgate.whatsappfx.core.KeyStore.createdThisRun()) Platform.runLater(RecoveryCodeDialog::show);
        for (var h : accounts.all()) wire(h);
        watch();
        IdleTrim.start("whatsappfx", Activity::last, Duration.ofMinutes(2));
        if (getParameters().getRaw().contains("--background")) trimWhenHidden();
        else if (accounts.count() == 1) ChatWindow.show(accounts.primary());
        else Tray.chooseCentred();
        Log.info("whatsappfx running");
    }

    /**
     * Two minutes after a window closes, give the memory back if none is open again. Not left to IdleTrim's own idle
     * test: WhatsApp traffic keeps the app busy all day, so that test alone would never trim (measured 2026-10-27:
     * 299 MB working set 150 s after the chats closed, against 28 MB once trimmed).
     */
    public static void trimWhenHidden() {
        java.util.concurrent.CompletableFuture.delayedExecutor(2, TimeUnit.MINUTES).execute(() -> Platform.runLater(() -> {
            if (ChatWindow.anyOpen() || SettingsWindow.isOpen() || LinkWindow.isOpen()) return;
            Thread.ofVirtual().start(() -> { IdleTrim.trim(); Log.info("trimmed memory: no window open"); });
        }));
    }

    /**
     * The Mac CI job (ci/mac-smoke.sh) has nobody to press F2, so with WHATSAPPFX_TEST_APPROVE=1 AND a WHATSAPPFX_HOME that is not the default
     * home, the approval window is replaced by an automatic yes. On a real person's machine the home is the default one, so this is never on.
     */
    static boolean testApprove() {
        var home = System.getenv(chatsgate.whatsappfx.wire.Paths2.HOME_ENV);
        return "1".equals(System.getenv("WHATSAPPFX_TEST_APPROVE")) && home != null && !home.isBlank()
                && !java.nio.file.Path.of(home).equals(java.nio.file.Path.of(System.getProperty("user.home"), "chatsgate", "whatsappfx"));
    }

    /** The number a scope names: by its phone number once linked, else by its key; the first when none does. */
    Hub hubOf(String scopeAccount) {
        return accounts.all().stream().filter(h -> h.account.equals(scopeAccount) || (h.ownUser() != null && ("+" + h.ownUser()).equals(scopeAccount))).findFirst().orElse(accounts.primary());
    }

    /** What one number's events do in the app: its windows, its notices, its sound. Once per number, on the FX thread. */
    void wire(Hub hub) {
        hub.showHook = () -> Platform.runLater(() -> ChatWindow.show(hub));
        hub.showSettingsHook = () -> Platform.runLater(() -> SettingsWindow.show(hub));
        hub.showChatHook = chat -> Platform.runLater(() -> ChatWindow.show(hub, chat));
        hub.draftHook = (chat, text) -> Platform.runLater(() -> ChatWindow.draft(hub, chat, text));
        hub.cliNoticeHook = (title, text) -> Platform.runLater(() -> chatsgate.whatsappfx.ui.notify.Notices.message(hub, title, text, null));
        hub.onLinkLost = () -> Tray.linkLost(hub);
        hub.onCall = d -> chatsgate.whatsappfx.ui.notify.Notices.call(hub, d);
        hub.testNoticeHook = kind -> Platform.runLater(() -> { if ("call".equals(kind)) Tray.testCall(hub); else Tray.testMessage(hub); });
        Live.install(hub, (title, text, chat) -> {
            chatsgate.whatsappfx.ui.notify.Notices.message(hub, title, text, chat);
            Sound.chime();
        });
    }

    /**
     * Something a person must hear about while the window is closed: once, shortly after start, a number that is not linked at all.
     */
    void watch() {
        var ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("wa-watch").factory());
        ticker.schedule(() -> {
            for (var h : accounts.all()) if (Boolean.TRUE.equals(h.status().get("needsPairing"))) Platform.runLater(() -> {
                var which = accounts.count() > 1 ? h.title() + ": " : "";
                Tray.tooltip(Tray.TIP + " - " + which + "NOT LINKED");
                Tray.warn(which + "WhatsappFx is not linked to WhatsApp", "Nothing arrives until this number is linked. Click to link it.", () -> LinkWindow.show(h));
                LinkWindow.show(h);
            });
        }, 20, TimeUnit.SECONDS);
    }

    /**
     * Exiting JavaFX is not exiting the process (AWT's tray thread is not a daemon), and System.exit would run shutdown
     * hooks that block: the connector's re-enters its own synchronized stop(), and ArcadeDB adds its own. The orderly
     * work is done by hub.shutdown(), so halt after it; the quit watchdog covers the case this line is never reached.
     */
    @Override
    public void stop() {
        Log.info("fx stop: removing tray");
        Tray.remove();
        if (accounts != null) accounts.shutdown();
        Log.info("fx stop: halting");
        Runtime.getRuntime().halt(0);
    }
}
