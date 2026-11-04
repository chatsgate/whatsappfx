package chatsgate.whatsappfx.daemon;

import com.arcadedb.database.Database;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only SQL against the index, and the only read path this daemon trusts.
 *
 * <p>ArcadeDB has two query APIs and they disagree. The typed builder goes through the engine's
 * native {@code Select}, which hands the result limit to the index candidate scan — so the cap is
 * spent before the remaining conditions and before the ordering are applied. Measured on this index:
 * {@code messages --chat} returned 0 rows of 371, {@code search "the"} returned 11 of 662, and
 * {@code chats} reported a last-message timestamp ten days stale on a busy chat. The same questions
 * asked in SQL answer correctly, so every listing verb is built here.
 *
 * <p>The limit is therefore applied in Java, on the way out, and never inside the statement: a
 * {@code LIMIT} in the SQL text would put the cap back where the engine can misuse it.
 */
public final class Sql {

    private Sql() {}

    /**
     * Effectively uncapped, for the internal callers that need every matching row — a backfill
     * work-list, a per-chat aggregate. A number rather than no bound at all, so a runaway query
     * exhausts the ceiling instead of the heap.
     */
    public static final int NO_CAP = 1_000_000;

    public static List<Map<String, Object>> query(Database db, String statement, int limit) {
        var s = requireRead(statement);
        var out = new ArrayList<Map<String, Object>>();
        try (var rs = db.query("sql", s)) {
            while (rs.hasNext() && out.size() < limit) {
                var row = rs.next();
                var m = new LinkedHashMap<String, Object>();
                for (var name : row.getPropertyNames()) m.put(name, portable(row.getProperty(name), 0));
                out.add(m);
            }
        }
        return out;
    }

    /** The read-only guard, on its own so a caller that rewrites a statement can check it first. */
    public static String requireRead(String statement) {
        var s = statement == null ? "" : statement.strip();
        var head = s.toLowerCase(Locale.ROOT);
        if (!head.startsWith("select") && !head.startsWith("match")) {
            throw new IllegalArgumentException("only SELECT and MATCH are allowed here");
        }
        return s;
    }

    /**
     * A value the wire can carry, whatever the engine put in the row.
     *
     * <p>Not every result is a string and a number. {@code select from schema:types} answers with
     * schema objects, and one of them reaches a {@code java.io.File}, on which Gson gives up with
     * <i>Failed making field 'java.io.File#path' accessible</i> — and because the whole reply is
     * serialised at once, that killed the entire call with HTTP 400 and a message about reflection.
     * The one query that would have told a reader the real column names was the one that could not
     * run. Anything outside the shapes JSON has a place for is rendered as its text instead of being
     * allowed to fail the response.
     */
    private static Object portable(Object v, int depth) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (depth >= MAX_DEPTH) return String.valueOf(v);
        if (v instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : map.entrySet()) out.put(String.valueOf(e.getKey()), portable(e.getValue(), depth + 1));
            return out;
        }
        if (v instanceof Iterable<?> it) {
            var out = new ArrayList<>();
            for (var e : it) out.add(portable(e, depth + 1));
            return out;
        }
        if (v.getClass().isArray()) {
            var out = new ArrayList<>();
            for (var i = 0; i < java.lang.reflect.Array.getLength(v); i++) {
                out.add(portable(java.lang.reflect.Array.get(v, i), depth + 1));
            }
            return out;
        }
        return String.valueOf(v);
    }

    /** Deep enough for a nested schema record, shallow enough that a cycle cannot run away. */
    private static final int MAX_DEPTH = 5;

    /** Every matching row, up to {@link #NO_CAP}. */
    public static List<Map<String, Object>> all(Database db, String statement) {
        return query(db, statement, NO_CAP);
    }

    /** A single scalar, for the count-shaped questions. */
    public static long count(Database db, String statement) {
        var rows = query(db, statement, 1);
        if (rows.isEmpty()) return 0;
        for (var v : rows.get(0).values()) if (v instanceof Number n) return n.longValue();
        return 0;
    }

    /** Single quotes are the only metacharacter that can escape a quoted literal here. */
    public static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
