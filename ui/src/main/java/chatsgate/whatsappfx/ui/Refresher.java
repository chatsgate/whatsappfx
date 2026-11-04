package chatsgate.whatsappfx.ui;

import javafx.application.Platform;
import javafx.stage.Stage;
import leasegate.core.IdleTrim;
import chatsgate.whatsappfx.core.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs a UI refresh every two seconds while the window is showing, and nothing at all while it is
 * hidden, which for a tray app is nearly all of the time. A ticker behind a hidden window keeps
 * pulling trimmed pages back in (PRP 08). The scheduler thread exists only while the window shows.
 */
final class Refresher {

    final Runnable body;
    ScheduledExecutorService ticker;

    Refresher(Stage stage, Runnable body) {
        this.body = body;
        stage.showingProperty().addListener((o, was, showing) -> { if (showing) start(); else stop(); });
        body.run();
        if (stage.isShowing()) start();
    }

    synchronized void start() {
        if (ticker != null) return;
        body.run();
        ticker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("wa-ui-refresh").factory());
        ticker.scheduleWithFixedDelay(() -> Platform.runLater(body), 2, 2, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (ticker == null) return;
        ticker.shutdownNow();
        ticker = null;
        // The window's scene graph and the status scans are garbage now; give the memory back at once.
        Thread.ofVirtual().start(() -> { IdleTrim.trim(); Log.info("trimmed memory after the window was hidden"); });
    }
}
