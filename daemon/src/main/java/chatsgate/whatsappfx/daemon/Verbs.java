package chatsgate.whatsappfx.daemon;

import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import chatsgate.whatsappfx.core.Json;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.DaemonClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** The verb surface. Each verb declares what it needs a person to have approved (see {@link Need}), except ping, show and notify-test. */
public final class Verbs {

    /** Verbs that are about the whole app, not one number: they need no --account. */
    private static final java.util.Set<String> GLOBAL = java.util.Set.of("ping", "show", "notify-test", "accounts", "account.add", "quit", "lock", "key.recovery", "key.restore");

    private final Accounts accounts;

    public Verbs(Accounts accounts) {
        this.accounts = accounts;
    }

    public void mount(Javalin app) {
        open(app, "ping", (hub, b) -> Map.of("ok", true));
        list(app, "status", (hub, b) -> hub.status());
        list(app, "chats", (hub, b) -> Queries.chats(hub.index.db(), hub.account, Json.str(b, "contains"), limit(b, 200)));
        readChat(app, "messages", (hub, b) -> Queries.messages(hub.index.db(), hub.account, Json.str(b, "chat"),
                Json.str(b, "contains"), Json.num(b, "before"), limit(b, 100)));
        list(app, "contacts", (hub, b) -> Queries.contacts(hub.index.db(), hub.account, Json.str(b, "contains"), limit(b, 200)));
        readChat(app, "media", (hub, b) -> Queries.media(hub.index.db(), hub.account, Json.str(b, "chat"),
                Json.str(b, "state"), limit(b, 200)));
        readChat(app, "media.fetch", (hub, b) -> Json.str(b, "id") != null
                ? Map.of("status", hub.media.fetch(require(b, "chat"), Json.str(b, "id")))
                : hub.media.backfill(Json.str(b, "chat")));
        control(app, "policy.set", (hub, b) -> {
            var applied = Policy.set(hub.index.db(), hub.account, require(b, "chat"), require(b, "policy"));
            return Map.of("chat", require(b, "chat"), "policy", require(b, "policy"), "appliedTo", applied);
        });
        list(app, "job", (hub, b) -> hub.jobs.report(require(b, "id")));
        list(app, "jobs", (hub, b) -> hub.jobs.recent(limit(b, 20)));
        // Answers {"rows":[...],"notes":[...]} rather than a bare array: a wrong column name is not an
        // error here, it is a column of nulls, so the reply has to carry room to say so.
        readAll(app, "query", (hub, b) -> Columns.ask(hub.index.db(), require(b, "sql"), limit(b, 500)));
        list(app, "columns", (hub, b) -> Columns.describe(Json.str(b, "type")));
        readAll(app, "names.sync", (hub, b) -> Names.sync(hub));
        readAll(app, "reindex", (hub, b) -> Reindex.start(hub));
        gated(app, "autostart", (hub, b) -> Json.str(b, "set") == null ? Need.list() : Need.control(), (hub, b) -> switch (Json.str(b, "set") == null ? "status" : Json.str(b, "set")) {
            case "on" -> Autostart.enable();
            case "off" -> Autostart.disable();
            case "status" -> Autostart.status();
            default -> throw new IllegalArgumentException("set must be on, off or status");
        });
        control(app, "pair.qr", (hub, b) -> connector(hub, "pair.qr", Map.of()));
        control(app, "pair.code", (hub, b) -> connector(hub, "pair.code",
                Map.of("phone", require(b, "phone"), "notify", true)));
        control(app, "connect", (hub, b) -> connector(hub, "connect", Map.of()));
        control(app, "disconnect", (hub, b) -> connector(hub, "disconnect", Map.of()));
        readChat(app, "history.more", (hub, b) -> connector(hub, "history.request", Map.of(
                "chat", require(b, "chat"), "id", Json.str(b, "id"), "sender", Json.str(b, "sender"),
                "fromMe", Boolean.TRUE.equals(Json.bool(b, "fromMe")),
                "ts", Json.num(b, "ts") == null ? 0 : Json.num(b, "ts"),
                "count", Json.num(b, "count") == null ? 50 : Json.num(b, "count"))));

        list(app, "who", (hub, b) -> {
            var ids = new java.util.ArrayList<String>();
            for (var e : b.getAsJsonArray("ids")) {
                var s = e.getAsString().replaceAll("^@", "");
                if (s.contains("@")) ids.add(s);
                else { ids.add(s + "@lid"); ids.add(s + "@s.whatsapp.net"); }
            }
            var r = hub.connector.call("resolve", Map.of("jids", ids), 60);
            if (!r.get("ok").getAsBoolean()) throw new IllegalStateException(r.get("error").getAsString());
            return Map.of("people", r.get("result"));
        });

        open(app, "notify-test", (hub, b) -> {
            var h = hub.testNoticeHook;
            var kind = Json.str(b, "kind") == null ? "message" : Json.str(b, "kind");
            if (h != null) h.accept(kind);
            return Map.of("shown", h != null, "kind", kind);
        });

        open(app, "show", (hub, b) -> {
            if (Boolean.TRUE.equals(Json.bool(b, "all"))) {
                accounts.all().forEach(h -> { var s = h.showHook; if (s != null) s.run(); });
                return Map.of("shown", true, "all", accounts.count());
            }
            // no number named and several linked: the app asks the person which (what a click on its tray icon does)
            if (b.has("_unnamed") && accounts.count() > 1 && Json.str(b, "chat") == null && Json.str(b, "window") == null) {
                var s = accounts.showHook;
                if (s != null) s.run();
                return Map.of("shown", s != null, "chose", true);
            }
            if ("settings".equals(Json.str(b, "window"))) {
                var s = hub.showSettingsHook;
                if (s != null) s.run();
                return Map.of("shown", s != null, "window", "settings");
            }
            var chat = Json.str(b, "chat");
            var c = hub.showChatHook;
            if (chat != null && c != null) { c.accept(chat); return Map.of("shown", true, "chat", chat); }
            var h = hub.showHook;
            if (h != null) h.run();
            return Map.of("shown", h != null);
        });

        control(app, "key.recovery", (hub, b) -> Map.of("recoveryCode", chatsgate.whatsappfx.core.KeyStore.recoveryCode(), "protection", chatsgate.whatsappfx.core.KeyStore.protection()));
        control(app, "key.restore", (hub, b) -> {
            chatsgate.whatsappfx.core.KeyStore.restore(require(b, "code"), Boolean.TRUE.equals(Json.bool(b, "replace")));
            return Map.of("restored", true, "next", "restart WhatsappFx so the connector opens its store with this key");
        });
        control(app, "quit", (hub, b) -> {
            hub.requestQuit();
            return Map.of("stopping", true);
        });

        // The numbers: which are linked, their names and colours. Naming one is `--account` (the header X-Wa-Account), or "account" in the body.
        // Open, like `cdpg profiles`: a program must be able to learn what to ask for before it has been approved for anything; the phone numbers are masked.
        open(app, "accounts", (hub, b) -> accounts.all().stream().map(Verbs::summary).toList());
        control(app, "account.add", (hub, b) -> summary(accounts.add(Json.str(b, "title"), Palette.parse(Json.str(b, "colour")))));
        control(app, "account.set", (hub, b) -> {
            var colour = Palette.parse(Json.str(b, "colour"));
            if (Json.str(b, "colour") != null && colour == null) throw new IllegalArgumentException("colour must be one of " + String.join(", ", Palette.NAMES) + " or #rrggbb");
            accounts.update(hub, Json.str(b, "title"), colour, Json.str(b, "short"));
            return summary(hub);
        });
        control(app, "account.remove", (hub, b) -> {
            accounts.remove(hub);
            return Map.of("removed", hub.account, "dataKept", chatsgate.whatsappfx.wire.Paths2.account(hub.account).toString());
        });

        list(app, "lock", (hub, b) -> {
            hub.callers.revokeAll();
            return Map.of("approvals", 0);
        });

        // The sender and the from-me flag are looked up from the message row rather than demanded from
        // the caller: an addressing mistake here is silent — the server accepts a key for a message
        // that does not exist and nothing appears in the chat.
        change(app, "react", false, (hub, b) -> Writes.react(hub, require(b, "chat"), require(b, "id"),
                Json.str(b, "emoji"), Json.str(b, "sender"), Json.bool(b, "fromMe")));
        change(app, "star", false, (hub, b) -> Writes.star(hub, require(b, "chat"), require(b, "id"),
                !Boolean.FALSE.equals(Json.bool(b, "starred")), Json.str(b, "sender"), Json.bool(b, "fromMe")));
        change(app, "edit", true, (hub, b) -> Writes.edit(hub, require(b, "chat"), require(b, "id"), require(b, "text")));
        gated(app, "delete", (hub, b) -> Need.change(require(b, "chat"), true), (hub, b) -> connector(hub, "delete", Map.of("chat", require(b, "chat"), "id", require(b, "id"))));
        gated(app, "draft", (hub, b) -> {
            var own = hub.ownChat();
            if (own == null) throw new IllegalStateException("this PC is not linked to a number yet");
            return new Need(Tier.SEND, own, false, false, null);
        }, (hub, b) -> Drafts.make(hub, require(b, "chat"), require(b, "text")));
        send(app, "reply", (hub, b) -> Writes.reply(hub, require(b, "chat"), require(b, "id"),
                require(b, "text"), Json.str(b, "sender")));
        send(app, "send.text", (hub, b) -> connector(hub, "send.text",
                Map.of("chat", require(b, "chat"), "text", require(b, "text"))));
        send(app, "send.media", (hub, b) -> Writes.sendMedia(hub, require(b, "chat"), require(b, "path"),
                Json.str(b, "caption"), Json.str(b, "as"), Json.str(b, "filename"),
                Json.str(b, "quoteId"), Json.str(b, "sender")));

        change(app, "call.reject", false, (hub, b) -> Calls.reject(hub, require(b, "chat"), require(b, "id")));

        // Gated, because it ends a working link and the phone has to be in somebody's hand to restore
        // one. The automatic path needs no gate and does not come through here: when WhatsApp itself
        // removes the device the connector re-arms on its own, having lost nothing left to lose.
        control(app, "logout", (hub, b) -> connector(hub, "logout",
                Map.of("local", Boolean.TRUE.equals(Json.bool(b, "local")))));
    }

