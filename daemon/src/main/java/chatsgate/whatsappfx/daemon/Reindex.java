package chatsgate.whatsappfx.daemon;

import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.DaemonClient;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the index from the journal, which is what makes "the journal is the truth" a fact rather
 * than a claim. Until this existed, a damaged index meant the data was still on disk and nothing
 * could read it back.
 *
 * <p>Additive: rows are upserted, nothing is deleted. Two things do not live in the journal and so do
 * not come back here — per-chat attachment policy and chat names. Policy is re-stated with
 * {@code policy --set}, names with {@code names}.
 */
public final class Reindex {

    private Reindex() {}

    public static Map<String, Object> start(Hub hub) {
        var files = journalFiles(hub);
        var jobId = hub.jobs.create("reindex", files.size() + " journal file(s)", 0);
        Thread.ofVirtual().name("wa-reindex").start(() -> run(hub, jobId, files));
        var out = new LinkedHashMap<String, Object>();
        out.put("job", jobId);
        out.put("files", files.size());
        return out;
    }

    private static List<Path> journalFiles(Hub hub) {
        var out = new ArrayList<Path>();
        try (var s = Files.list(hub.journal.dir())) {
            s.filter(p -> p.getFileName().toString().endsWith(".ndjson")).sorted().forEach(out::add);
        } catch (Exception e) {
            Log.error("cannot list the journal", e);
        }
        return out;
    }

    private static void run(Hub hub, String jobId, List<Path> files) {
        long lines = 0;
        try {
            // Total is set from the line count rather than the file count, so progress means messages.
            for (var f : files) lines += countLines(f);
            hub.jobs.setTotal(jobId, lines);
            for (var f : files) replay(hub, jobId, f);
        } catch (Exception e) {
            Log.error("reindex failed", e);
            hub.jobs.finish(jobId, String.valueOf(e.getMessage()));
            return;
        }
        // A run whose every line was unusable would otherwise sit at running forever.
        hub.jobs.finish(jobId, null);
    }

    private static long countLines(Path f) throws Exception {
        long n = 0;
        try (var in = new BufferedInputStream(Files.newInputStream(f), 1 << 16)) {
            int b;
            while ((b = in.read()) != -1) if (b == '\n') n++;
        }
        return n;
    }

    /**
     * Offsets are recomputed exactly as the writer produced them — the UTF-8 length of the line plus
     * one for the newline — because {@code joff} is how a message body is read back out of here and an
     * offset that is off by a byte points at nothing.
     */
    private static void replay(Hub hub, String jobId, Path f) throws Exception {
        long at = 0;
        try (var in = new BufferedInputStream(Files.newInputStream(f), 1 << 16)) {
            var buf = new ByteArrayOutputStream(4096);
            int b;
            while ((b = in.read()) != -1) {
                if (b != '\n') {
                    buf.write(b);
                    continue;
                }
                var raw = buf.toByteArray();
                buf.reset();
                var start = at;
                at += raw.length + 1;
                index(hub, jobId, new String(raw, StandardCharsets.UTF_8), start);
            }
            // A journal whose last line has no newline yet: still a message, still indexable.
            if (buf.size() > 0) index(hub, jobId, new String(buf.toByteArray(), StandardCharsets.UTF_8), at);
        }
    }

    private static void index(Hub hub, String jobId, String line, long offset) {
        if (line.isBlank()) {
            hub.jobs.advance(jobId, "skipped", 1, 0);
            return;
        }
        try {
            var data = DaemonClient.GSON.fromJson(line, JsonObject.class);
            if (hub.ingest.indexRow(data, offset)) hub.jobs.advance(jobId, "done", 1, line.length());
            else hub.jobs.advance(jobId, "skipped", 1, 0);
        } catch (Exception e) {
            hub.jobs.advance(jobId, "failed", 1, 0);
        }
    }
}
