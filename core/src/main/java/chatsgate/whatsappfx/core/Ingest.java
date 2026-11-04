package chatsgate.whatsappfx.core;

import com.arcadedb.database.Database;
import com.google.gson.JsonObject;
import chatsgate.whatsappfx.core.model.WaChat;
import chatsgate.whatsappfx.core.model.WaContact;
import chatsgate.whatsappfx.core.model.WaMessage;
import chatsgate.whatsappfx.wire.DaemonClient;

import static chatsgate.whatsappfx.core.Json.bool;
import static chatsgate.whatsappfx.core.Json.num;
import static chatsgate.whatsappfx.core.Json.str;
import static chatsgate.whatsappfx.core.model.WaChat.$key;
import static datapotter.arcadedbhelper.Query.query;
import static datapotter.arcadedbhelper.Upsert.upsert;

/** Turns connector events into a journal line and an index row, in that order. */
public final class Ingest {

    private final String account;
    private final Journal journal;
    private final Database db;

    public Ingest(String account, Journal journal, Database db) {
        this.account = account;
        this.journal = journal;
        this.db = db;
    }

    /**
     * A message already in the journal is not written to it twice.
     *
     * <p>The index has always been idempotent, keyed on the message, but the journal appended
     * unconditionally — and WhatsApp replays the entire history on every re-pair. Left alone, each
     * relogin wrote a second copy of every message this account has ever received, so the truth-store
     * grew by its whole size each time somebody recovered a banned device. The existing row already
     * carries the offset of the line, so the honest answer is to reuse it.</p>
     */
    public void message(JsonObject data) {
        var chat = str(data, "chat");
        var msgId = str(data, "id");
        if (chat == null || msgId == null) return;
        var known = existing(WaMessage.keyOf(account, chat, msgId));
        var offset = known == null ? null : known.joff();
        if (offset == null) {
            try {
                offset = journal.append(chat, DaemonClient.GSON.toJson(data));
            } catch (Exception e) {
                Log.error("journal append failed for " + chat, e);
                return;
            }
        }
        indexRow(data, offset);
    }

    /**
     * The index half alone, for a rebuild: the line is already in the journal at {@code offset}, so
     * appending it again would duplicate the truth in the name of restoring a view of it.
     */
    public boolean indexRow(JsonObject data, long offset) {
        var chat = str(data, "chat");
        var msgId = str(data, "id");
        if (chat == null || msgId == null) return false;
        var media = data.has("media") && data.get("media").isJsonObject() ? data.getAsJsonObject("media") : null;
        var key = WaMessage.keyOf(account, chat, msgId);
        // The same message arrives again on every reconnect that replays history. Writing the initial
        // attachment state unconditionally would walk a filed attachment back to unfetched and offer it
        // to the next backfill as work.
        var settled = media == null ? null : settledState(key);
        db.transaction(() -> {
            var u = upsert(db, WaMessage.TYPEDEF).key(WaMessage.$key, key)
                    .set(WaMessage.$account, account)
                    .set(WaMessage.$chat, chat)
                    .set(WaMessage.$msgId, msgId)
                    .set(WaMessage.$sender, str(data, "sender"))
                    .set(WaMessage.$senderPn, str(data, "senderPn"))
                    .set(WaMessage.$chatPn, str(data, "chatPn"))
                    .set(WaMessage.$fromMe, bool(data, "fromMe"))
                    .set(WaMessage.$isGroup, bool(data, "isGroup"))
                    .set(WaMessage.$ts, num(data, "ts"))
                    .set(WaMessage.$pushName, str(data, "pushName"))
                    .set(WaMessage.$type, typeOf(data))
                    .set(WaMessage.$text, str(data, "text"))
                    .set(WaMessage.$source, str(data, "source"))
                    .set(WaMessage.$joff, offset);
            if (media != null) {
                u.set(WaMessage.$mediaKind, str(media, "kind"))
                        .set(WaMessage.$mimetype, str(media, "mimetype"))
                        .set(WaMessage.$filename, str(media, "filename"))
                        .set(WaMessage.$size, num(media, "size"))
                        // "none", not "queued": policy decides separately whether this is ever fetched,
                        // and calling every attachment queued made 646 rows nobody had queued read as
                        // pending work.
                        .set(WaMessage.$mediaState, settled != null ? settled
                                : Boolean.TRUE.equals(bool(media, "expired")) ? "expired" : "none");
            }
            u.save();
        });
        return true;
    }

