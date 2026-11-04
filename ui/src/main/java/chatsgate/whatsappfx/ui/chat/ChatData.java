package chatsgate.whatsappfx.ui.chat;

import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.daemon.Queries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The window's only door to the index and the connector. Everything here blocks, so it runs on {@link #io}, one
 * virtual thread, which also keeps the window's reads in the order they were asked. Limits are applied in Java by
 * {@link Queries} (an SQL LIMIT is answered wrongly by ArcadeDB).
 */
final class ChatData {

    static final int PAGE = 100;

    final Hub hub;
    final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("wa-chat-io").factory());

    ChatData(Hub hub) {
        this.hub = hub;
    }

    List<ChatRow> chats() {
        var out = new ArrayList<ChatRow>();
        for (var c : Queries.chats(hub.index.db(), hub.account, null, 5000)) {
            var jid = str(c.get("jid"));
            if (jid == null || jid.equals("status@broadcast")) continue;
            var pn = str(c.get("pn"));
            var group = Boolean.TRUE.equals(c.get("isGroup")) || jid.endsWith("@g.us");
            out.add(new ChatRow(jid, label(str(c.get("name")), pn, jid, group), digits(pn != null ? pn : jid),
                    group, num(c.get("lastMsgTs")), num(c.get("unread"))));
        }
        return out;
    }

    /** The newest {@link #PAGE} messages before {@code before} (null: the newest of all), oldest first. */
    List<Msg> messages(String jid, Long before) {
        var out = new ArrayList<Msg>();
        var rows = Queries.messages(hub.index.db(), hub.account, jid, null, before, PAGE);
        // names first, in one connector call: senders in a group, and everyone @mentioned anywhere
        var group = jid.endsWith("@g.us");
        var senders = new ArrayList<String>();
        var texts = new ArrayList<String>();
        var pushNames = new java.util.HashMap<String, String>();
        for (var m : rows) {
            var sender = str(m.get("sender"));
            if (group && sender != null && !Boolean.TRUE.equals(m.get("fromMe"))) senders.add(sender);
            if (sender != null && str(m.get("pushName")) != null) pushNames.putIfAbsent(sender, str(m.get("pushName")));
            texts.add(str(m.get("text")));
        }
        People.learn(hub, senders, texts, pushNames);
        for (var m : rows) {
            var pic = pic(jid, m);
            // a picture is drawn, so the "📎 image" label is dropped and only its caption stays
            var text = pic != null ? (str(m.get("text")) == null ? "" : str(m.get("text"))) : body(m);
            if (text == null) continue;
            var sender = str(m.get("sender"));
            var who = People.name(sender);
            out.add(new Msg(str(m.get("id")), Boolean.TRUE.equals(m.get("fromMe")), num(m.get("ts")),
                    who != null ? who : str(m.get("pushName")), People.mentions(text),
                    Boolean.TRUE.equals(m.get("starred")), str(m.get("reaction")), null, pic));
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * What an attachment shows: its thumbnail out of the journal line (inside the message, nothing to download), and the file when
     * it is on this machine: the filed copy, or for a message we sent the very file we sent from, which is why our own picture shows
     * at once and for older messages too while that file still exists. Every attachment gets one, so a message is never only a paper clip.
     */
    Msg.Pic pic(String jid, Map<String, Object> m) {
        var kind = str(m.get("mediaKind"));
        if (kind == null) return null;
        var id = str(m.get("id"));
        String file = null;
        var path = str(m.get("localPath"));
        if (path != null && "done".equals(str(m.get("mediaState"))) && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path))) file = path;
        byte[] thumb = null;
        int w = 0, h = 0;
        try {
            var row = datapotter.arcadedbhelper.Query.query(hub.index.db(), chatsgate.whatsappfx.core.model.WaMessage.TYPEDEF)
                    .eq(chatsgate.whatsappfx.core.model.WaMessage.$key, chatsgate.whatsappfx.core.model.WaMessage.keyOf(hub.account, jid, id))
                    .firstOrNull();
            var line = row == null || row.joff() == null ? null : hub.journal.lineAt(jid, row.joff());
            if (line != null) {
                var root = chatsgate.whatsappfx.wire.DaemonClient.GSON.fromJson(line, com.google.gson.JsonObject.class);
                var sentFrom = root.has("media") && root.get("media").isJsonObject() && root.getAsJsonObject("media").has("sentFrom")
                        ? root.getAsJsonObject("media").get("sentFrom").getAsString() : null;
                if (file == null && sentFrom != null && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(sentFrom))) file = sentFrom;
                var body = root.getAsJsonObject("body");
                var inner = body == null ? null : body.getAsJsonObject(kind + "Message");
                if (inner != null) {
                    if (inner.has("JPEGThumbnail")) thumb = java.util.Base64.getDecoder().decode(inner.get("JPEGThumbnail").getAsString());
                    if (inner.has("width")) w = inner.get("width").getAsInt();
                    if (inner.has("height")) h = inner.get("height").getAsInt();
                }
            }
        } catch (Exception e) {
            chatsgate.whatsappfx.core.Log.warn("no thumbnail for " + id + ": " + e.getMessage());
        }
        var name = str(m.get("filename"));
        return new Msg.Pic(kind, thumb, file, w, h, name, num(m.get("size")), jid, id);
    }

    /**
     * The file of an attachment, for Open: here already, or fetched through the connector into the Folder of Settings, Attachments
     * (whatever size, because someone clicked it) and waited for. Blocks: run on {@link #io}. Throws when WhatsApp no longer has it.
     */
    java.nio.file.Path fileOf(Msg.Pic p) throws Exception {
        if (p.file() != null && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(p.file()))) return java.nio.file.Path.of(p.file());
        var key = chatsgate.whatsappfx.core.model.WaMessage.keyOf(hub.account, p.chat(), p.id());
        var q = datapotter.arcadedbhelper.Query.query(hub.index.db(), chatsgate.whatsappfx.core.model.WaMessage.TYPEDEF);
        var row = q.eq(chatsgate.whatsappfx.core.model.WaMessage.$key, key).firstOrNull();
        if (row != null && "done".equals(row.mediaState()) && row.localPath() != null && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(row.localPath())))
            return java.nio.file.Path.of(row.localPath());
        var started = hub.media.fetch(p.chat(), p.id());
        if (!"queued".equals(started)) throw new IllegalStateException(started);
        for (var i = 0; i < 360; i++) {
            Thread.sleep(500);
            row = datapotter.arcadedbhelper.Query.query(hub.index.db(), chatsgate.whatsappfx.core.model.WaMessage.TYPEDEF)
                    .eq(chatsgate.whatsappfx.core.model.WaMessage.$key, key).firstOrNull();
            var state = row == null ? null : row.mediaState();
            if ("done".equals(state) && row.localPath() != null) return java.nio.file.Path.of(row.localPath());
            if ("expired".equals(state)) throw new IllegalStateException("WhatsApp no longer has this file");
            if ("failed".equals(state) || "toolarge".equals(state)) throw new IllegalStateException("could not be downloaded (" + state + ")");
        }
        throw new IllegalStateException("still downloading; try again in a moment");
    }

    /** The newest line that has something to show, for the chat list. */
    String snippet(String jid) {
        for (var m : Queries.messages(hub.index.db(), hub.account, jid, null, null, 5)) {
            var text = body(m);
            if (text != null) People.learn(hub, java.util.List.of(), java.util.List.of(text), java.util.Map.of());
            if (text != null) return (Boolean.TRUE.equals(m.get("fromMe")) ? "You: " : "") + People.plain(People.mentions(text)).replace('\n', ' ');
        }
        return "";
    }

    /** Through the connector, as {@code whatsappcli send} does; the connector echoes the sent message into the index. */
    void send(String jid, String text) throws Exception {
        hub.app.allow("write");
        var reply = hub.connector.call("send.text", Map.of("chat", jid, "text", text), 120);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
    }

    /** A file to a chat, as {@code whatsappcli send --file} does: an image as an image, anything else as a document (a picture too big to go as one goes as a document). */
    void sendFile(String jid, java.nio.file.Path file, String caption) throws Exception {
        hub.app.allow("send file");
        var as = Attach.isImage(file) ? "image" : "document";
        try {
            chatsgate.whatsappfx.daemon.Writes.sendMedia(hub, jid, file.toString(), caption, as, null, null, null);
        } catch (Exception e) {
            if (!"image".equals(as) || !String.valueOf(e.getMessage()).contains("document")) throw e;
            chatsgate.whatsappfx.daemon.Writes.sendMedia(hub, jid, file.toString(), caption, "document", null, null, null);
        }
    }

    /** A reply quoting message {@code id}, as {@code whatsappcli reply} sends it. */
    void reply(String jid, String id, String text) throws Exception {
        hub.app.allow("write");
        chatsgate.whatsappfx.daemon.Writes.reply(hub, jid, id, text, null);
    }

    /** New text for one of our own messages. */
    void edit(String jid, String id, String text) throws Exception {
        hub.app.allow("write");
        chatsgate.whatsappfx.daemon.Writes.edit(hub, jid, id, text);
    }

    /** A reaction; an empty emoji takes it away. */
    void react(String jid, String id, String emoji) throws Exception {
        hub.app.allow("write");
        chatsgate.whatsappfx.daemon.Writes.react(hub, jid, id, emoji, null, null);
    }

    void star(String jid, String id, boolean on) throws Exception {
        hub.app.allow("write");
        chatsgate.whatsappfx.daemon.Writes.star(hub, jid, id, on, null, null);
    }

    void close() {
        io.shutdownNow();
    }

    static String body(Map<String, Object> m) {
        var text = str(m.get("text"));
        if (text != null && !text.isBlank()) return text;
        var kind = str(m.get("mediaKind"));
        if (kind == null) return null;
        var file = str(m.get("filename"));
        return "📎 " + kind + (file == null ? "" : "  " + file);
    }

    static String label(String name, String pn, String jid, boolean group) {
        if (name != null && !name.isBlank()) return name;
        if (group) return "Group " + digits(jid);
        return "+" + digits(pn != null ? pn : jid);
    }

    static String digits(String jid) {
        if (jid == null) return null;
        var at = jid.indexOf('@');
        var local = at < 0 ? jid : jid.substring(0, at);
        var colon = local.indexOf(':');
        return colon < 0 ? local : local.substring(0, colon);
    }

    static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}
