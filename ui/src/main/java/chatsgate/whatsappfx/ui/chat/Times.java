package chatsgate.whatsappfx.ui.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Times the way WhatsApp shows them: a clock for today, then Yesterday, a weekday, a date. ts is in seconds. */
final class Times {

    static final ZoneId ZONE = ZoneId.systemDefault();
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM/yy");
    static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("d MMMM yyyy");
    static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE");

    private Times() {}

    static LocalDate date(long ts) {
        return Instant.ofEpochSecond(ts).atZone(ZONE).toLocalDate();
    }

    static String clock(long ts) {
        return ts <= 0 ? "" : CLOCK.format(Instant.ofEpochSecond(ts).atZone(ZONE));
    }

    /** For the chat list. */
    static String list(long ts) {
        if (ts <= 0) return "";
        var d = date(ts);
        var age = LocalDate.now(ZONE).toEpochDay() - d.toEpochDay();
        if (age == 0) return clock(ts);
        if (age == 1) return "Yesterday";
        if (age < 7) return WEEKDAY.format(d);
        return SHORT.format(d);
    }

    /** For the chip over the first message of a day. */
    static String day(long ts) {
        var d = date(ts);
        var age = LocalDate.now(ZONE).toEpochDay() - d.toEpochDay();
        if (age == 0) return "Today";
        if (age == 1) return "Yesterday";
        if (age < 7) return WEEKDAY.format(d);
        return LONG.format(d);
    }

    /** The same messages with {@code day} set where a new calendar day starts. */
    static List<Msg> days(List<Msg> in) {
        var out = new ArrayList<Msg>(in.size());
        LocalDate last = null;
        for (var m : in) {
            var d = date(m.ts());
            out.add(d.equals(last) ? m.withDay(null) : m.withDay(day(m.ts())));
            last = d;
        }
        return out;
    }
}
