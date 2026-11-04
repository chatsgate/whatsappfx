package chatsgate.whatsappfx.daemon;

import com.google.gson.JsonObject;
import chatsgate.whatsappfx.core.Json;
import chatsgate.whatsappfx.core.model.WaChat;
import chatsgate.whatsappfx.core.model.WaContact;
import chatsgate.whatsappfx.core.model.WaMessage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static datapotter.arcadedbhelper.Query.query;

/**
 * Calls. whatsmeow carries call signalling only, so a call is recorded as a message row of type
 * {@code call}, one row per state ({@code call-<callId>-<state>}), and can be declined but not answered.
 */
public final class Calls {

    private Calls() {}

    public static boolean isIncomingOffer(JsonObject data) {
        var call = Json.obj(data, "call");
        return call != null && "offer".equals(Json.str(call, "state")) && !Boolean.TRUE.equals(Json.bool(data, "fromMe"));
    }

    /** Contact name, else chat name, else the phone number, else the bare jid. */
    public static String callerName(Hub hub, JsonObject data) {
        var db = hub.index.db();
        for (var jid : new String[]{Json.str(data, "senderPn"), Json.str(data, "sender")}) {
            if (jid == null) continue;
            var c = query(db, WaContact.TYPEDEF).eq(WaContact.$key, WaContact.keyOf(hub.account, jid)).firstOrNull();
            if (c != null && !jid.equals(c.best())) return c.best();
        }
        var chat = Json.str(data, "chat");
        var ch = chat == null ? null : query(db, WaChat.TYPEDEF).eq(WaChat.$key, WaChat.keyOf(hub.account, chat)).firstOrNull();
        if (ch != null && ch.name() != null && !ch.name().isBlank()) return ch.name();
        var pn = Json.str(data, "senderPn");
        var shown = pn != null ? pn : Json.str(data, "sender");
        return shown == null ? "unknown" : "+" + shown.split("[:@]")[0];
    }

    /** Accepts the bare call id or the row id ({@code call-<callId>-<state>}). */
    static String callId(String id) {
        if (!id.startsWith("call-")) return id;
        var rest = id.substring(5);
        var dash = rest.lastIndexOf('-');
        return dash > 0 ? rest.substring(0, dash) : rest;
    }

    public static Map<String, Object> reject(Hub hub, String chat, String id) throws Exception {
        var callId = callId(id);
        var offer = query(hub.index.db(), WaMessage.TYPEDEF)
                .eq(WaMessage.$key, WaMessage.keyOf(hub.account, chat, "call-" + callId + "-offer")).firstOrNull();
        var from = offer != null && offer.sender() != null ? offer.sender() : chat;
        var reply = hub.connector.call("call.reject", Map.of("from", from, "callId", callId), 60);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
        hub.ingest.message(declined(chat, from, callId, offer));
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("callId", callId);
        out.put("from", from);
        out.put("offerKnown", offer != null);
        return out;
    }

    /** Our own decline leaves no event behind, so it is recorded here, the way a sent message is. */
    private static JsonObject declined(String chat, String from, String callId, WaMessage offer) {
        var call = new JsonObject();
        call.addProperty("callId", callId);
        call.addProperty("state", "declined");
        var d = new JsonObject();
        d.addProperty("source", "sent");
        d.addProperty("chat", chat);
        d.addProperty("sender", from);
        d.addProperty("fromMe", true);
        d.addProperty("isGroup", offer != null && Boolean.TRUE.equals(offer.isGroup()));
        d.addProperty("id", "call-" + callId + "-declined");
        d.addProperty("ts", Instant.now().getEpochSecond());
        d.addProperty("type", "call");
        d.addProperty("text", "call declined from this device");
        d.add("call", call);
        return d;
    }
}
