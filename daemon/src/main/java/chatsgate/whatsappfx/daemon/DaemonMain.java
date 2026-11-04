package chatsgate.whatsappfx.daemon;

import io.javalin.Javalin;
import chatsgate.whatsappfx.core.Activity;
import chatsgate.whatsappfx.core.Config;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.DaemonClient;
import chatsgate.whatsappfx.wire.DaemonHandshake;
import chatsgate.whatsappfx.wire.Paths2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

/**
 * The resident process: it holds the index lock, drives the connector and answers the CLI.
 *
 * <p>Runs headless with {@code --serve}; the desktop app starts the same thing in-process and adds a
 * window to it.
 */
public final class DaemonMain {

    public static final String VERSION = "1.0";

    public static void main(String[] args) throws Exception {
        Log.echo = true;
        var accounts = start();
        Runtime.getRuntime().addShutdownHook(new Thread(accounts::shutdown));
        Thread.currentThread().join();
    }

    public static Accounts start() throws Exception {
        Files.createDirectories(Paths2.home());
        var accounts = new Accounts(Config.load());
        var token = Tokens.random();
        var app = Javalin.create(cfg -> cfg.showJavalinBanner = false);
        app.before(ctx -> {
            Activity.touch();
            if (!token.equals(ctx.header("X-Wa-Token"))) {
                ctx.status(401).result("{\"error\":\"bad token\"}");
                ctx.skipRemainingHandlers();
            }
        });
        new Verbs(accounts).mount(app);
        app.start("127.0.0.1", 0);
        writeHandshake(app.port(), token);
        Log.info("daemon listening on 127.0.0.1:" + app.port());
        accounts.start();
        return accounts;
    }

    private static void writeHandshake(int port, String token) throws Exception {
        var h = new DaemonHandshake(port, token, ProcessHandle.current().pid(),
                Instant.now().toString(), VERSION);
        Files.writeString(Paths2.daemonFile(), DaemonClient.GSON.toJson(h), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(Paths2.daemonFile(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windows) {
            // no POSIX permissions here: the file stays as the user's own folder has it
        }
    }
}
