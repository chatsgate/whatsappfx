package chatsgate.whatsappfx.daemon;

import com.google.gson.JsonObject;
import chatsgate.whatsappfx.core.Activity;
import chatsgate.whatsappfx.core.Config;
import chatsgate.whatsappfx.core.Connector;
import chatsgate.whatsappfx.core.ConnectorConfig;
import chatsgate.whatsappfx.core.IndexDb;
import chatsgate.whatsappfx.core.Ingest;
import chatsgate.whatsappfx.core.Journal;
import chatsgate.whatsappfx.core.Json;
import chatsgate.whatsappfx.core.Log;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Everything long-lived: the index, the journal, the child process, and what we know about it. */
public final class Hub implements CallerGate.Env {

    public final Accounts accounts;
    public final Config config;
    /** The key of this number: its folder under accounts/ and the account column of every index row. Never changes. */
    public final String account;
    public final Journal journal;
    public final IndexDb index;
    public final Ingest ingest;
    public final Connector connector;
    /** The one approval gate of the app: an approval names the number it is for, so one gate serves every number. */
    public final CallerGate callers;
    public final AppGate app = new AppGate();
    public final Unlocks unlocks;
    public final AccessLog access;
    private volatile AccountSpec spec;

    @Override public String account() { return account; }
    @Override public Unlocks unlocks() { return unlocks; }
    @Override public AccessLog access() { return access; }

    public AccountSpec spec() { return spec; }
    /** The name the person gave this number, shown in its window, the tray and the notices. */
    public String title() { return spec.title(); }
    public void title(String t) { spec = spec.withTitle(t); }
    /** Its colour, #rrggbb. */
    public String colour() { return spec.colour(); }
    public void colour(String c) { spec = spec.withColour(c); }
    /** What the command line calls it (--account): the short name the person set, else the key. */
    public String shortName() { return spec.key(); }
    public void shortName(String s) { spec = spec.withShort(s); }
    @Override public ChatInfo.Info chat(String jid) { return ChatInfo.of(this, jid); }
    public final Jobs jobs;
    public final MediaWorker media;

    /** Told about every call event after it is recorded; the tray sets it to raise a notification. */
    public volatile java.util.function.Consumer<JsonObject> onCall;
    /** Told about every message after it is indexed; the UI sets it for live chat updates and notices. */
    public volatile java.util.function.Consumer<JsonObject> onMessage;
    /** Told when WhatsApp ends this device's link; the tray says so at once, since the window is usually closed. */
    public volatile Runnable onLinkLost;
    /** Brings the UI forward; asked for by a second launch of the app (verb {@code show}). */
    public volatile Runnable showHook;
    /** Opens the settings window (verb {@code show} with window "settings"). */
    public volatile Runnable showSettingsHook;
    /** Opens the chats on one chat (verb {@code show} with a chat). */
    public volatile java.util.function.Consumer<String> showChatHook;
    /** Raises a notice for something the command line did (a message sent, one changed or deleted): title, then one line. */
    public volatile java.util.function.BiConsumer<String, String> cliNoticeHook;
    /** Opens a chat in the chats window with this text in its write box and nothing sent (verb {@code draft}): chat, text. */
    public volatile java.util.function.BiConsumer<String, String> draftHook;
    /** Raises a test notification in the UI (verb {@code notify-test}): "message" or "call". */
    public volatile java.util.function.Consumer<String> testNoticeHook;

    private final AtomicReference<JsonObject> lastStatus = new AtomicReference<>(new JsonObject());
    private final AtomicReference<String> lastQr = new AtomicReference<>();
    private final AtomicReference<String> lastPairCode = new AtomicReference<>();
    private volatile long lastEventAt, lastMessageAt, connectedAt;
    private volatile int restarts;

    Hub(Accounts accounts, AccountSpec spec) {
        this.accounts = accounts;
        this.spec = spec;
        this.config = accounts.config;
        this.account = spec.id();
        this.callers = accounts.callers;
        this.unlocks = accounts.unlocks;
        this.access = accounts.access;
        this.journal = new Journal(account);
        this.index = accounts.index;
        this.ingest = new Ingest(account, journal, index.db());
        this.connector = new Connector(ConnectorConfig.of(account, config), this::onEvent, this::onExit);
        this.jobs = new Jobs(index.db(), account);
        this.media = new MediaWorker(this);
    }

