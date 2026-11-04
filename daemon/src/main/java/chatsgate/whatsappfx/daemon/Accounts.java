package chatsgate.whatsappfx.daemon;

import chatsgate.whatsappfx.core.Config;
import chatsgate.whatsappfx.core.IndexDb;
import chatsgate.whatsappfx.core.Log;

import module java.base;

/**
 * Every linked number of this PC, and what they share: one index (an embedded database takes one writer), one approval gate (an
 * approval names the number it is for), the access log, the wallet-open switch and the way the app stops. Each number is a
 * {@link Hub} with its own connector child, session store, journal and spool.
 */
public final class Accounts {

    public static final int MAX = 8;

    public final Config config;
    public final IndexDb index = IndexDb.open();
    public final CallerGate callers = new CallerGate();
    public final Unlocks unlocks = new Unlocks();
    public final AccessLog access = new AccessLog();
    private final AccountStore store = new AccountStore();
    private final List<Hub> hubs = new CopyOnWriteArrayList<>();

    /** The UI sets these: told after a number is added (its connector already started), changed, or removed. */
    public volatile Consumer<Hub> onAdded, onChanged, onRemoved;
    /** Told when a number was linked that another number here already is (this one, the other): only sensible for a test. */
    public volatile BiConsumer<Hub, Hub> onDuplicate;
    /** How the app stops itself when asked: the window sets this to JavaFX's own exit; headless there is nothing to unwind but the daemon. */
    public volatile Runnable quitHook;
    /** Asked for by a second launch of the app (verb {@code show} naming no number): the UI offers the numbers. */
    public volatile Runnable showHook;

    private volatile long openUntil;

    public Accounts(Config config) {
        this.config = config;
        for (var spec : store.load()) hubs.add(new Hub(this, spec));
    }

    public List<Hub> all() { return List.copyOf(hubs); }

    public int count() { return hubs.size(); }

    /** The number a request without a name means: the first one, which is the only one until another is added. */
    public Hub primary() { return hubs.getFirst(); }

    /** By short name or key, by the name the person gave it (any case), or by phone number (all of it, or the last four or more digits); null when none or several match. */
    public Hub find(String key) {
        if (key == null || key.isBlank()) return null;
        var k = key.strip().replaceFirst("^[+]", "");
        var hit = hubs.stream().filter(h -> h.shortName().equalsIgnoreCase(k) || h.account.equalsIgnoreCase(k) || h.title().equalsIgnoreCase(k)).toList();
        if (hit.size() == 1) return hit.getFirst();
        if (!hit.isEmpty()) return null;
        if (k.matches("[0-9]{4,}")) hit = hubs.stream().filter(h -> h.ownUser() != null && h.ownUser().endsWith(k)).toList();
        return hit.size() == 1 ? hit.getFirst() : null;
    }

    /** Another number of this app linked to the same phone number as this one, or null. Two windows for one number can only tell apart by name on the command line. */
    public Hub duplicateOf(Hub h) {
        var u = h.ownUser();
        return u == null ? null : hubs.stream().filter(o -> o != h && u.equals(o.ownUser())).findFirst().orElse(null);
    }

    void checkDuplicate(Hub h) {
        var other = duplicateOf(h);
        if (other == null) return;
        Log.warn("number " + h.account + " is linked to the same phone number (+" + h.ownUser() + ") as " + other.account + ": for testing only, the command line needs --account to tell them apart");
        var l = onDuplicate;
        if (l != null) l.accept(h, other);
    }

    /** "work (Work, +…4572)" for each number: what a person can type after --account. */
    public String known() {
        return hubs.stream().map(h -> h.shortName() + " (" + h.title() + (h.ownUser() == null ? ", not linked" : ", " + mask(h.ownUser())) + ")").collect(Collectors.joining(", "));
    }

    /** The end of a phone number, enough to recognise it and not enough to hand it over. */
    public static String mask(String digits) {
        return digits == null ? null : "+…" + digits.substring(Math.max(0, digits.length() - 4));
    }

    public void start() {
        for (var h : hubs) h.startQuietly();
    }

    /** A new number: its folder is made on first start; it opens unlinked, ready for a QR or a code. */
    public synchronized Hub add(String title, String colour) throws IOException {
        if (hubs.size() >= MAX) throw new IllegalStateException("at most " + MAX + " numbers");
        var name = title == null || title.isBlank() ? "Number " + (hubs.size() + 1) : title.strip();
        if (hubs.stream().anyMatch(h -> h.title().equalsIgnoreCase(name))) throw new IllegalArgumentException("a number is already called '" + name + "'");
        var taken = hubs.stream().map(h -> h.colour()).toList();
        var spec = new AccountSpec(AccountSpec.idFor(name, hubs.stream().map(h -> h.account).toList()), name, colour == null ? Palette.free(taken) : colour);
        if (colour != null && taken.contains(colour)) throw new IllegalArgumentException("that colour is already worn by another number");
        var hub = new Hub(this, spec);
        hubs.add(hub);
        save();
        hub.start();
        Log.info("number added: " + spec.id() + " (" + spec.title() + ")");
        var l = onAdded;
        if (l != null) l.accept(hub);
        return hub;
    }

