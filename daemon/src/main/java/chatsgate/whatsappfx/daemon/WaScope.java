package chatsgate.whatsappfx.daemon;

import leasegate.core.access.Scope;

import java.util.Objects;

/**
 * What one request reaches: one linked number (the user's own, {@code account}), one tier, and one chat, or every chat when
 * {@code chat} is null. {@code kind} and {@code chatName} describe the chat for the person; {@code detail} is shown and never
 * matched. An approval for one number never covers another.
 */
public record WaScope(String account, Tier tier, ChatKind kind, String chat, String chatName, String detail,
                      Integer requestedMinutes) implements Scope<WaScope> {

    public static WaScope of(String account, Tier tier) {
        return new WaScope(account, tier, ChatKind.NONE, null, null, null, null);
    }

    @Override public boolean covers(WaScope r) {
        if (!Objects.equals(account, r.account)) return false;
        if (!tier.covers(r.tier)) return false;
        if (r.tier == Tier.LIST || r.tier == Tier.CONTROL) return true;
        if (r.chat == null) return chat == null;               // "every chat" needs an "every chat" approval
        return chat == null || chat.equals(r.chat);
    }

    @Override public WaScope narrowedTo(WaScope r) { return r; }

    /** 0 reaches every chat; 1 is one chat. */
    @Override public int narrowness() { return tier == Tier.LIST || tier == Tier.CONTROL || chat != null ? 1 : 0; }
}
