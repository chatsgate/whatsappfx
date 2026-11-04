package chatsgate.whatsappfx.daemon;

import com.google.gson.JsonObject;
import chatsgate.whatsappfx.core.Activity;
import chatsgate.whatsappfx.core.Json;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.core.MediaPlacer;
import chatsgate.whatsappfx.core.model.WaChat;
import chatsgate.whatsappfx.core.model.WaMessage;
import chatsgate.whatsappfx.wire.DaemonClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static datapotter.arcadedbhelper.Query.query;
import static datapotter.arcadedbhelper.Upsert.upsert;

/** Downloads the attachments policy allows and files them on the shared drive. */
public final class MediaWorker {

    // Three at a time. One is what let a single 333 MB chat-export zip sit in front of a hundred
    // bills; more than a few would only contend for the same Drive-synced disk.
    private static final int WORKERS = 3;

    /** How long a cached tally may stand before it is recomputed regardless. */
    private static final long MAX_TALLY_AGE_NANOS = 60_000_000_000L;

    private final Hub hub;
    private final LinkedBlockingQueue<MediaTask> queue = new LinkedBlockingQueue<>();

    /**
     * The last tally read off the index, and null whenever it is stale.
     *
     * <p>Recomputing it on demand is what made an idle app expensive. The tally is a grouped count over
     * every WaMessage row &mdash; no index covers it, so it is a full scan, about 20 ms and some five
     * thousand row materialisations each time. {@code Hub.status()} carries it, the window rebuilt the
     * status block every two seconds and asked for it twice per rebuild, and nothing else was running:
     * that alone was 40 MB of garbage every two to four seconds, for as long as the app was up. The
     * count changes only when a media row changes, which is the same reasoning Autostart already uses
     * for its own entry in that block.
     */
    private volatile Map<String, Object> tally;
    private volatile long tallyAt;

    private final AtomicLong done = new AtomicLong(), failed = new AtomicLong(),
            expired = new AtomicLong(), oversize = new AtomicLong();

    public MediaWorker(Hub hub) {
        this.hub = hub;
        for (var i = 0; i < WORKERS; i++) Thread.ofVirtual().name("wa-media-" + i).start(this::loop);
    }

    /** Live path: the body is already in hand, so nothing has to be read back off disk. */
    public void consider(JsonObject msg) {
        var media = Json.obj(msg, "media");
        if (media == null) return;
        // A row that has just appeared with an attachment on it changes the not-fetched count.
        tally = null;
        var chat = Json.str(msg, "chat");
        if (chat == null) return;
        // Our own send names the local file it uploaded from. Filing that copy onto the shared drive
        // is a decision this machine already made by sending the file, not a question for per-chat
        // attachment policy or the size cap — both of which exist to bound what a backfill hoovers up
        // from a counterparty, not to second-guess an outbound file we chose to send.
        var sentFrom = Json.str(media, "sentFrom");
        if (sentFrom != null && !sentFrom.isBlank()) {
            markState(chat, Json.str(msg, "id"), "queued");
            queue.add(new MediaTask(null, msg));
            return;
        }
        if (!Policy.allows(hub.index.db(), hub.config, hub.account, chat)) return;
        if (tooBig(msg, null)) return;
        queue.add(new MediaTask(null, msg));
    }

    /**
     * Backfill everything policy allows but nothing has fetched yet, as a job.
     *
     * <p>Switching a chat on governs only messages that arrive afterwards, so without this the
     * attachments already in the index from history sync would never be downloaded.
     */
    public Map<String, Object> backfill(String onlyChat) {
        var pending = pending(onlyChat);
        var jobId = hub.jobs.create("media.backfill",
                onlyChat == null ? "all policy-on chats" : onlyChat, pending.size());
        var queued = 0;
        for (var row : pending) {
            if ("queued".equals(enqueue(row[0], row[1], jobId, false))) queued++;
            else hub.jobs.advance(jobId, "skipped", 1, 0);
        }
        if (pending.isEmpty()) hub.jobs.finish(jobId, null);
        var out = new LinkedHashMap<String, Object>();
        out.put("job", jobId);
        out.put("queued", queued);
        out.put("total", pending.size());
        return out;
    }