    /** Title and colour are the person's to change at any time; the key (and so the folder and the index rows) never is. */
    public synchronized void update(Hub hub, String title, String colour, String shortName) {
        if (shortName != null) {
            if (!AccountSpec.validShort(shortName)) throw new IllegalArgumentException("a short name is 1 to 16 lower-case letters, digits or dashes");
            if (hubs.stream().anyMatch(h -> h != hub && (h.shortName().equals(shortName) || h.account.equals(shortName)))) throw new IllegalArgumentException("another number already answers to '" + shortName + "'");
            hub.shortName(shortName);
        }
        if (title != null && !title.isBlank()) {
            if (hubs.stream().anyMatch(h -> h != hub && h.title().equalsIgnoreCase(title.strip()))) throw new IllegalArgumentException("a number is already called '" + title.strip() + "'");
            hub.title(title.strip());
        }
        if (colour != null) {
            if (hubs.stream().anyMatch(h -> h != hub && colour.equals(h.colour()))) throw new IllegalArgumentException("that colour is already worn by another number");
            hub.colour(colour);
        }
        save();
        var l = onChanged;
        if (l != null) l.accept(hub);
    }

    /** Stops the number and forgets it here. Its folder and its index rows stay on disk, untouched: adding it back with the same key finds them. */
    public synchronized void remove(Hub hub) {
        if (hubs.size() < 2) throw new IllegalStateException("the only number cannot be removed");
        hubs.remove(hub);
        hub.connector.stop();
        save();
        Log.info("number removed from the list: " + hub.account + " (its data is kept in " + chatsgate.whatsappfx.wire.Paths2.account(hub.account) + ")");
        var l = onRemoved;
        if (l != null) l.accept(hub);
    }

    private void save() {
        store.save(hubs.stream().map(Hub::spec).toList());
    }

    /** "Wallet open" mode (the user's name for it): until this moment every command-line request of every tier, for every number, is allowed without a window, each still written to access.log and each send still shown as a card. Not kept across a restart, on purpose. */
    public boolean walletOpen() { return System.currentTimeMillis() < openUntil; }

    public long walletOpenUntilMillis() { return walletOpen() ? openUntil : 0; }

    /** Up to four hours; 0 turns it off. */
    public void walletOpen(int hours) {
        openUntil = hours <= 0 ? 0 : System.currentTimeMillis() + Math.min(hours, 4) * 3_600_000L;
        Log.info(hours <= 0 ? "wallet open mode off" : "wallet open mode on for " + Math.min(hours, 4) + " h: command-line requests pass without a window");
    }

    /**
     * Stop cleanly, on request from the CLI.
     *
     * <p>Installing a rebuilt connector means restarting this process, and without this the only way was the tray menu: a mouse
     * click standing between an agent and its own build. Killing the JVM instead leaves ArcadeDB to recover on next start, which
     * works but is not free.
     */
    public void requestQuit() {
        var hook = quitHook;
        Log.info("quit requested");
        startQuitWatchdog();
        Thread.ofPlatform().name("wa-quit").start(() -> {
            try {
                // Long enough for the reply to reach the caller before the port closes under it.
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (hook != null) {
                hook.run();
            } else {
                shutdown();
                System.exit(0);
            }
        });
    }

    /**
     * Halt if the graceful path has not finished in time.
     *
     * <p>The orderly shutdown goes through JavaFX, then ArcadeDB's server stop, and either can block: a quit that hung there left
     * the JVM alive with the index already closed, answering "index database is not open" to every call, a state strictly worse
     * than being killed. So a bounded wait, then {@code halt}, which skips hooks and anything stuck in them.
     *
     * <p>Safe to do because of the storage design rather than in spite of it: the journal is the truth, ArcadeDB recovers its
     * index from the write-ahead log, and a job caught mid-flight is marked interrupted on the next start.
     */
    private void startQuitWatchdog() {
        var t = new Thread(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            Log.warn("graceful shutdown did not finish in 10s — halting");
            Runtime.getRuntime().halt(0);
        }, "wa-quit-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** Logged step by step, so a hang names the step it hung on rather than going quiet. */
    public void shutdown() {
        for (var h : hubs) {
            Log.info("shutdown: stopping connector of " + h.account);
            h.connector.stop();
        }
        Log.info("shutdown: closing index");
        index.shutdown();
        Log.info("shutdown: done");
    }
}
