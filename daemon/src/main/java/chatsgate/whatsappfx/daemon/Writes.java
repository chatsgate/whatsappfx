package chatsgate.whatsappfx.daemon;

import com.google.gson.JsonObject;
import chatsgate.whatsappfx.core.model.WaMessage;
import chatsgate.whatsappfx.wire.DaemonClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static datapotter.arcadedbhelper.Query.query;
import static datapotter.arcadedbhelper.Upsert.upsert;

/**
 * The three write actions, with everything WhatsApp needs looked up rather than asked for.
 *
 * <p>A reaction, a star and a quoted reply all address a message by a key of chat, id, sender and
 * from-me, and getting the sender wrong does not fail — it builds a key for a message that does not
 * exist, the server accepts it, and nothing appears in the chat. The caller of an agentic CLI cannot
 * be expected to know that a group message's sender is the participant while a one-to-one message's
 * is the chat, so the index answers it: we recorded the sender when the message arrived.
 *
 * <p>What we sent is written back onto the message row, so "which bills have been marked" is a query
 * here rather than a scroll through the phone.
 */
public final class Writes {

    private Writes() {}

    public static Map<String, Object> react(Hub hub, String chat, String msgId, String emoji,
                                            String senderOverride, Boolean fromMeOverride) throws Exception {
        var ref = Ref.of(hub, chat, msgId, senderOverride, fromMeOverride);
        var mark = emoji == null || emoji.isBlank() ? hub.config.reactionEmoji() : emoji;
        var args = ref.args(chat, msgId);
        args.put("emoji", mark);
        var result = call(hub, "react", args);
        record(hub, chat, msgId, u -> u.set(WaMessage.$reaction, mark.isEmpty() ? null : mark));
        result.put("emoji", mark);
        result.put("resolvedSender", ref.sender);
        return result;
    }

    public static Map<String, Object> star(Hub hub, String chat, String msgId, boolean starred,
                                           String senderOverride, Boolean fromMeOverride) throws Exception {
        var ref = Ref.of(hub, chat, msgId, senderOverride, fromMeOverride);
        var args = ref.args(chat, msgId);
        args.put("starred", starred);
        var result = call(hub, "star", args);
        record(hub, chat, msgId, u -> u.set(WaMessage.$starred, starred));
        result.put("starred", starred);
        result.put("resolvedSender", ref.sender);
        return result;
    }

    /** Replaces the text of one of our own messages (WhatsApp allows about 15 minutes). The connector reports the new text as the same message id, so the index row follows. */
    public static Map<String, Object> edit(Hub hub, String chat, String msgId, String text) throws Exception {
        var args = new LinkedHashMap<String, Object>();
        args.put("chat", chat);
        args.put("id", msgId);
        args.put("text", text);
        return call(hub, "edit", args);
    }

    /**
     * A reply quoting the message it answers. The quoted body is the protojson the connector emitted
     * when the message arrived, read back out of the journal — so the quote block renders as the
     * original image or document, not as a line of text about it.
     */
    public static Map<String, Object> reply(Hub hub, String chat, String msgId, String text,
                                            String senderOverride) throws Exception {
        var ref = Ref.of(hub, chat, msgId, senderOverride, null);
        var args = new LinkedHashMap<String, Object>();
        args.put("chat", chat);
        args.put("text", text);
        args.put("quoteId", msgId);
        args.put("quoteSender", ref.sender);
        var body = ref.body(hub, chat);
        if (body != null) args.put("quoteBody", body);
        var result = call(hub, "reply", args);
        result.put("quotedSender", ref.sender);
        result.put("quotedBodyIncluded", body != null);
        return result;
    }

