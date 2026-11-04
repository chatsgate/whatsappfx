package chatsgate.whatsappfx.core;

import chatsgate.whatsappfx.wire.Paths2;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * The append-only truth. One NDJSON file per chat, one message per line, in arrival order.
 *
 * <p>The index is a rebuildable view over this, never the other way round: a corrupted database is
 * then a re-index rather than a data loss, and the file is greppable without any of our code.
 * Appends return the byte offset they wrote at, which is how the index gets a message body back
 * without storing it twice.
 */
public final class Journal {

    private final Path dir;
    private final Map<String, Long> sizes = new HashMap<>();

    public Journal(String accountId) {
        this.dir = Paths2.journalDir(accountId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create journal at " + dir, e);
        }
    }

    public Path fileFor(String chatJid) {
        return dir.resolve(Slug.userPart(chatJid) + "-" + Slug.shortHash(chatJid) + ".ndjson");
    }

    /** Appends one line and returns the offset it starts at. */
    public synchronized long append(String chatJid, String jsonLine) throws IOException {
        var f = fileFor(chatJid);
        var at = sizes.computeIfAbsent(f.toString(), k -> {
            try {
                return Files.exists(f) ? Files.size(f) : 0L;
            } catch (IOException e) {
                return 0L;
            }
        });
        var bytes = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(f, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        sizes.put(f.toString(), at + bytes.length);
        return at;
    }

    /** Reads back one line by the offset {@link #append} returned. */
    public String lineAt(String chatJid, long offset) throws IOException {
        var f = fileFor(chatJid);
        if (!Files.exists(f)) return null;
        try (var raf = new RandomAccessFile(f.toFile(), "r")) {
            raf.seek(offset);
            var line = raf.readLine();
            return line == null ? null : new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
    }

    public Path dir() {
        return dir;
    }
}