    /** The number a request names (header {@code X-Wa-Account}, or {@code account} in the body), or null when it names none. */
    private Hub named(Context ctx, JsonObject body) {
        var key = ctx.header("X-Wa-Account");
        // base64 like the other caller headers, because a name the person gave may not be Latin-1
        if (key != null) try { key = new String(java.util.Base64.getDecoder().decode(key), java.nio.charset.StandardCharsets.UTF_8); } catch (IllegalArgumentException plain) { }
        if (key == null || key.isBlank()) key = Json.str(body, "account");
        if (key == null || key.isBlank()) return null;
        var hub = accounts.find(key);
        if (hub == null) throw new IllegalArgumentException("no single number called '" + key + "' (it names none, or several: two numbers may share a name part or a phone number; use the key. Known: " + accounts.known() + ")");
        return hub;
    }

    static Map<String, Object> summary(Hub h) {
        var s = h.status();
        var m = new LinkedHashMap<String, Object>();
        m.put("account", h.account);
        m.put("title", h.title());
        m.put("colour", h.colour());
        m.put("colourName", Palette.nameOf(h.colour()));
        m.put("short", h.shortName());
        m.put("number", Accounts.mask(h.ownUser()));
        m.put("loggedIn", s.get("loggedIn"));
        m.put("online", s.get("connectedSince") != null);
        m.put("connectorAlive", s.get("connectorAlive"));
        m.put("sameNumberAs", s.get("sameNumberAs"));
        return m;
    }