    /**
     * Selected with SQL rather than the typed builder: a selection that silently under-reads is how a
     * backfill quietly skips half the work, and the builder under-reads whenever a limit meets a
     * condition its chosen index does not cover. See {@link Sql}.
     */
    private List<String[]> pending(String onlyChat) {
        var chats = new ArrayList<String>();
        if (onlyChat != null) {
            chats.add(onlyChat);
        } else {
            for (var c : query(hub.index.db(), WaChat.TYPEDEF).eq(WaChat.$account, hub.account).list()) {
                if (Policy.allows(hub.index.db(), hub.config, hub.account, c.jid())) chats.add(c.jid());
            }
        }
        var out = new ArrayList<String[]>();
        for (var chat : chats) {
            if (!Policy.allows(hub.index.db(), hub.config, hub.account, chat)) continue;
            var sql = "select chat, msgId from WaMessage where account = '" + esc(hub.account)
                    + "' and chat = '" + esc(chat) + "' and mediaKind is not null"
                    + " and (mediaState is null or mediaState in ['none', 'queued', 'failed'])"
                    + " order by ts desc";
            for (var r : Sql.all(hub.index.db(), sql)) {
                out.add(new String[]{String.valueOf(r.get("chat")), String.valueOf(r.get("msgId"))});
            }
        }
        return out;
    }

    private static String esc(String s) {
        return Sql.esc(s);
    }

    /**
     * A row marked queued by a process that is no longer running is not queued. The queue lives in
     * memory, so on startup nothing can be: anything still saying so is a lie left by a kill, and the
     * repair is the same statement either way.
     */
    public int clearStaleQueued() {
        var stale = Sql.all(hub.index.db(), "select chat, msgId from WaMessage where account = '"
                + esc(hub.account) + "' and mediaState = 'queued'");
        for (var r : stale) {
            markState(String.valueOf(r.get("chat")), String.valueOf(r.get("msgId")), "none");
        }
        tally = null;
        return stale.size();
    }

    /** Single fetch by message id. Ignores the size cap, because naming one file is a decision. */
    public String fetch(String chat, String msgId) {
        return enqueue(chat, msgId, null, true);
    }

    private String enqueue(String chat, String msgId, String jobId, boolean byId) {
        var row = query(hub.index.db(), WaMessage.TYPEDEF)
                .eq(WaMessage.$key, WaMessage.keyOf(hub.account, chat, msgId)).firstOrNull();
        if (row == null) return "unknown message";
        if (row.joff() == null) return "no journal offset recorded";
        try {
            var line = hub.journal.lineAt(chat, row.joff());
            if (line == null) return "journal line missing";
            var msg = DaemonClient.GSON.fromJson(line, JsonObject.class);
            if (!byId && tooBig(msg, jobId)) return "too large; fetch it by id to override";
            markState(chat, msgId, "queued");
            queue.add(new MediaTask(jobId, msg));
            return "queued";
        } catch (Exception e) {
            return "failed: " + e.getMessage();
        }
    }

    /** Oversized attachments are recorded and skipped, so they stay listable rather than vanishing. */
    private boolean tooBig(JsonObject msg, String jobId) {
        var size = Json.num(Json.obj(msg, "media"), "size");
        var cap = hub.config.maxAttachmentMb() * 1024L * 1024L;
        if (size == null || cap <= 0 || size <= cap) return false;
        mark(Json.str(msg, "chat"), Json.str(msg, "id"), "toolarge", null, null);
        oversize.incrementAndGet();
        return true;
    }

    private void loop() {
        while (true) {
            MediaTask task = null;
            try {
                task = queue.take();
                Activity.touch();
                download(task);
                Activity.touch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.error("media download failed", e);
                failed.incrementAndGet();
                if (task != null) hub.jobs.advance(task.jobId(), "failed", 1, 0);
            }
        }
    }

    private void download(MediaTask task) throws Exception {
        var msg = task.msg();
        var chat = Json.str(msg, "chat");
        var msgId = Json.str(msg, "id");
        var media = Json.obj(msg, "media");
        var ts = Json.num(msg, "ts");
        var sentFrom = Json.str(media, "sentFrom");
        if (sentFrom != null && !sentFrom.isBlank()) {
            fileSent(task, chat, msgId, media, ts, sentFrom);
            return;
        }
        var target = MediaPlacer.target(hub.config.mediaRoot(), hub.driveFolder(), chat, chatName(chat), msgId,
                ts == null ? 0 : ts, Json.str(media, "filename"), Json.str(media, "kind"));

        var args = new LinkedHashMap<String, Object>();
        args.put("chat", chat);
        args.put("sender", Json.str(msg, "sender"));
        args.put("id", msgId);
        args.put("fromMe", Boolean.TRUE.equals(Json.bool(msg, "fromMe")));
        args.put("ts", ts);
        args.put("out", target.toString());
        args.put("body", msg.get("body"));

        var reply = hub.connector.call("media.download", args, 900);
        if (!reply.get("ok").getAsBoolean()) {
            var err = reply.get("error").getAsString();
            var isExpired = err.startsWith("expired");
            if (isExpired) expired.incrementAndGet(); else failed.incrementAndGet();
            mark(chat, msgId, isExpired ? "expired" : "failed", null, null);
            hub.jobs.advance(task.jobId(), isExpired ? "skipped" : "failed", 1, 0);
            return;
        }
        var res = reply.getAsJsonObject("result");
        var size = Json.num(res, "size");
        mark(chat, msgId, "done", Json.str(res, "path"), Json.str(res, "sha256"));
        done.incrementAndGet();
        hub.jobs.advance(task.jobId(), "done", 1, size == null ? 0 : size);
    }

