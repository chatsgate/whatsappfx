package chatsgate.whatsappfx.daemon;

import jakarta.servlet.http.HttpServletRequest;
import leasegate.core.LeaseEngine;
import leasegate.core.Outcome;
import leasegate.core.access.*;
import leasegate.core.peer.TcpPeer;
import leasegate.core.win.PeerIdentity;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Paths2;

import module java.base;

/**
 * Which program is asking, asked of the kernel and never of the client, and whether a person has approved that program
 * (that process chain) for what this request needs: the tier, the chat and the linked number. The window that asks is the app's,
 * handed in by the UI; a headless daemon has none and refuses.
 *
 * <p>Nothing is refused outright and no code is needed: groups, channels, status and broadcast lists are each a separate, plainly
 * worded approval of their own, asked per chat. Two switches sit above the approvals. "Wallet open" mode (the user's name; the
 * app's own window turns it on for at most four hours) lets every request of every tier through without a window, still logged
 * and still announced. The unlock walls ({@link Unlocks#REQUIRED}) are built for a stricter product and OFF here. Locking Windows ends nothing:
 * an approval lasts until its time, its program or a revoke, which is how leasegate itself behaves.
 */
public final class CallerGate {

    final ReentrantLock asking = new ReentrantLock();
    public final LeaseEngine<WaScope> engine;
    private final Env defaultEnv;
    volatile Approval<WaScope> approval;
    /** Messages posted so far under each lease, by grant id. */
    private final Map<String, Integer> sent = new ConcurrentHashMap<>();

    /** What the gate needs from the app around it; the app's Hub, or a stand-in in a test. */
    public interface Env {
        String account();
        String ownUser();
        boolean walletOpen();
        Unlocks unlocks();
        AccessLog access();
        ChatInfo.Info chat(String jid);
    }

    /** What an allowed request knows, for the caller to announce it. */
    public record Allowed(ChatInfo.Info chat, String program, String via) {}

    /** The gate of the whole app: each check names the number (the Env) the request is for. */
    public CallerGate() { this(null); }

    public CallerGate(Env defaultEnv) {
        this.defaultEnv = defaultEnv;
        var rules = new WaRules();
        engine = new LeaseEngine<>(rules, r -> approval.decide(r), new KeyedAppStore<>(Paths2.home().resolve("keyed-apps.json"), rules));
    }

    public void approval(Approval<WaScope> window) { approval = window; }

    public Allowed check(HttpServletRequest rq, Need need, String verb) { return check(rq, defaultEnv, need, verb); }

    /** Returns when this request may go ahead; throws {@link SecurityException} with the reason when it may not. {@code hub} is the number it is for. */
    public Allowed check(HttpServletRequest rq, Env hub, Need need, String verb) {
        var pid = TcpPeer.ownerOf(rq.getRemotePort(), rq.getLocalPort());
        if (pid.isEmpty() && hub.walletOpen()) {
            // Where the kernel cannot say who is asking (macOS is untested), a person who opened the wallet has already said every program may.
            hub.access().write(AccessLog.line(Instant.now(), 0, "?", verb, need.tier(), need.chat(), null, noteWith(hub, "wallet open, caller unknown")));
            return new Allowed(need.chat() == null ? new ChatInfo.Info(null, null, ChatKind.NONE) : hub.chat(need.chat()), "?", "");
        }
        if (pid.isEmpty()) throw new SecurityException("WhatsappFx cannot tell which program is asking (" + TcpPeer.unavailableReason() + ")");
        if (approval == null) throw new SecurityException("WhatsappFx has no window to ask in: start the WhatsappFx app");
        var info = need.chat() == null ? new ChatInfo.Info(null, null, ChatKind.NONE) : hub.chat(need.chat());
        walls(hub, need, info, verb);
        var raw = ProcessTree.rawChain(pid.getAsLong());
        var peer = PeerIdentity.resolve(pid.getAsLong());
        var number = hub.ownUser();
        var scope = new WaScope(number == null ? hub.account() : "+" + number, need.tier(), info.kind(), need.chat(), info.name(), verb + caller(rq), null);
        var program = peer.imagePath() == null ? "?" : Path.of(peer.imagePath()).getFileName().toString();
        if (hub.walletOpen()) {
            hub.access().write(AccessLog.line(Instant.now(), pid.getAsLong(), program, verb, need.tier(), need.chat(), info.name(), noteWith(hub, "wallet open")));
            return new Allowed(info, program, caller(rq).replaceFirst("^  ·  from: ", ""));
        }
        var grant = covering(peer, raw, scope, need);
        if (grant.isEmpty()) {
            asking.lock();
            try {
                grant = covering(peer, raw, scope, need);
                if (grant.isEmpty()) {
                    var out = engine.attest(peer, raw, scope, "whatsappcli " + verb);
                    if (out instanceof Outcome.Refused<Grant<WaScope>> r) {
                        Log.info("refused pid " + pid.getAsLong() + ": " + verb + ": " + r.error());
                        throw new SecurityException(r.error() + (r.hint() == null ? "" : " (" + r.hint() + ")"));
                    }
                    grant = Optional.of(((Outcome.Allowed<Grant<WaScope>>) out).value());
                }
            } finally {
                asking.unlock();
            }
        }
        if (need.sends()) sent.merge(grant.get().id(), 1, Integer::sum);
        hub.access().write(AccessLog.line(Instant.now(), pid.getAsLong(), program, verb, need.tier(), need.chat(), info.name(),
                noteWith(hub, need.sends() ? "message " + sent.getOrDefault(grant.get().id(), 1) : null)));
        return new Allowed(info, program, caller(rq).replaceFirst("^  ·  from: ", ""));
    }