    public void start() throws IOException {
        var orphans = jobs.reapOrphans();
        if (orphans > 0) Log.warn(orphans + " job(s) left running by a previous process marked interrupted");
        var stale = media.clearStaleQueued();
        if (stale > 0) Log.info(stale + " attachment(s) marked queued by a previous process reset to none");
        connector.start();
    }

    /** One number that cannot start (its store is locked, its connector is missing) must not stop the others. */
    void startQuietly() {
        try {
            start();
        } catch (Exception e) {
            Log.error("number " + account + " did not start", e);
        }
    }

    private void onEvent(JsonObject evt) {
        lastEventAt = Instant.now().getEpochSecond();
        var name = Json.str(evt, "event");
        var data = Json.obj(evt, "data");
        if (name == null) return;
        // Real work only: connection-state chatter is not activity, or the idle trim would never run.
        switch (name) {
            case "message", "chat", "contact", "mediaready", "mediaretry", "pair" -> Activity.touch();
            default -> { }
        }
        try {
            switch (name) {
                case "message" -> {
                    ingest.message(data);
                    lastMessageAt = lastEventAt;
                    media.consider(data);
                    nameGroup(Json.str(data, "chat"));
                    var l = onMessage;
                    if (l != null) l.accept(data);
                }
                case "call" -> {
                    ingest.message(data);
                    var l = onCall;
                    if (l != null) l.accept(data);
                }
                case "chat" -> ingest.chat(data);
                case "contact" -> ingest.contact(data);
                case "qr" -> lastQr.set(Json.str(data, "code"));
                case "paircode" -> lastPairCode.set(Json.str(data, "code"));
                case "pair" -> onPaired(data);
                case "ready" -> lastStatus.set(data);
                case "connected" -> {
                    lastStatus.set(data);
                    connectedAt = lastEventAt;
                    lastQr.set(null);
                    background.execute(() -> { try { Names.groups(this); } catch (Exception e) { Log.warn("group names not synced: " + e.getMessage()); } });
                }
                // Each of these now carries the whole session state, which is the point: this copy used
                // to be written only on "ready" and "connected", so a logout changed nothing here and
                // status went on reporting a paired device for two days after WhatsApp removed it.
                case "disconnected" -> {
                    lastStatus.set(data);
                    connectedAt = 0;
                }
                case "loggedout" -> onLoggedOut(data);
                case "session.reset" -> onSessionReset(data);
                case "banned" -> {
                    lastStatus.set(data);
                    Log.warn("account temporarily banned: " + Json.str(data, "code"));
                }
                case "mediaready" -> media.completed(data);
                case "mediaretry" -> media.retryOutcome(data);
                default -> { }
            }
        } catch (Exception e) {
            Log.error("event " + name + " failed", e);
        }
    }

