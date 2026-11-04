package chatsgate.whatsappfx.daemon;

import com.arcadedb.database.Database;
import chatsgate.whatsappfx.core.Config;
import chatsgate.whatsappfx.core.model.WaChat;

import java.util.Locale;

import static datapotter.arcadedbhelper.Query.query;
import static datapotter.arcadedbhelper.Upsert.upsert;

/**
 * Whether a chat's attachments get downloaded.
 *
 * <p>Three layers, innermost first: the per-chat decision we hold, then the allow list in config,
 * then the global default. The phone's own {@code mediaVisibility} is recorded but deliberately not
 * consulted — it arrives only inside a history-sync snapshot and goes stale silently, so treating it
 * as live would make downloads depend on a value nobody can see changing.
 */
public final class Policy {

    private Policy() {}

    /**
     * One conversation can arrive under two addresses, and a decision about it has to cover both.
     *
     * <p>whatsmeow addresses live traffic by LID while the identity a person recognises and would name
     * is the phone number, and both end up as chat rows. Turning the self chat on by its phone-number
     * jid therefore governed nothing: an image sent from the phone arrived as
     * {@code 12345678901234@lid}, that row said "inherit", and the attachment was skipped in silence.
     * So every lookup follows the pairing rather than trusting the address it was handed.
     */
    public static boolean allows(Database db, Config cfg, String account, String chatJid) {
        String name = null;
        for (var chat : identities(db, account, chatJid)) {
            var explicit = chat.mediaPolicy();
            if ("on".equalsIgnoreCase(explicit)) return true;
            if ("off".equalsIgnoreCase(explicit)) return false;
            if (name == null) name = chat.name();
        }
        if (listed(cfg, chatJid, name)) return true;
        return "on".equalsIgnoreCase(cfg.defaultMediaPolicy());
    }

    /**
     * The chat rows that mean this conversation: the one addressed, plus its partner across the
     * LID / phone-number split — in either direction, since either can be the one named.
     */
    private static java.util.List<WaChat> identities(Database db, String account, String chatJid) {
        var out = new java.util.ArrayList<WaChat>();
        var direct = query(db, WaChat.TYPEDEF).eq(WaChat.$key, WaChat.keyOf(account, chatJid)).firstOrNull();
        if (direct != null) {
            out.add(direct);
            if (direct.pn() != null && !direct.pn().equals(chatJid)) {
                var byPn = query(db, WaChat.TYPEDEF)
                        .eq(WaChat.$key, WaChat.keyOf(account, direct.pn())).firstOrNull();
                if (byPn != null) out.add(byPn);
            }
        }
        // The other direction: we were given the phone number, and the LID row is the one that carries
        // it. A plain equality on an indexed field with no limit, which is a shape that answers.
        for (var c : Sql.all(db, "select jid from WaChat where account = '" + Sql.esc(account)
                + "' and pn = '" + Sql.esc(chatJid) + "'")) {
            var jid = String.valueOf(c.get("jid"));
            if (jid.equals(chatJid)) continue;
            var row = query(db, WaChat.TYPEDEF).eq(WaChat.$key, WaChat.keyOf(account, jid)).firstOrNull();
            if (row != null) out.add(row);
        }
        return out;
    }

    private static boolean listed(Config cfg, String jid, String name) {
        for (var entry : cfg.mediaAllow()) {
            if (entry == null || entry.isBlank()) continue;
            var e = entry.strip().toLowerCase(Locale.ROOT);
            if (jid != null && jid.toLowerCase(Locale.ROOT).contains(e)) return true;
            if (name != null && name.toLowerCase(Locale.ROOT).contains(e)) return true;
        }
        return false;
    }

    /**
     * Written to every address of the conversation, not only the one named. {@link #allows} would
     * follow the pairing anyway; writing both as well means {@code chats} shows the same answer
     * against either row, so nobody reads one of them and concludes the chat is off.
     */
    public static java.util.List<String> set(Database db, String account, String chatJid, String value) {
        var v = value == null ? "inherit" : value.toLowerCase(Locale.ROOT);
        if (!v.equals("on") && !v.equals("off") && !v.equals("inherit")) {
            throw new IllegalArgumentException("policy must be on, off or inherit");
        }
        var targets = new java.util.LinkedHashSet<String>();
        targets.add(chatJid);
        for (var c : identities(db, account, chatJid)) targets.add(c.jid());
        for (var jid : targets) {
            db.transaction(() -> upsert(db, WaChat.TYPEDEF).key(WaChat.$key, WaChat.keyOf(account, jid))
                    .set(WaChat.$account, account)
                    .set(WaChat.$jid, jid)
                    .set(WaChat.$mediaPolicy, v)
                    .save());
        }
        return new java.util.ArrayList<>(targets);
    }
}