    private void open(Javalin app, String verb, Handler h) {
        gated(app, verb, null, h);
    }

    private void list(Javalin app, String verb, Handler h) {
        gated(app, verb, (hub, b) -> Need.list(), h);
    }

    /** Reads one chat when the request names one, else every chat. */
    private void readChat(Javalin app, String verb, Handler h) {
        gated(app, verb, (hub, b) -> Need.read(Json.str(b, "chat")), h);
    }

    private void readAll(Javalin app, String verb, Handler h) {
        gated(app, verb, (hub, b) -> Need.read(null), h);
    }

    private void control(Javalin app, String verb, Handler h) {
        gated(app, verb, (hub, b) -> Need.control(), h);
    }

    /** A new message in one chat: counted against the approval and announced. */
    private void send(Javalin app, String verb, Handler h) {
        gated(app, verb, (hub, b) -> Need.send(require(b, "chat")), h);
    }

    /** A reaction, star, edit or delete in one chat. */
    private void change(Javalin app, String verb, boolean announce, Handler h) {
        gated(app, verb, (hub, b) -> Need.change(require(b, "chat"), announce), h);
    }

    /** {@code need} says what the request needs a person to have approved for the calling program; null needs nothing. */
    private void gated(Javalin app, String verb, BiFunction<Hub, JsonObject, Need> need, Handler h) {
        app.post("/wa/" + verb, ctx -> respond(ctx, verb, need, h));
    }

