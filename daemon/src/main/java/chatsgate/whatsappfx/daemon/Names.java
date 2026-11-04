package chatsgate.whatsappfx.daemon;

import com.google.gson.JsonObject;
import chatsgate.whatsappfx.core.Json;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.core.model.WaChat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static datapotter.arcadedbhelper.Query.query;
import static datapotter.arcadedbhelper.Upsert.upsert;

/**
 * Gives nameless chats their names.
 *
 * <p>History sync addresses chats by LID while the contact list is keyed by phone number, so a fresh
 * sync leaves most chats as bare identifiers. This asks the account what it knows and writes the
 * answer into the index, so it costs one pass rather than a re-sync.
 */
public final class Names {

    private static final int BATCH = 200;

    private Names() {}

    public static Map<String, Object> sync(Hub hub) throws Exception {
        var contacts = pullContacts(hub);
        var named = resolveChats(hub);
        var out = new LinkedHashMap<String, Object>();
        out.put("contacts", contacts);
        out.put("chatsNamed", named);
        out.put("groupsNamed", groups(hub));
        return out;
    }

    /**
     * Every group this account is in, named, in one call. History sync wrote a chat row only for the chats that existed
     * then, so a group joined or first heard from later had messages and no name, and the chats window showed it as
     * "Group 1203…". Run on every connect; cheap (one request, a few dozen rows).
     */
    public static int groups(Hub hub) throws Exception {
        var reply = hub.connector.call("groups", Map.of(), 120);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
        var n = 0;
        for (var e : reply.getAsJsonArray("result")) {
            var g = e.getAsJsonObject();
            if (group(hub, Json.str(g, "jid"), Json.str(g, "name"))) n++;
        }
        Log.info("group names: " + n + " groups named");
        return n;
    }

    /** One group the index has no name for (a message arrived in it), looked up through the connector. */
    public static void group(Hub hub, String jid) {
        try {
            var reply = hub.connector.call("resolve", Map.of("jids", List.of(jid)), 60);
            if (!reply.get("ok").getAsBoolean()) return;
            for (var e : reply.getAsJsonArray("result")) group(hub, jid, Json.str(e.getAsJsonObject(), "name"));
        } catch (Exception ex) {
            Log.warn("group name for " + jid + " not found: " + ex.getMessage());
        }
    }

    static boolean group(Hub hub, String jid, String name) {
        if (jid == null || name == null || name.isBlank()) return false;
        var db = hub.index.db();
        db.transaction(() -> upsert(db, WaChat.TYPEDEF).key(WaChat.$key, WaChat.keyOf(hub.account, jid))
                .set(WaChat.$account, hub.account).set(WaChat.$jid, jid).set(WaChat.$isGroup, true)
                .set(WaChat.$name, name).save());
        return true;
    }

    /** Whether the index has a name for this chat. */
    public static boolean named(Hub hub, String jid) {
        var c = query(hub.index.db(), WaChat.TYPEDEF).eq(WaChat.$key, WaChat.keyOf(hub.account, jid)).firstOrNull();
        return c != null && c.name() != null && !c.name().isBlank();
    }

    private static int pullContacts(Hub hub) throws Exception {
        var reply = hub.connector.call("contacts", Map.of(), 120);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
        var rows = reply.getAsJsonArray("result");
        for (var r : rows) hub.ingest.contact(r.getAsJsonObject());
        return rows.size();
    }

    private static int resolveChats(Hub hub) throws Exception {
        var db = hub.index.db();
        var pending = new ArrayList<String>();
        for (var c : query(db, WaChat.TYPEDEF).eq(WaChat.$account, hub.account).list()) {
            if (c.name() == null || c.name().isBlank()) pending.add(c.jid());
        }
        var done = 0;
        for (var i = 0; i < pending.size(); i += BATCH) {
            var slice = pending.subList(i, Math.min(pending.size(), i + BATCH));
            var reply = hub.connector.call("resolve", Map.of("jids", slice), 120);
            if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
            done += apply(hub, reply.getAsJsonArray("result").asList().stream()
                    .map(e -> e.getAsJsonObject()).toList());
        }
        Log.info("name sync: " + done + " of " + pending.size() + " nameless chats resolved");
        return done;
    }

    private static int apply(Hub hub, List<JsonObject> rows) {
        var db = hub.index.db();
        var applied = 0;
        for (var row : rows) {
            var jid = Json.str(row, "jid");
            var name = Json.str(row, "name");
            var pn = Json.str(row, "pn");
            if (jid == null || (name == null && pn == null)) continue;
            db.transaction(() -> {
                var u = upsert(db, WaChat.TYPEDEF).key(WaChat.$key, WaChat.keyOf(hub.account, jid));
                if (pn != null) u.set(WaChat.$pn, pn);
                if (name != null && !name.isBlank()) u.set(WaChat.$name, name);
                u.save();
            });
            if (name != null && !name.isBlank()) applied++;
        }
        return applied;
    }
}
