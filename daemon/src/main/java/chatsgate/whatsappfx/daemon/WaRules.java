package chatsgate.whatsappfx.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import leasegate.core.access.*;

import module java.base;

import static leasegate.core.access.ScopeLine.*;

/** The rules of this gate: four tiers, per number and per chat, durations from {@link Caps}, no passphrase. */
public final class WaRules implements LeaseRules<WaScope> {

    @Override public String product() { return "WhatsappFx"; }

    @Override public DurationCaps.Result capByScope(DurationCaps.Result base, WaScope s) {
        var cap = Caps.minutes(s);
        return base.minutes() > cap ? new DurationCaps.Result(cap, label(cap) + " at most for this") : base;
    }

    private static String label(int minutes) { return minutes % 60 == 0 ? (minutes / 60) + (minutes == 60 ? " hour" : " hours") : minutes + " minutes"; }

    @Override public int keyedMaxMinutes(WaScope s) { return Caps.minutes(s); }

    @Override public int riskBase(WaScope s) {
        return switch (s.tier()) { case LIST -> 0; case READ -> s.chat() == null ? 2 : 1; case SEND, CONTROL -> 2; };
    }

    /** WhatsappFx has no passphrase: the stronger tiers are the stronger wording, the stricter caps and, for groups, the unlock. */
    @Override public boolean needsPassphrase(WaScope s) { return false; }

    @Override public List<ScopeLine> describe(WaScope s) {
        var out = new ArrayList<ScopeLine>();
        var number = s.account() == null ? "" : "  ·  " + s.account();
        out.add(switch (s.tier()) {
            case LIST -> caution("to " + s.tier().verb);
            case READ -> s.chat() == null ? wide("to " + s.tier().verb + " in EVERY chat") : caution("to " + s.tier().verb + " in one chat");
            case SEND -> wide("to " + s.tier().verb + ", in one chat");
            case CONTROL -> wide("to " + s.tier().verb);
        });
        if (s.tier() == Tier.SEND && (s.kind() == ChatKind.GROUP || s.kind() == ChatKind.BROADCAST))
            out.add(wide("to MANY people at once: everyone in " + s.kind().noun + " sees it, as you"));
        if (s.chat() != null) out.add(note((s.chatName() == null ? s.chat() : s.chatName()) + "  ·  " + s.kind().label));
        else if (s.tier() == Tier.READ) out.add(wide("every chat and contact in this WhatsApp" + number));
        else if (s.tier() == Tier.SEND) out.add(wide("as you" + number));
        if (s.tier() == Tier.SEND && Caps.messages(s) > 0) out.add(note("at most " + Caps.messages(s) + " messages, then it asks again"));
        if (s.detail() != null) out.add(note("this request: " + s.detail()));
        return out;
    }

    @Override public ScopeLine summary(WaScope s) {
        var where = s.chat() == null ? "every chat" : s.chatName() != null ? s.chatName() : s.chat();
        var line = s.tier().word + "  ·  " + (s.tier() == Tier.LIST || s.tier() == Tier.CONTROL ? "the app" : where);
        return new ScopeLine(line, s.tier().ordinal() >= Tier.SEND.ordinal() || (s.tier() == Tier.READ && s.chat() == null) ? Tone.WIDE : Tone.CAUTION);
    }

    @Override public void writeScope(ObjectNode n, WaScope s) {
        n.put("tier", s.tier().word);
        n.put("account", s.account());
        n.put("kind", s.kind().name());
        if (s.chat() != null) n.put("chat", s.chat());
    }

    @Override public WaScope readScope(JsonNode n) {
        return new WaScope(n.path("account").asText(null), Tier.of(n.path("tier").asText("read")),
                ChatKind.valueOf(n.path("kind").asText("NONE")), n.path("chat").asText(null), null, null, null);
    }

    @Override public WaScope stored(WaScope s) { return new WaScope(s.account(), s.tier(), s.kind(), s.chat(), s.chatName(), null, null); }
}
