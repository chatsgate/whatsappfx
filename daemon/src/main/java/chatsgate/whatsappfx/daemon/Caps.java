package chatsgate.whatsappfx.daemon;

/**
 * How long an approval may last and how many messages it may send, in ONE place so the numbers can be changed in a minute.
 * The user's rule: groups may also be longer. The window offers durations up to these caps; leasegate shortens them further
 * when the approved program sits high up the process tree.
 */
public final class Caps {

    private Caps() {}

    public static final int LIST_MINUTES = 24 * 60;
    public static final int READ_CHAT_MINUTES = 4 * 60;
    public static final int READ_EVERY_MINUTES = 60;
    public static final int SEND_SELF_MINUTES = 4 * 60;
    public static final int SEND_SINGLE_MINUTES = 30;
    public static final int SEND_GROUP_MINUTES = 4 * 60; // groups, channels, status and broadcast lists: asked separately, and (the user) may be longer
    public static final int CONTROL_MINUTES = 15;
    /** The longest any approval may be: eight hours. */
    public static final int MAX_MINUTES = 480;

    /** How many messages one SEND approval may post; the next one asks again. 0 is no limit. */
    public static final int SEND_SINGLE_MESSAGES = 20;
    public static final int SEND_GROUP_MESSAGES = 20;

    public static int minutes(WaScope s) {
        return Math.min(MAX_MINUTES, switch (s.tier()) {
            case LIST -> LIST_MINUTES;
            case READ -> s.chat() == null ? READ_EVERY_MINUTES : READ_CHAT_MINUTES;
            case SEND -> switch (s.kind()) {
                case SELF -> SEND_SELF_MINUTES;
                case GROUP, BROADCAST -> SEND_GROUP_MINUTES;
                default -> SEND_SINGLE_MINUTES;
            };
            case CONTROL -> CONTROL_MINUTES;
        });
    }

    public static int messages(WaScope s) {
        if (s.tier() != Tier.SEND) return 0;
        return switch (s.kind()) {
            case SELF -> 0;
            case GROUP, BROADCAST -> SEND_GROUP_MESSAGES;
            default -> SEND_SINGLE_MESSAGES;
        };
    }
}
