package chatsgate.whatsappfx.daemon;

import com.arcadedb.database.Database;
import chatsgate.whatsappfx.core.Activity;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.core.model.WaJob;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static datapotter.arcadedbhelper.Query.query;
import static datapotter.arcadedbhelper.Upsert.upsert;

/** Creates, advances and reports jobs. Every mutation lands in the index before it is reported. */
public final class Jobs {

    private static final AtomicLong SEQ = new AtomicLong();

    private final Database db;
    private final String account;

    public Jobs(Database db, String account) {
        this.db = db;
        this.account = account;
    }

    public String create(String verb, String args, long total) {
        var id = verb.replace('.', '-') + "-" + now() + "-" + SEQ.incrementAndGet();
        write(id, u -> u.set(WaJob.$account, account)
                .set(WaJob.$verb, verb)
                .set(WaJob.$args, args)
                .set(WaJob.$state, "running")
                .set(WaJob.$total, total)
                .set(WaJob.$done, 0L)
                .set(WaJob.$failed, 0L)
                .set(WaJob.$skipped, 0L)
                .set(WaJob.$bytes, 0L)
                .set(WaJob.$startedAt, now()));
        return id;
    }

    public synchronized void advance(String id, String field, long by, long bytes) {
        if (id == null) return;
        Activity.touch();
        var job = byId(id);
        if (job == null) return;
        var current = switch (field) {
            case "done" -> job.done();
            case "failed" -> job.failed();
            default -> job.skipped();
        };
        var next = (current == null ? 0 : current) + by;
        write(id, u -> {
            switch (field) {
                case "done" -> u.set(WaJob.$done, next);
                case "failed" -> u.set(WaJob.$failed, next);
                default -> u.set(WaJob.$skipped, next);
            }
            u.set(WaJob.$bytes, (job.bytes() == null ? 0 : job.bytes()) + bytes);
        });
        if (finished(byId(id))) finish(id, null);
    }

    public void finish(String id, String error) {
        if (id == null) return;
        write(id, u -> u.set(WaJob.$state, error == null ? "done" : "failed")
                .set(WaJob.$finishedAt, now())
                .set(WaJob.$error, error));
        Log.info("job " + id + (error == null ? " done" : " failed: " + error));
    }

    public void setTotal(String id, long total) {
        write(id, u -> u.set(WaJob.$total, total));
    }

    private static boolean finished(WaJob j) {
        return j != null && j.totalOr0() > 0
                && j.doneOr0() + n(j.failed()) + n(j.skipped()) >= j.totalOr0();
    }

    private static long n(Long v) {
        return v == null ? 0 : v;
    }

    /**
     * A job left "running" by a killed process is not running. Saying so on startup is the difference
     * between a stale record and a lie.
     */
    public int reapOrphans() {
        // SQL for the same reason as the listing: this is a compound predicate, and the one thing this
        // method must not do is find fewer stale jobs than exist and leave one claiming to be running.
        var stale = Sql.all(db, "select id from WaJob where account = '" + Sql.esc(account)
                + "' and state = 'running'");
        for (var r : stale) {
            var id = String.valueOf(r.get("id"));
            write(id, u -> u.set(WaJob.$state, "interrupted").set(WaJob.$finishedAt, now()));
        }
        return stale.size();
    }

    public WaJob byId(String id) {
        return query(db, WaJob.TYPEDEF).eq(WaJob.$id, id).firstOrNull();
    }

    public Map<String, Object> report(String id) {
        var j = byId(id);
        if (j == null) throw new IllegalArgumentException("no such job: " + id);
        return describe(j);
    }

    /**
     * Newest first, in SQL. The typed builder applies its limit before its ordering, so the same
     * question asked through it returns some jobs rather than the latest ones — see {@link Sql}.
     */
    public List<Map<String, Object>> recent(int limit) {
        var out = new ArrayList<Map<String, Object>>();
        for (var r : Sql.query(db, "select id from WaJob where account = '" + Sql.esc(account)
                + "' order by startedAt desc", limit)) {
            var j = byId(String.valueOf(r.get("id")));
            if (j != null) out.add(describe(j));
        }
        return out;
    }

    /** ETA only where the denominator is real; an invented one is worse than none. */
    public static Map<String, Object> describe(WaJob j) {
        var m = new LinkedHashMap<String, Object>();
        var settled = j.doneOr0() + n(j.failed()) + n(j.skipped());
        var elapsed = Math.max(1, now() - n(j.startedAt()));
        m.put("id", j.id());
        m.put("verb", j.verb());
        m.put("state", j.state());
        m.put("total", j.total());
        m.put("done", j.doneOr0());
        m.put("failed", n(j.failed()));
        m.put("skipped", n(j.skipped()));
        m.put("bytes", n(j.bytes()));
        m.put("elapsedSeconds", "running".equals(j.state()) ? elapsed : n(j.finishedAt()) - n(j.startedAt()));
        m.put("etaSeconds", eta(j, settled, elapsed));
        m.put("startedAt", j.startedAt());
        m.put("finishedAt", j.finishedAt());
        m.put("error", j.error());
        m.put("args", j.args());
        return m;
    }

    private static Object eta(WaJob j, long settled, long elapsed) {
        if (!"running".equals(j.state()) || j.totalOr0() <= 0 || settled <= 0) return null;
        var remaining = j.totalOr0() - settled;
        return remaining <= 0 ? 0 : Math.round((double) elapsed / settled * remaining);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private void write(String id, java.util.function.Consumer<datapotter.arcadedbhelper.Upsert<WaJob>> body) {
        db.transaction(() -> {
            var u = upsert(db, WaJob.TYPEDEF).key(WaJob.$id, id).set(WaJob.$updatedAt, now());
            body.accept(u);
            u.save();
        });
    }
}
