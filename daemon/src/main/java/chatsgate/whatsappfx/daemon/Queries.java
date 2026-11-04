package chatsgate.whatsappfx.daemon;

import com.arcadedb.database.Database;
import chatsgate.whatsappfx.core.model.WaChat;
import chatsgate.whatsappfx.core.model.WaMessage;
import datapotter.datahelper.Field_I;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static chatsgate.whatsappfx.daemon.Sql.esc;

/**
 * Every read verb. Free to call and merely audited — this is local data, not a credential.
 *
 * <p>All of it goes through {@link Sql}. The typed builder is not used for any listing: it under-reads
 * whenever a limit meets anything the chosen index does not cover, and it applies {@code orderBy}
 * after that limit, so "newest first" came back neither newest nor complete. See {@link Sql} for the
 * measurements.
 */
public final class Queries {

    private Queries() {}

    /**
     * The columns of a message listing, as fields rather than as strings, so renaming one in the model
     * is a compile error here instead of a column that silently stops being filled in.
     *
     * <p>What each is PRINTED as is not decided here: {@link Columns#printedName} holds the one
     * definition of the presentation names, and {@code query} reads the same table to resolve a
     * printed name back to storage. Two copies of that mapping would drift, and the drift is precisely
     * the fault this arrangement exists to prevent — a reader learns the column name {@code id} from a
     * listing, writes it into SQL, and gets a column of nulls with no error.
     */
    private static final List<Field_I<WaMessage, ?>> MSG_FIELDS = List.of(
            WaMessage.$msgId, WaMessage.$chat, WaMessage.$sender, WaMessage.$senderPn, WaMessage.$fromMe,
            WaMessage.$ts, WaMessage.$pushName, WaMessage.$type, WaMessage.$text, WaMessage.$mediaKind,
            WaMessage.$mimetype, WaMessage.$filename, WaMessage.$size, WaMessage.$mediaState,
            WaMessage.$localPath, WaMessage.$localSha256, WaMessage.$reaction, WaMessage.$starred);

