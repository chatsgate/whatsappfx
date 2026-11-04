package chatsgate.whatsappfx.core;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import chatsgate.whatsappfx.core.model.WaChat;
import chatsgate.whatsappfx.core.model.WaContact;
import chatsgate.whatsappfx.core.model.WaMessage;
import chatsgate.whatsappfx.core.model.WaJob;
import chatsgate.whatsappfx.wire.Paths2;
import datapotter.arcadedbhelper.InitDoc;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the embedded index. Only the daemon constructs it: an embedded database takes a single-writer
 * lock on its directory, which is the reason the daemon exists rather than a limitation to route
 * around.
 *
 * <p>Opened through {@link DatabaseFactory} rather than by starting an {@code ArcadeDBServer}, which
 * is what this used to do. The server was never used for anything — every read goes through
 * {@code Sql} in this process — and it costs a full Undertow stack: two listening sockets (an HTTP
 * one and an HTTPS one, chosen from 2480-2489 and 2490-2499, so which port a WhatsApp archive
 * answered on depended on what else on this machine had already claimed 2480), thirteen more threads,
 * a plugin manager, a security realm needing a root password invented for it, and about a second of
 * startup. Measured: 23 threads and 1535 ms with the server, 10 threads and 603 ms without.
 */
public final class IndexDb {

    private static final String DB_NAME = "wa_index";

    /**
     * ArcadeDB's page cache, in MB.
     *
     * <p>Named explicitly because the {@code low-ram} profile below sets it to 16 MB, and the index on
     * this machine is 24 MB — so the cache could not hold the data it was caching, and every listing
     * verb re-read pages off disk and re-materialised them on the heap. That was the larger half of
     * the garbage this process produced. Left uncapped instead, ArcadeDB derives it from {@code -Xmx}
     * and asks for a quarter of the heap, which with no {@code -Xmx} at all came to 983 MB. 48 MB holds
     * the whole index twice over and leaves the idle trim little to fight (PRP 08).
     */
    private static final int PAGE_CACHE_MB = 48;

    private static IndexDb instance;

    private final DatabaseFactory factory;
    private final Database database;
    private final AtomicBoolean up = new AtomicBoolean(false);

    private IndexDb() {
        var path = Paths2.indexRoot().resolve("databases").resolve(DB_NAME);
        try {
            Files.createDirectories(path.getParent());
            quietArcadeLog();
            // Order matters: the profile rewrites several settings, so anything of ours has to follow it.
            GlobalConfiguration.PROFILE.setValue("low-ram");
            GlobalConfiguration.MAX_PAGE_RAM.setValue(PAGE_CACHE_MB);
            factory = new DatabaseFactory(path.toString());
            database = factory.exists() ? factory.open() : factory.create();
            InitDoc.initDocTypes(database,
                    WaMessage.TYPEDEF, WaChat.TYPEDEF, WaContact.TYPEDEF, WaJob.TYPEDEF);
            up.set(true);
        } catch (Exception e) {
            throw new IllegalStateException("could not open the index at " + path, e);
        }
    }

    /**
     * ArcadeDB's own logging config has a file handler writing {@code log/arcadedb.log.*} relative to the
     * working directory, so a start from a source folder littered that folder (and those files once got
     * committed). Point java.util.logging at a config of ours instead: warnings to stderr, no files.
     * Must run before the first ArcadeDB class initialises its logger.
     */
    private static void quietArcadeLog() {
        if (System.getProperty("java.util.logging.config.file") != null) return;
        try {
            var f = Paths2.home().resolve("arcadedb-log.properties");
            if (!Files.exists(f)) {
                Files.writeString(f, """
                        handlers = java.util.logging.ConsoleHandler
                        .level = WARNING
                        java.util.logging.ConsoleHandler.level = WARNING
                        """);
            }
            System.setProperty("arcadedb.installCustomFormatter", "false");
            System.setProperty("java.util.logging.config.file", f.toString());
        } catch (Exception e) {
            Log.warn("ArcadeDB log config not written, it may write log/ into the working directory: " + e.getMessage());
        }
    }

    public static synchronized IndexDb open() {
        if (instance == null) instance = new IndexDb();
        return instance;
    }

    public Database db() {
        if (!up.get()) throw new IllegalStateException("index database is not open");
        return database;
    }

    /** Ours to close now, where the server used to own the instance's lifetime. */
    public synchronized void shutdown() {
        if (!up.getAndSet(false)) return;
        try {
            if (database.isOpen()) database.close();
        } finally {
            factory.close();
        }
    }
}