    /**
     * A file, or a media reply when {@code quoteId} is given. The path is validated here rather than
     * left to the connector's own open-file error, so a typo is reported against the field the caller
     * can fix rather than as an opaque failure from the far side of the pipe.
     */
    public static Map<String, Object> sendMedia(Hub hub, String chat, String path, String caption,
                                                String as, String filename, String quoteId,
                                                String senderOverride) throws Exception {
        var file = Path.of(path);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("no such file: " + path);
        var args = new LinkedHashMap<String, Object>();
        args.put("chat", chat);
        args.put("path", path);
        if (caption != null) args.put("caption", caption);
        if (as != null) args.put("as", as);
        if (filename != null) args.put("filename", filename);
        String quotedSender = null;
        JsonObject body = null;
        if (quoteId != null && !quoteId.isBlank()) {
            var ref = Ref.of(hub, chat, quoteId, senderOverride, null);
            args.put("quoteId", quoteId);
            args.put("quoteSender", ref.sender);
            body = ref.body(hub, chat);
            if (body != null) args.put("quoteBody", body);
            quotedSender = ref.sender;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (Exception e) {
            size = 0;
        }
        var result = call(hub, "send.media", args, connectorTimeout(size));
        if (quotedSender != null) {
            result.put("quotedSender", quotedSender);
            result.put("quotedBodyIncluded", body != null);
        }
        return result;
    }

    /**
     * The fixed 120s the other writes use is fine for a reaction or a short reply; it is not fine for
     * a large upload, where the failure mode is the ugly one — the CLI reports a timeout while the
     * upload continues in the background and the message eventually lands anyway. 200 KB/s is a
     * deliberately pessimistic floor, and the cap stays under {@code DaemonClient.CALL_TIMEOUT} (30
     * minutes) so the HTTP call does not die first and report nothing useful.
     */
    private static long connectorTimeout(long fileSizeBytes) {
        return Math.min(1500, 120 + fileSizeBytes / (200 * 1024));
    }

    /** What the message row knows about how to address this message, with caller overrides on top. */
    private record Ref(String sender, boolean fromMe, Long joff) {

        static Ref of(Hub hub, String chat, String msgId, String senderOverride, Boolean fromMeOverride) {
            var row = query(hub.index.db(), WaMessage.TYPEDEF)
                    .eq(WaMessage.$key, WaMessage.keyOf(hub.account, chat, msgId)).firstOrNull();
            var sender = senderOverride;
            if (sender == null || sender.isBlank()) sender = row == null ? chat : row.sender();
            if (sender == null || sender.isBlank()) sender = chat;
            var fromMe = fromMeOverride != null ? fromMeOverride
                    : row != null && Boolean.TRUE.equals(row.fromMe());
            return new Ref(sender, fromMe, row == null ? null : row.joff());
        }

        LinkedHashMap<String, Object> args(String chat, String msgId) {
            var m = new LinkedHashMap<String, Object>();
            m.put("chat", chat);
            m.put("id", msgId);
            m.put("sender", sender);
            m.put("fromMe", fromMe);
            return m;
        }

        JsonObject body(Hub hub, String chat) {
            if (joff == null) return null;
            try {
                var line = hub.journal.lineAt(chat, joff);
                if (line == null) return null;
                var msg = DaemonClient.GSON.fromJson(line, JsonObject.class);
                return msg.has("body") && msg.get("body").isJsonObject() ? msg.getAsJsonObject("body") : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static Map<String, Object> call(Hub hub, String cmd, Map<String, Object> args) throws Exception {
        return call(hub, cmd, args, 120);
    }

    private static Map<String, Object> call(Hub hub, String cmd, Map<String, Object> args, long timeoutSeconds) throws Exception {
        var reply = hub.connector.call(cmd, args, timeoutSeconds);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("result", reply.get("result"));
        return out;
    }

    /**
     * The account, chat and id go in alongside the mark. They are already in the key, but a row whose
     * fields are only in its key is a row no query can find: marking a message we did not hold created
     * exactly that — a reaction recorded against nothing, invisible to {@code messages}, to
     * {@code media}, and to the question it was written to answer.
     */
    private static void record(Hub hub, String chat, String msgId,
                               java.util.function.Consumer<datapotter.arcadedbhelper.Upsert<WaMessage>> body) {
        var db = hub.index.db();
        db.transaction(() -> {
            var u = upsert(db, WaMessage.TYPEDEF)
                    .key(WaMessage.$key, WaMessage.keyOf(hub.account, chat, msgId))
                    .set(WaMessage.$account, hub.account)
                    .set(WaMessage.$chat, chat)
                    .set(WaMessage.$msgId, msgId);
            body.accept(u);
            u.save();
        });
    }
}