    /**
     * Conversations, most recent first.
     *
     * <p>Recency and counts are derived from the messages rather than read off the chat record: the
     * conversation records history sync sends arrive with a zero timestamp. Two grouped passes do the
     * whole job, which also replaces three per-chat queries each — 630 of them on this index.
     *
     * <p>A chat that holds messages but never got a conversation record — the self chat and
     * {@code status@broadcast} are both like this — is listed too. Leaving it out made its messages
     * undiscoverable: nothing named the jid that {@code messages --chat} needs.
     */
    public static List<Map<String, Object>> chats(Database db, String account, String contains, int limit) {
        var a = esc(account);
        var newest = new HashMap<String, Long>();
        var counts = new HashMap<String, Long>();
        for (var r : Sql.all(db, "select chat, max(ts) as newest, count(*) as n from WaMessage"
                + " where account = '" + a + "' group by chat")) {
            var jid = str(r.get("chat"));
            if (jid == null) continue;
            newest.put(jid, num(r.get("newest")));
            counts.put(jid, num(r.get("n")));
        }
        var attachments = new HashMap<String, Long>();
        for (var r : Sql.all(db, "select chat, count(*) as n from WaMessage where account = '" + a
                + "' and mediaKind is not null group by chat")) {
            attachments.put(str(r.get("chat")), num(r.get("n")));
        }

        var names = contactNames(db, account);
        var order = new LinkedHashSet<String>();
        var records = new LinkedHashMap<String, Map<String, Object>>();
        for (var c : Sql.all(db, "select from WaChat where account = '" + a + "'")) {
            var jid = str(c.get("jid"));
            if (jid == null) continue;
            records.put(jid, c);
            order.add(jid);
        }
        order.addAll(newest.keySet());

        var out = new ArrayList<Map<String, Object>>();
        for (var jid : order) {
            var c = records.get(jid);
            var pn = c == null ? null : str(c.get("pn"));
            var name = displayName(c, jid, pn, names);
            if (contains != null && !matches(contains, jid, name, pn)) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("jid", jid);
            m.put("name", name);
            m.put("pn", pn);
            m.put("isGroup", c == null ? jid.endsWith("@g.us") : c.get("isGroup"));
            m.put("lastMsgTs", newest.get(jid));
            // Through the same table the query verb reads, so this rename cannot become a second,
            // divergent opinion about what a chat listing calls WaChat.unreadCount.
            m.put(Columns.printedName(WaChat.__, WaChat.$unreadCount.name()),
                    c == null ? null : c.get(WaChat.$unreadCount.name()));
            m.put("mediaVisibility", c == null ? null : c.get("mediaVisibility"));
            m.put("mediaPolicy", c == null || c.get("mediaPolicy") == null ? "inherit" : c.get("mediaPolicy"));
            m.put("messages", counts.getOrDefault(jid, 0L));
            m.put("attachments", attachments.getOrDefault(jid, 0L));
            m.put("known", c != null);
            out.add(m);
        }
        out.sort((x, y) -> Long.compare(num(y.get("lastMsgTs")), num(x.get("lastMsgTs"))));
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /** The chat's own name, else whatever the contact list knows under its LID or its phone number. */
    private static String displayName(Map<String, Object> chat, String jid, String pn, Map<String, String> names) {
        if (chat != null) {
            var own = str(chat.get("name"));
            if (own != null && !own.isBlank()) return own;
        }
        for (var key : new String[]{pn, jid}) {
            if (key == null) continue;
            var found = names.get(key);
            if (found != null) return found;
        }
        return null;
    }

    /** One pass over the contact list instead of two index lookups per chat. */
    private static Map<String, String> contactNames(Database db, String account) {
        var out = new HashMap<String, String>();
        for (var c : Sql.all(db, "select from WaContact where account = '" + esc(account) + "'")) {
            var jid = str(c.get("jid"));
            if (jid == null) continue;
            var best = best(c);
            if (best != null) out.put(jid, best);
        }
        return out;
    }

    private static String best(Map<String, Object> contact) {
        for (var f : new String[]{"fullName", "businessName", "pushName", "firstName"}) {
            var v = str(contact.get(f));
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    public static List<Map<String, Object>> messages(Database db, String account, String chat,
                                                     String contains, Long before, int limit) {
        var sql = new StringBuilder("select from WaMessage where account = '").append(esc(account)).append('\'');
        if (chat != null) sql.append(" and chat = '").append(esc(chat)).append('\'');
        if (before != null) sql.append(" and ts < ").append(before);
        if (contains != null) sql.append(" and text ilike '%").append(esc(contains)).append("%'");
        sql.append(" order by ts desc");
        return rows(db, sql.toString(), limit);
    }

    public static List<Map<String, Object>> media(Database db, String account, String chat, String state, int limit) {
        var sql = new StringBuilder("select from WaMessage where account = '")
                .append(esc(account)).append("' and mediaKind is not null");
        if (chat != null) sql.append(" and chat = '").append(esc(chat)).append('\'');
        if (state != null) sql.append(" and mediaState = '").append(esc(state)).append('\'');
        sql.append(" order by ts desc");
        return rows(db, sql.toString(), limit);
    }

    private static List<Map<String, Object>> rows(Database db, String sql, int limit) {
        var out = new ArrayList<Map<String, Object>>();
        for (var r : Sql.query(db, sql, limit)) {
            var m = new LinkedHashMap<String, Object>();
            for (var f : MSG_FIELDS) m.put(Columns.printedName(WaMessage.__, f.name()), r.get(f.name()));
            out.add(m);
        }
        return out;
    }

    public static List<Map<String, Object>> contacts(Database db, String account, String contains, int limit) {
        var out = new ArrayList<Map<String, Object>>();
        for (var c : Sql.all(db, "select from WaContact where account = '" + esc(account) + "'")) {
            var jid = str(c.get("jid"));
            var name = best(c);
            if (contains != null && !matches(contains, jid, name)) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("jid", jid);
            m.put("name", name == null ? jid : name);
            m.put("pushName", c.get("pushName"));
            out.add(m);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Durable attachment tally, straight off the index, so a restart does not report zero filed. */
    public static Map<String, Object> mediaTally(Database db, String account) {
        var m = new LinkedHashMap<String, Object>();
        for (var r : Sql.all(db, "select mediaState, count(*) as n from WaMessage where account = '"
                + esc(account) + "' and mediaKind is not null group by mediaState")) {
            var state = str(r.get("mediaState"));
            m.put(state == null ? "none" : state, num(r.get("n")));
        }
        return m;
    }

    private static boolean matches(String needle, String... fields) {
        var n = needle.toLowerCase(Locale.ROOT);
        for (var f : fields) if (f != null && f.toLowerCase(Locale.ROOT).contains(n)) return true;
        return false;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}
