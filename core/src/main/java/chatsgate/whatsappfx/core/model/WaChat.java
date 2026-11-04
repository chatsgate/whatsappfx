package chatsgate.whatsappfx.core.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

import static datapotter.arcadedbhelper.ArcadeType.VERTEX;

/** One conversation. Attachment policy is stored here rather than in config, because the target of a rule is a live chat and a name in a file goes stale the moment it is renamed. */
@ArcadeData(type = VERTEX)
public final class WaChat extends WaChat_A {

    String key;
    String account;
    String jid;

    /** The phone-number JID behind a LID chat, when the account knows it. Names key off this. */
    String pn;

    String name;
    Boolean isGroup;

    /** As the phone reported it at sync time: DEFAULT, ON or OFF. A snapshot, never live. */
    String mediaVisibility;

    /** inherit | on | off — our own decision, and the one that actually governs downloads. */
    String mediaPolicy;

    Boolean archived;
    Long pinned;
    Long muteEndTime;
    Long unreadCount;
    Long lastMsgTs;
    Long msgCount;
    Boolean endOfHistory;

    public static final TypeDef<WaChat> TYPEDEF =
            schemaBuilder()
                    .factory(WaChat::new)
                    .unique($key)
                    .lsmIndex($account)
                    .lsmIndex($jid)
                    .lsmIndex($name)
                    .__();

    public static String keyOf(String account, String jid) {
        return account + "|" + jid;
    }
}
