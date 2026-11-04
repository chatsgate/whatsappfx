package chatsgate.whatsappfx.daemon;

import module java.base;

/**
 * One linked number as the person set it up: the folder key ({@code id}, never changes, it names the data on disk and keys the
 * index rows), the name they gave it, its colour ({@code #rrggbb}, one of {@link Palette}'s, never shared by two numbers) and an
 * optional short name for the command line ({@code whatsappcli --account work}); without one the key is the short name.
 */
public record AccountSpec(String id, String title, String colour, String shortName) {

    public AccountSpec(String id, String title, String colour) { this(id, title, colour, null); }

    /** What the command line calls it: the short name, else the key. */
    public String key() { return shortName == null || shortName.isBlank() ? id : shortName; }

    /** The key a title becomes: lower-case letters and digits, anything else a dash, never one that {@code taken} holds. */
    public static String idFor(String title, Collection<String> taken) {
        var base = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) base = "number";
        if (base.length() > 24) base = base.substring(0, 24).replaceAll("-$", "");
        var id = base;
        for (var n = 2; taken.contains(id); n++) id = base + "-" + n;
        return id;
    }

    /** A short name a person may type: 1 to 16 lower-case letters, digits or dashes. */
    public static boolean validShort(String s) { return s != null && s.matches("[a-z0-9][a-z0-9-]{0,15}"); }

    public AccountSpec withTitle(String t) { return new AccountSpec(id, t, colour, shortName); }

    public AccountSpec withColour(String c) { return new AccountSpec(id, title, c, shortName); }

    public AccountSpec withShort(String s) { return new AccountSpec(id, title, colour, s); }
}
