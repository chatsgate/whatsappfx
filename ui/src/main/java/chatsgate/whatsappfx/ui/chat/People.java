package chatsgate.whatsappfx.ui.chat;

import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Hub;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Who a number is. In groups WhatsApp now addresses people by LID (a number that is not their phone), so an
 * "@mention" arrives as "@<digits>" and a sender as "<digits>@lid". The connector's resolve maps a LID to the phone
 * number and that to the name saved in this account's contacts; failing that, the name the person shows themselves
 * (the push name on their messages). Answers are kept for the life of the app, so each person is asked about once.
 */
final class People {

    /** Marks a mention in message text, so {@link RichText} can draw it as a mention and drop the marks. */
    static final char OPEN = '', CLOSE = '';
    static final Pattern MENTION = Pattern.compile("@(\\d{5,})");

    /** digits of a jid → a name; "" once asked and nothing was found. */
    private static final Map<String, String> names = new ConcurrentHashMap<>();

    private People() {}

    static String digits(String jid) {
        return ChatData.digits(jid);
    }

    /** The known name for this jid, or null. Never blocks. */
    static String name(String jid) {
        if (jid == null) return null;
        var n = names.get(digits(jid));
        return n == null || n.isEmpty() ? null : n;
    }

    /**
     * Asks about the people not yet known: the senders given, and everyone mentioned in {@code texts} (as LID and as
     * phone number, since the digits alone do not say which). One connector call; blocks, so io thread only.
     * {@code pushNames} (jid → the name on their messages) is the fallback.
     */
    static void learn(Hub hub, Collection<String> senders, Collection<String> texts, Map<String, String> pushNames) {
        var ask = new LinkedHashSet<String>();
        for (var s : senders) if (s != null && !names.containsKey(digits(s))) ask.add(s);
        for (var t : texts) {
            if (t == null || t.indexOf('@') < 0) continue;
            var m = MENTION.matcher(t);
            while (m.find()) {
                var d = m.group(1);
                if (names.containsKey(d)) continue;
                ask.add(d + "@lid");
                ask.add(d + "@s.whatsapp.net");
            }
        }
        if (ask.isEmpty()) return;
        try {
            var reply = hub.connector.call("resolve", Map.of("jids", ask.stream().toList()), 30);
            if (reply.get("ok").getAsBoolean()) {
                for (var e : reply.getAsJsonArray("result")) {
                    var row = e.getAsJsonObject();
                    var jid = str(row, "jid");
                    var name = str(row, "name");
                    if (name == null || name.isBlank()) name = str(row, "pushName");
                    if (jid != null && name != null && !name.isBlank()) names.put(digits(jid), name);
                }
            }
        } catch (Exception ex) {
            Log.warn("names not resolved: " + ex.getMessage());
        }
        for (var jid : ask) {
            var d = digits(jid);
            if (names.containsKey(d)) continue;
            var push = pushNames.get(jid);
            if (push != null && !push.isBlank()) names.put(d, push);
            else if (jid.endsWith("@s.whatsapp.net")) names.putIfAbsent(d, "");
        }
        // a mention that matched nothing under either form is settled as unknown, so it is not asked again
        for (var jid : ask) names.putIfAbsent(digits(jid), "");
    }

    /** "@<digits>" → "@Name" where the name is known, marked as a mention; unknown ones stay as they are. */
    static String mentions(String text) {
        if (text == null || text.indexOf('@') < 0) return text;
        var m = MENTION.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            var n = names.get(m.group(1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    n == null || n.isEmpty() ? m.group() : OPEN + "@" + n + CLOSE));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String plain(String text) {
        return text == null ? null : text.replace(String.valueOf(OPEN), "").replace(String.valueOf(CLOSE), "");
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }
}