    /**
     * The user's decision for a file this machine just sent: copy the same bytes we uploaded straight
     * onto the shared drive rather than leave the row unfetched for a backfill to download back from
     * WhatsApp. Errors are caught here rather than left to {@code loop()}'s generic handler, because a
     * failed copy is not a failed send — the message already went — so the honest outcome is a
     * {@code failed} media row (which a later backfill can still repair) and not a reported job
     * failure for a send that in fact succeeded.
     */
    private void fileSent(MediaTask task, String chat, String msgId, JsonObject media, Long ts, String sentFrom) {
        try {
            var source = Path.of(sentFrom);
            var target = MediaPlacer.target(hub.config.mediaRoot(), hub.driveFolder(), chat, chatName(chat), msgId,
                    ts == null ? 0 : ts, Json.str(media, "filename"), Json.str(media, "kind"));
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            var sha = sha256(target);
            var size = Files.size(target);
            mark(chat, msgId, "done", target.toString(), sha);
            done.incrementAndGet();
            hub.jobs.advance(task.jobId(), "done", 1, size);
        } catch (Exception e) {
            Log.error("filing sent attachment failed for " + chat + "/" + msgId, e);
            failed.incrementAndGet();
            mark(chat, msgId, "failed", null, null);
            hub.jobs.advance(task.jobId(), "failed", 1, 0);
        }
    }

    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            var buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public void completed(JsonObject data) {
        Log.info("media retry delivered: " + Json.str(data, "id"));
        done.incrementAndGet();
    }

    public void retryOutcome(JsonObject data) {
        Log.info("media retry " + Json.str(data, "status") + " for " + Json.str(data, "id"));
    }

    private String chatName(String chat) {
        var c = query(hub.index.db(), WaChat.TYPEDEF)
                .eq(WaChat.$key, WaChat.keyOf(hub.account, chat)).firstOrNull();
        return c == null ? null : c.name();
    }

    private void mark(String chat, String msgId, String state, String path, String sha) {
        var db = hub.index.db();
        db.transaction(() -> upsert(db, WaMessage.TYPEDEF)
                .key(WaMessage.$key, WaMessage.keyOf(hub.account, chat, msgId))
                .set(WaMessage.$mediaState, state)
                .set(WaMessage.$localPath, path)
                .set(WaMessage.$localSha256, sha)
                .save());
        tally = null;
    }

    /** State alone, so recording that a row is queued cannot erase where an earlier attempt filed it. */
    private void markState(String chat, String msgId, String state) {
        var db = hub.index.db();
        db.transaction(() -> upsert(db, WaMessage.TYPEDEF)
                .key(WaMessage.$key, WaMessage.keyOf(hub.account, chat, msgId))
                .set(WaMessage.$mediaState, state)
                .save());
        tally = null;
    }

    /**
     * Counts that survive a restart come from the index; the ones that cannot are named as this
     * session's. Reporting the in-memory tallies alone made a run of 118 filed attachments read as
     * zero the moment the app was restarted, which is the first thing an agent asks status for.
     */
    public Map<String, Object> stats() {
        var m = new LinkedHashMap<String, Object>();
        var tally = tally();
        m.put("mediaFiled", tally.getOrDefault("done", 0L));
        m.put("mediaNotFetched", tally.getOrDefault("none", 0L));
        m.put("mediaExpired", tally.getOrDefault("expired", 0L));
        m.put("mediaFailed", tally.getOrDefault("failed", 0L));
        m.put("mediaTooLarge", tally.getOrDefault("toolarge", 0L));
        m.put("mediaQueued", queue.size());
        m.put("sessionDone", done.get());
        m.put("sessionFailed", failed.get());
        m.put("sessionExpired", expired.get());
        return m;
    }

    /**
     * The cached tally, recomputed when a write here invalidated it or when the last one is a minute
     * old. The age backstop is insurance rather than the mechanism: every path in this class that
     * changes a media state clears the cache, and so does ingest of a message that carries an
     * attachment, but a count that is only ever right because every future writer remembered to say so
     * is a count that goes quietly wrong. A minute is far too rare to matter and far too soon to
     * mislead.
     */
    private Map<String, Object> tally() {
        var cached = tally;
        var now = System.nanoTime();
        if (cached != null && now - tallyAt < MAX_TALLY_AGE_NANOS) return cached;
        var fresh = Queries.mediaTally(hub.index.db(), hub.account);
        tallyAt = now;
        tally = fresh;
        return fresh;
    }
}