    private void respond(Context ctx, String verb, BiFunction<Hub, JsonObject, Need> need, Handler h) {
        try {
            var body = ctx.body().isBlank() ? new JsonObject()
                    : DaemonClient.GSON.fromJson(ctx.body(), JsonObject.class);
            var hub = named(ctx, body);
            if (hub == null) {
                // like `cdpg` asking for a profile: with several numbers a command must say which, and is told what exists
                if (accounts.count() > 1 && !GLOBAL.contains(verb))
                    throw new IllegalArgumentException("WhatsappFx has " + accounts.count() + " numbers: " + accounts.known()
                            + ". Say which with --account <short name or phone number> (or set WHATSAPPFX_ACCOUNT). 'whatsappcli accounts' lists them.");
                hub = accounts.primary();
                body.addProperty("_unnamed", true); // only `show` reads this: no number named is a question for the person
            }
            var n = need == null ? null : need.apply(hub, body);
            var allowed = n == null ? null : hub.callers.check(ctx.req(), hub, n, verb);
            var result = h.apply(hub, body);
            if (n != null && n.announce()) announce(hub, allowed, verb, body);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(DaemonClient.GSON.toJson(result));
        } catch (SecurityException e) {
            Log.warn("verb " + verb + " refused: " + e.getMessage());
            ctx.status(403).contentType("application/json; charset=utf-8")
                    .result(DaemonClient.GSON.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        } catch (Exception e) {
            Log.warn("verb failed: " + e.getMessage());
            ctx.status(400).contentType("application/json; charset=utf-8")
                    .result(DaemonClient.GSON.toJson(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    /** A notice after something the command line did that others can see: a send, an edit, a delete. */
    private void announce(Hub hub, CallerGate.Allowed who, String verb, JsonObject body) {
        var hook = hub.cliNoticeHook;
        if (hook == null || who == null) return;
        var what = switch (verb) {
            case "edit" -> "changed a message in";
            case "delete" -> "deleted a message in";
            case "send.media" -> "sent a file to";
            default -> "sent a message to";
        };
        var name = who.chat().name() != null ? who.chat().name() : who.chat().jid();
        var text = Json.str(body, "text");
        var first = text == null ? "" : text.lines().findFirst().orElse("");
        hook.accept("whatsappcli " + what + " " + name, first.length() > 120 ? first.substring(0, 120) + "…" : first);
    }

    private Object connector(Hub hub, String cmd, Map<String, Object> args) throws Exception {
        var reply = hub.connector.call(cmd, args, 120);
        if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("result", reply.get("result"));
        return out;
    }

    private static int limit(JsonObject b, int fallback) {
        var v = Json.num(b, "limit");
        return v == null ? fallback : (int) Math.max(1, Math.min(100_000, v));
    }

    private static String require(JsonObject b, String field) {
        var v = Json.str(b, field);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("'" + field + "' is required");
        return v;
    }

    interface Handler {
        Object handle(Hub hub, JsonObject body) throws Exception;

        default Object apply(Hub hub, JsonObject body) {
            try {
                return handle(hub, body);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }
}
