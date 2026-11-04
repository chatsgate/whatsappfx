package chatsgate.whatsappfx.ui;

import java.time.Duration;
import java.util.Map;

/** The status block, in words rather than JSON, because a person is reading it. */
public final class StatusText {

    private StatusText() {}

    public static String of(Map<String, Object> s) {
        var sb = new StringBuilder();
        line(sb, "Account", str(s, "account"));
        line(sb, "WhatsApp", whatsapp(s));
        line(sb, "Connection", s.get("connectedSince") == null ? "offline"
                : "online since " + ago((Long) s.get("connectedSince")) + " ago");
        line(sb, "Connector", Boolean.TRUE.equals(s.get("connectorAlive"))
                ? "running" + (num(s, "restarts") > 0 ? " (restarted " + num(s, "restarts") + "x)" : "")
                : "DOWN");
        line(sb, "Last contact", s.get("secondsSinceLastEvent") == null ? "never"
                : human((Long) s.get("secondsSinceLastEvent")) + " ago" + warning(s));
        line(sb, "Programs", num(s, "approvals") + " approved to use whatsappcli");
        line(sb, "Autostart", Boolean.TRUE.equals(s.get("autostart"))
                ? "on — starts at logon" : "off  (F7 in settings turns it on; 14 days offline forces a re-link)");
        if (s.get("banReason") != null) line(sb, "Ban", String.valueOf(s.get("banReason"))
                + (s.get("banUntil") == null ? "" : ", until " + java.time.Instant.ofEpochSecond((Long) s.get("banUntil"))));
        // Filed, expired and failed are totals off the index rather than this session's tallies, so a
        // restart does not report a completed backfill as nothing.
        line(sb, "Attachments", num(s, "mediaFiled") + " filed, " + num(s, "mediaQueued") + " queued, "
                + num(s, "mediaNotFetched") + " not fetched, " + num(s, "mediaExpired") + " expired, "
                + num(s, "mediaFailed") + " failed, " + num(s, "mediaTooLarge") + " too large");
        return sb.toString();
    }

    /**
     * The line that used to lie. It read loggedIn off a copy of the status that was refreshed only
     * when the connector said "ready" or "connected", so a device WhatsApp had removed went on being
     * reported as paired — here and in the CLI — until somebody tried to use it two days later.
     */
    private static String whatsapp(Map<String, Object> s) {
        if (Boolean.TRUE.equals(s.get("loggedIn")))
            return "paired as " + str(s, "pushName") + "  (" + str(s, "jid") + ")";
        var why = s.get("logoutReason");
        var when = s.get("logoutAt");
        return (why == null ? "not paired" : "NOT PAIRED — " + why
                + (when == null ? "" : ", " + ago((Long) when) + " ago"))
                + "\n" + " ".repeat(15) + "link again in settings: F3 by code, F4 by QR";
    }

    /**
     * WhatsApp deregisters a linked device that stays offline for roughly a fortnight, so the age of
     * the last contact is the one number that predicts a forced re-pair. Warn well before it.
     */
    private static String warning(Map<String, Object> s) {
        var secs = (Long) s.get("secondsSinceLastEvent");
        return secs == null ? "" : warning(secs);
    }

    static String warning(long secs) {
        var days = secs / 86400;
        if (days >= 10) return "   ⚠ re-pairing likely at 14 days offline";
        if (days >= 5) return "   (run it regularly — 14 days offline forces a re-pair)";
        return "";
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append(String.format("%-14s %s%n", label, value));
    }

    private static String str(Map<String, Object> s, String k) {
        var v = s.get(k);
        return v == null ? "—" : String.valueOf(v);
    }

    private static long num(Map<String, Object> s, String k) {
        var v = s.get(k);
        return v instanceof Number n ? n.longValue() : 0;
    }

    static String ago(long epochSeconds) {
        return human(java.time.Instant.now().getEpochSecond() - epochSeconds);
    }

    public static String human(long seconds) {
        var d = Duration.ofSeconds(Math.max(0, seconds));
        if (d.toDays() > 0) return d.toDays() + "d " + d.toHoursPart() + "h";
        if (d.toHours() > 0) return d.toHours() + "h " + d.toMinutesPart() + "m";
        if (d.toMinutes() > 0) return d.toMinutes() + "m";
        return d.toSeconds() + "s";
    }
}
