package chatsgate.whatsappfx.core;

import com.google.gson.JsonObject;
import chatsgate.whatsappfx.wire.DaemonClient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The Go connector as a child process, spoken to in NDJSON over its stdin and stdout.
 *
 * <p>Everything protocol-shaped lives on the far side of this pipe and everything policy-shaped on
 * this side. The cost of that split is this class; the benefit is that a crash in the WhatsApp stack
 * is a restart here rather than a dead JVM with the index open.
 */
public final class Connector {

    private final ConnectorConfig cfg;
    private final Consumer<JsonObject> onEvent;
    private final Consumer<Integer> onExit;

    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private volatile Process proc;
    private volatile BufferedWriter in;

    /**
     * Set while we are the ones killing the child, so the supervisor does not treat a deliberate stop
     * as a crash. Without it, {@code stop()} destroyed the process, the exit watcher reported it as a
     * death, and the restarter started a fresh connector — on the way out of the app, which left the
     * JVM alive with the index already closed and a brand-new child attached to it.
     */
    private volatile boolean stopping;

    /** Registered once. {@code start()} runs again on every restart, and hooks do not un-register. */
    private volatile boolean hookInstalled;

    public Connector(ConnectorConfig cfg, Consumer<JsonObject> onEvent, Consumer<Integer> onExit) {
        this.cfg = cfg;
        this.onEvent = onEvent;
        this.onExit = onExit;
    }

    public synchronized void start() throws IOException {
        if (alive()) return;
        if (stopping) throw new IOException("connector is stopping");
        if (chatsgate.whatsappfx.wire.Os.MAC) prepareForMac(cfg.exe());
        var pb = new ProcessBuilder(cfg.command());
        pb.redirectErrorStream(false);
        // The session store's key, in this child's environment only. No key, no start: a connector
        // without it would open an empty plain store and ask to pair again.
        try {
            pb.environment().put("WHATSAPPFX_DB_KEY", KeyStore.keyHex());
        } catch (RuntimeException e) {
            throw new IOException("session key unavailable: " + e.getMessage(), e);
        }
        proc = pb.start();
        in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        if (!hookInstalled) {
            hookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        }
        Thread.ofVirtual().name("wa-connector-out").start(this::readOut);
        Thread.ofVirtual().name("wa-connector-err").start(this::readErr);
        Thread.ofVirtual().name("wa-connector-wait").start(this::awaitExit);
    }

    /**
     * A connector that was downloaded or copied in carries the quarantine flag (Gatekeeper refuses to run it: "cannot be opened because the
     * developer cannot be verified") and may have lost its execute bit. Both are put right, best-effort. Needs a Mac to prove.
     */
    private static void prepareForMac(java.nio.file.Path exe) {
        try {
            exe.toFile().setExecutable(true);
            new ProcessBuilder("xattr", "-d", "com.apple.quarantine", exe.toString()).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.warn("connector not prepared for macOS: " + e.getMessage());
        }
    }

    public boolean alive() {
        var p = proc;
        return p != null && p.isAlive();
    }

    public synchronized void stop() {
        stopping = true;
        var p = proc;
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    public JsonObject call(String cmd, Object args, long timeoutSeconds) throws IOException {
        try {
            return callAsync(cmd, args).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("connector '" + cmd + "': " + e.getMessage(), e);
        }
    }

    public CompletableFuture<JsonObject> callAsync(String cmd, Object args) throws IOException {
        if (!alive()) throw new IOException("connector is not running");
        var id = Long.toString(seq.incrementAndGet());
        var future = new CompletableFuture<JsonObject>();
        pending.put(id, future);
        var req = new JsonObject();
        req.addProperty("id", id);
        req.addProperty("cmd", cmd);
        req.add("args", DaemonClient.GSON.toJsonTree(args == null ? Map.of() : args));
        synchronized (this) {
            in.write(DaemonClient.GSON.toJson(req));
            in.write('\n');
            in.flush();
        }
        return future;
    }

    private void readOut() {
        try (var r = proc.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) route(line);
        } catch (IOException e) {
            Log.warn("connector stdout closed: " + e.getMessage());
        }
    }

    private void route(String line) {
        try {
            var o = DaemonClient.GSON.fromJson(line, JsonObject.class);
            if (o == null) return;
            if (o.has("event")) {
                onEvent.accept(o);
                return;
            }
            var f = pending.remove(o.get("id").getAsString());
            if (f != null) f.complete(o);
        } catch (Exception e) {
            Log.warn("unparseable connector line: " + e.getMessage());
        }
    }

    private void readErr() {
        try (var r = proc.errorReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) Log.info("[connector] " + line);
        } catch (IOException ignored) {
        }
    }

    private void awaitExit() {
        try {
            var code = proc.waitFor();
            pending.values().forEach(f -> f.completeExceptionally(new IOException("connector exited")));
            pending.clear();
            // A child we killed is not a child that crashed. Reporting it as one restarts it.
            if (!stopping) onExit.accept(code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