    /**
     * The walls, decided before a person is asked. OFF for WhatsappFx ({@link Unlocks#REQUIRED} is false): reading, listing and
     * sending (to one person, to a group, to a channel, status or broadcast list) need only the approval, asked separately for
     * each kind of place. With the switch on (for a stricter product), sending to anyone but yourself needs the AUTOMATION unlock,
     * and a group, channel or broadcast list also the GROUPS unlock, unless the wallet is open.
     */
    private void walls(Env hub, Need need, ChatInfo.Info info, String verb) {
        if (!Unlocks.REQUIRED || need.tier() != Tier.SEND || info.kind() == ChatKind.SELF || hub.walletOpen()) return;
        var number = hub.ownUser();
        if (!hub.unlocks().has(Unlocks.Feature.AUTOMATION, number))
            throw new SecurityException("sending from the command line is not enabled in this build. A note to yourself ('send --draft') needs no unlock; "
                    + "anything else needs an unlock code, which the developer issues for your number and your written reason: Tray > Command-line access");
        if ((info.kind() == ChatKind.GROUP || info.kind() == ChatKind.BROADCAST) && !hub.unlocks().has(Unlocks.Feature.GROUPS, number))
            throw new SecurityException("sending to a group, channel or broadcast list from the command line needs its own unlock code, issued for your number and your written reason: "
                    + "Tray > Command-line access");
    }

    /**
     * The grant that already covers this request, from the parent chain alone. Kept because the message count needs the grant itself.
     * It began as a workaround for leasegate PRP 32 (a full process snapshot, about five seconds here, on every call); leasegate 0.3
     * fixed that (measured 2026-11-04 with 342 processes: snapshot 20 to 100 ms, rawChain about 130 ms for 8 hops), so it is no longer
     * needed for speed, and may move to {@code engine.existing} if that is wanted.
     *
     * <p>A lease that has posted its allowed number of messages is revoked here, so the person is asked again.
     */
    Optional<Grant<WaScope>> covering(PeerIdentity peer, List<ProcessTree.RawLink> raw, WaScope scope, Need need) {
        var chain = raw.stream().map(l -> new AncestryNode(l.pid(), l.imagePath(), l.startInstant(), 0)).toList();
        var found = engine.activeGrants().stream()
                .filter(g -> g.coversClient(peer.imagePath()) && g.anchorStillPresent(chain) && g.scope().covers(scope)).findFirst();
        if (found.isPresent() && need.sends()) {
            var cap = Caps.messages(found.get().scope());
            if (cap > 0 && sent.getOrDefault(found.get().id(), 0) >= cap) {
                Log.info("lease " + found.get().id() + " has posted " + cap + " messages: asking again");
                engine.revokeGrant(found.get().id());
                sent.remove(found.get().id());
                return Optional.empty();
            }
        }
        return found;
    }

    /** The number the line is about, so a log of several numbers reads on its own. */
    private static String noteWith(Env hub, String note) {
        var number = hub.ownUser();
        var who = "number " + (number == null ? hub.account() : "+" + number);
        return note == null ? who : note + "  " + who;
    }

    public int approvals() { return engine.activeGrants().size(); }

    public void revokeAll() { engine.revokeAllNow(); sent.clear(); Log.info("every program approval revoked"); }

    static String caller(HttpServletRequest rq) {
        var cmd = rq.getHeader("X-Wa-Caller-Cmd");
        if (cmd == null) return "";
        try { return "  ·  from: " + new String(Base64.getDecoder().decode(cmd), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return ""; }
    }
}
