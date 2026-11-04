package chatsgate.whatsappfx.ui.chat;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import chatsgate.whatsappfx.core.Json;
import chatsgate.whatsappfx.daemon.Calls;
import chatsgate.whatsappfx.daemon.Hub;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * New messages as they arrive, for the whole life of the app (the chat window comes and goes): counts what came in
 * per chat since it was last looked at, passes each message to the open window, and raises one tray notice per few
 * seconds for incoming ones (with the chime, which the app adds). Unread counts are this machine's own; nothing is marked read on the phone.
 */
public final class Live {

    static final long NOTICE_GAP_MS = 5000, MAX_AGE_S = 300;

    private static final Map<Hub, Live> ALL = new ConcurrentHashMap<>();

    final Hub hub;
    /** Incoming live messages per chat since it was last opened here. */
    final Map<String, Integer> fresh = new ConcurrentHashMap<>();
    /** Chats opened here: the history-sync unread snapshot no longer applies to them. */
    final Set<String> seen = ConcurrentHashMap.newKeySet();

    private final Set<String> noticeFrom = new LinkedHashSet<>();
    private final Set<String> noticeChats = new LinkedHashSet<>();
    private int noticeCount;
    private String noticeText;
    private boolean noticeDue;

    private Live(Hub hub) { this.hub = hub; }

    /** The unread counts and notice gathering of one number (made on first ask). */
    public static Live of(Hub hub) {
        return ALL.computeIfAbsent(hub, Live::new);
    }

    /** How the app shows a notice: its title, its text, and the chat a click on it should open (null: just the chats). */
    public interface Notice {
        void show(String title, String text, String chat);
    }

    /** Hooks the hub; {@code notice} shows a tray notice. Call once at start. */
    public static void install(Hub hub, Notice notice) {
        var live = of(hub);
        hub.onMessage = d -> {
            var chat = Json.str(d, "chat");
            if (chat == null || chat.equals("status@broadcast")) return;
            var incoming = !Boolean.TRUE.equals(bool(d, "fromMe")) && "live".equals(Json.str(d, "source"));
            var recent = System.currentTimeMillis() / 1000 - num(d, "ts") < MAX_AGE_S;
            var from = incoming && recent ? Calls.callerName(hub, d) : null; // an index read: not on the FX thread
            var text = from == null ? null : preview(d);
            Platform.runLater(() -> {
                var w = ChatWindow.current(hub);
                var looking = w != null && w.stage.isFocused() && chat.equals(w.pane.jid);
                if (incoming && !looking) live.fresh.merge(chat, 1, Integer::sum);
                if (w != null) w.live(chat);
                if (from != null && !looking) live.notice(chat, from, text, notice);
            });
        };
    }

    /** What the badge shows: the snapshot until the chat is opened here, plus what arrived since. */
    long unread(ChatRow row) {
        return (seen.contains(row.jid) ? 0 : row.unread) + fresh.getOrDefault(row.jid, 0);
    }

    public void read(String jid) {
        seen.add(jid);
        fresh.remove(jid);
    }

    /**
     * FX thread. The messages of the few seconds after the first are gathered into one notice: one message shows its
     * sender and text and opens its chat; several name their senders and open the chats.
     */
    private void notice(String chat, String from, String text, Notice show) {
        noticeFrom.add(from);
        noticeChats.add(chat);
        noticeCount++;
        noticeText = from + ": " + text;
        if (noticeDue) return;
        noticeDue = true;
        CompletableFuture.delayedExecutor(NOTICE_GAP_MS, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
            noticeDue = false;
            if (noticeCount == 0) return;
            if (noticeCount == 1) show.show("New WhatsApp message", noticeText, chat);
            else show.show(noticeCount + " new WhatsApp messages", "From " + String.join(", ", noticeFrom),
                    noticeChats.size() == 1 ? noticeChats.iterator().next() : null);
            noticeFrom.clear();
            noticeChats.clear();
            noticeCount = 0;
        }));
    }

    static String preview(JsonObject d) {
        var text = Json.str(d, "text");
        if (text == null || text.isBlank()) {
            var media = d.has("media") && d.get("media").isJsonObject() ? Json.str(d.getAsJsonObject("media"), "kind") : null;
            text = media != null ? "📎 " + media : Json.str(d, "mediaType") != null ? "📎 " + Json.str(d, "mediaType") : "(message)";
        }
        text = text.replace('\n', ' ');
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    private static Boolean bool(JsonObject d, String k) {
        return d.has(k) && d.get(k).isJsonPrimitive() ? d.get(k).getAsBoolean() : null;
    }

    private static long num(JsonObject d, String k) {
        return d.has(k) && d.get(k).isJsonPrimitive() ? d.get(k).getAsLong() : 0;
    }
}