    /** Lookups that call the connector: never on its reader thread, which is the one that would deliver the reply. */
    private final java.util.concurrent.ExecutorService background =
            java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().name("wa-hub-bg").factory());
    private final java.util.Set<String> groupsAsked = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A group heard from that the index has no name for gets one looked up, once per run. */
    private void nameGroup(String chat) {
        if (chat == null || !chat.endsWith("@g.us") || !groupsAsked.add(chat)) return;
        background.execute(() -> { if (!Names.named(this, chat)) Names.group(this, chat); });
    }

    private void onLoggedOut(JsonObject data) {
        lastStatus.set(data);
        connectedAt = 0;
        Log.warn("WhatsApp logged this device out: " + Json.str(data, "reason")
                + " — the connector is re-arming itself, then this machine has to be paired again");
        var l = onLinkLost;
        if (l != null) l.run();
    }

    /**
     * The connector has thrown the dead device away and built a fresh one, so pairing works again.
     * Nothing is lost that WhatsApp had not already taken: the journal and the index are a separate
     * store and are untouched by any of this.
     */
    private void onSessionReset(JsonObject data) {
        lastStatus.set(data);
        connectedAt = 0;
        lastQr.set(null);
        lastPairCode.set(null);
        Log.warn("linked device forgotten (" + Json.str(data, "reason")
                + ") — pair again with: whatsappcli pair --phone <digits>");
    }

    private void onPaired(JsonObject data) {
        if (!"success".equals(Json.str(data, "status"))) return;
        lastStatus.set(data);
        lastQr.set(null);
        lastPairCode.set(null);
        Log.info("paired as " + Json.str(data, "jid"));
        accounts.checkDuplicate(this);
    }

    /** A dead connector is a restart, not a dead daemon — the whole reason it is a separate process. */
    private void onExit(int code) {
        connectedAt = 0;
        Log.warn("connector exited with code " + code);
        if (restarts++ > 20) {
            Log.error("connector restart limit reached; staying down", null);
            return;
        }
        try {
            Thread.sleep(Math.min(30_000L, 1000L * restarts));
            connector.start();
            Log.info("connector restarted (attempt " + restarts + ")");
        } catch (Exception e) {
            Log.error("connector restart failed", e);
        }
    }

    /**
     * The folder attachments go under on the shared drive: the account's own number, not the internal
     * account id. The two are deliberately separate — the internal id names a local directory and must
     * be stable from before pairing, while this one has to be recognisable to a person browsing Drive.
     */
    public String driveFolder() {
        var s = lastStatus.get();
        // lastJid, because being logged out must not silently refile a paired account's attachments
        // under a placeholder folder the moment WhatsApp drops the link.
        var jid = Json.str(s, "jid");
        if (jid == null) jid = Json.str(s, "lastJid");
        if (jid == null) return account;
        var user = jid.split("[:@]")[0];
        return user.isBlank() ? account : "+" + user;
    }

    /** "Wallet open" mode is one switch for the whole app (see {@link Accounts#walletOpen()}). */
    @Override public boolean walletOpen() { return accounts.walletOpen(); }

    public long walletOpenUntilMillis() { return accounts.walletOpenUntilMillis(); }

    public void walletOpen(int hours) { accounts.walletOpen(hours); }

    /** The chat id of the user's own "message yourself" chat, or null before the first link. */
    public String ownChat() { var u = ownUser(); return u == null ? null : u + "@s.whatsapp.net"; }

    /** The user part of the linked number (digits), or null before the first link. */
    @Override public String ownUser() {
        var s = lastStatus.get();
        var jid = Json.str(s, "jid");
        if (jid == null) jid = Json.str(s, "lastJid");
        return ChatInfo.user(jid);
    }

    public Map<String, Object> status() {
        var m = new LinkedHashMap<String, Object>();
        m.put("account", account);
        var same = accounts.duplicateOf(this);
        m.put("sameNumberAs", same == null ? null : same.account);
        m.put("title", title());
        m.put("colour", colour());
        m.put("driveFolder", driveFolder());
        m.put("connectorAlive", connector.alive());
        m.put("restarts", restarts);
        m.put("approvals", callers.approvals());
        var s = lastStatus.get();
        m.put("loggedIn", Boolean.TRUE.equals(Json.bool(s, "loggedIn")));
        m.put("needsPairing", Boolean.TRUE.equals(Json.bool(s, "needsPairing")));
        m.put("jid", Json.str(s, "jid"));
        m.put("lastJid", Json.str(s, "lastJid"));
        m.put("pushName", Json.str(s, "pushName"));
        m.put("logoutReason", Json.str(s, "logoutReason"));
        m.put("logoutAt", Json.num(s, "logoutAt"));
        m.put("banReason", Json.str(s, "banReason"));
        m.put("banUntil", Json.num(s, "banUntil"));
        m.put("connectedSince", connectedAt == 0 ? null : connectedAt);
        m.put("lastEventAt", lastEventAt == 0 ? null : lastEventAt);
        m.put("lastMessageAt", lastMessageAt == 0 ? null : lastMessageAt);
        // A linked device left offline for about a fortnight is deregistered by WhatsApp, so the age
        // of the last contact is the number that predicts a forced re-pair.
        m.put("secondsSinceLastEvent", lastEventAt == 0 ? null : Instant.now().getEpochSecond() - lastEventAt);
        m.put("pendingQr", lastQr.get());
        m.put("pairCode", lastPairCode.get());
        m.put("autostart", Autostart.enabled());
        m.putAll(media.stats());
        return m;
    }

    /** Stops the whole app, on request from the command line (every number, then the index). */
    public void requestQuit() { accounts.requestQuit(); }
}