    /**
     * Protocol traffic named as such. whatsmeow reports app-state key shares, peer-data operations and
     * sender-key distribution with {@code Type == "text"}, which put nine rows of encryption
     * housekeeping into the self chat looking exactly like nine text messages with nothing in them.
     * They stay recorded — they are real events and the journal keeps everything — but an agent asking
     * what is in a chat can now tell them apart from something a person said.
     */
    private static String typeOf(JsonObject data) {
        var declared = str(data, "type");
        var text = str(data, "text");
        if (text != null && !text.isBlank()) return declared;
        var body = data.has("body") && data.get("body").isJsonObject() ? data.getAsJsonObject("body") : null;
        if (body == null || body.size() != 1) return declared;
        for (var field : new String[]{"protocolMessage", "senderKeyDistributionMessage",
                "deviceSentMessage", "keepInChatMessage"}) {
            if (body.has(field)) return "protocol";
        }
        return declared;
    }

    /**
     * A state a re-delivery must not undo: the file is on disk, we decided not to take it, or we tried
     * and WhatsApp had dropped it.
     *
     * <p>Expiry belongs here even though it looks like something the message itself reports. The flag
     * in the journal line says whether the media had a direct path at sync time; expiry is what we
     * learned by attempting the download later, so a replay of the line cannot know it. A reindex that
     * dropped it walked three known-expired attachments back to unfetched and offered them to the next
     * backfill as work. An explicit {@code fetch --id} still overrides all of this.
     */
    private String settledState(String key) {
        var row = existing(key);
        var state = row == null ? null : row.mediaState();
        return "done".equals(state) || "toolarge".equals(state) || "expired".equals(state) ? state : null;
    }

    private WaMessage existing(String key) {
        return query(db, WaMessage.TYPEDEF).eq(WaMessage.$key, key).firstOrNull();
    }

    public void chat(JsonObject d) {
        var jid = str(d, "jid");
        if (jid == null) return;
        var key = WaChat.keyOf(account, jid);
        db.transaction(() -> {
            var u = upsert(db, WaChat.TYPEDEF).key($key, key)
                .set(WaChat.$account, account)
                .set(WaChat.$jid, jid)
                .set(WaChat.$isGroup, jid.endsWith("@g.us"))
                .set(WaChat.$mediaVisibility, str(d, "mediaVisibility"))
                .set(WaChat.$archived, bool(d, "archived"))
                .set(WaChat.$pinned, num(d, "pinned"))
                .set(WaChat.$muteEndTime, num(d, "muteEndTime"))
                .set(WaChat.$unreadCount, num(d, "unreadCount"))
                .set(WaChat.$lastMsgTs, num(d, "lastMsgTs"))
                .set(WaChat.$endOfHistory, bool(d, "endOfHistory"));
            // A later chunk that carries no name must not erase a name already resolved for this chat.
            setIfPresent(u, WaChat.$name, str(d, "name"));
            setIfPresent(u, WaChat.$pn, str(d, "pn"));
            u.save();
        });
    }

    public void contact(JsonObject d) {
        var jid = str(d, "jid");
        if (jid == null) return;
        var key = WaContact.keyOf(account, jid);
        db.transaction(() -> {
            var u = upsert(db, WaContact.TYPEDEF).key(WaContact.$key, key)
                    .set(WaContact.$account, account)
                    .set(WaContact.$jid, jid);
            setIfPresent(u, WaContact.$pushName, str(d, "pushName"));
            setIfPresent(u, WaContact.$fullName, str(d, "fullName"));
            setIfPresent(u, WaContact.$firstName, str(d, "firstName"));
            setIfPresent(u, WaContact.$businessName, str(d, "businessName"));
            u.save();
        });
    }

    /** Names arrive from several sources at different times; a later blank must not erase an earlier name. */
    private static <T extends datapotter.arcadedbhelper.ArcadeDoc_I<T>> void setIfPresent(
            datapotter.arcadedbhelper.Upsert<T> u, datapotter.datahelper.Field<T, String> f, String v) {
        if (v != null && !v.isBlank()) u.set(f, v);
    }
}
