package chatsgate.whatsappfx.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import chatsgate.whatsappfx.wire.DaemonClient;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Output for two audiences at once: a person reading a terminal and an agent piping into another
 * tool. TSV is the default because it is the one shape both can consume without a parser.
 */
public final class Out {

    public static final PrintStream STDOUT = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);

    /** Progress goes to stderr so a --wait run still pipes clean data on stdout. */
    public static final PrintStream STDERR = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);

    private Out() {}

    private static boolean warnedBuild;

    public static DaemonClient daemon() {
        var d = DaemonClient.ifRunning().orElseThrow(() -> new IllegalStateException(
                "WhatsappFx is not running — start the app first (run whatsappfx; it holds the index and the WhatsApp session, and this command line only talks to it)"));
        warnIfOtherBuild(d.version());
        return d;
    }

    /** The command line and the app are one release; a different build on the other end is said once, on stderr. */
    private static void warnIfOtherBuild(String app) {
        var mine = chatsgate.whatsappfx.wire.Version.current();
        if (warnedBuild || app == null || app.equals(mine) || app.equals("dev") || mine.equals("dev")) return;
        warnedBuild = true;
        STDERR.println("note: whatsappcli " + mine + " is talking to a WhatsappFx " + app + ". They are meant to be the same build:"
                + " update both (whatsappfx -Xjr:update, whatsappcli -Xjr:update).");
    }

    public static String call(String verb, Map<String, Object> args) throws Exception {
        return daemon().callRaw(verb, args);
    }

    public static void emit(String json, boolean asJson) {
        emit(json, asJson, -1);
    }

    /**
     * With the limit that produced it, so a full page can say so.
     *
     * <p>A listing that returns exactly as many rows as it was allowed is indistinguishable from one
     * that returned everything, and an agent reading the second as the first draws a conclusion from a
     * truncated set. The note goes to stderr, so stdout still pipes clean.
     */
    public static void emit(String json, boolean asJson, int limit) {
        var parsed = DaemonClient.GSON.fromJson(json, JsonElement.class);
        if (parsed.isJsonArray() && limit > 0 && parsed.getAsJsonArray().size() >= limit) {
            STDERR.println("note: " + limit + " rows returned, which is the limit — there may be more."
                    + " Raise --limit or narrow the query.");
        }
        if (asJson || !parsed.isJsonArray()) {
            STDOUT.println(DaemonClient.GSON.toJson(parsed));
            return;
        }
        tsv(parsed.getAsJsonArray());
    }

    /** Union of keys across rows, so a column that only some rows carry is never silently dropped. */
    public static void tsv(JsonArray rows) {
        if (rows.isEmpty()) return;
        var cols = new LinkedHashSet<String>();
        for (var r : rows) if (r.isJsonObject()) cols.addAll(r.getAsJsonObject().keySet());
        STDOUT.println(String.join("\t", cols));
        for (var r : rows) {
            if (!r.isJsonObject()) continue;
            var o = r.getAsJsonObject();
            var cells = new ArrayList<String>(cols.size());
            for (var c : cols) cells.add(cell(o, c));
            STDOUT.println(String.join("\t", cells));
        }
    }

    /** Tabs and newlines inside a value would break the row, so they become spaces. */
    private static String cell(JsonObject o, String col) {
        if (!o.has(col) || o.get(col).isJsonNull()) return "";
        var e = o.get(col);
        var s = e.isJsonPrimitive() ? e.getAsString() : DaemonClient.GSON.toJson(e);
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /** One human-readable line: what is done, of how much, and how long is left. */
    public static String progress(String jobJson) {
        var o = DaemonClient.GSON.fromJson(jobJson, JsonObject.class);
        var total = num(o, "total");
        var settled = num(o, "done") + num(o, "failed") + num(o, "skipped");
        var pct = total > 0 ? settled * 100 / total : 0;
        var eta = o.get("etaSeconds");
        var line = String.format("%s  %d/%d (%d%%)  ok=%d failed=%d skipped=%d  %dMB",
                o.get("verb").getAsString(), settled, total, pct,
                num(o, "done"), num(o, "failed"), num(o, "skipped"), num(o, "bytes") / (1024 * 1024));
        return eta == null || eta.isJsonNull() ? line : line + "  eta " + eta.getAsLong() + "s";
    }

    private static long num(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
    }
}
