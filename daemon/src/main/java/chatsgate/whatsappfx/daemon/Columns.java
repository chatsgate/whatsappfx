package chatsgate.whatsappfx.daemon;

import com.arcadedb.database.Database;
import chatsgate.whatsappfx.core.model.WaChat;
import chatsgate.whatsappfx.core.model.WaContact;
import chatsgate.whatsappfx.core.model.WaJob;
import chatsgate.whatsappfx.core.model.WaMessage;
import datapotter.datahelper.Field_I;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The names, and the one place that knows the two sets of them apart.
 *
 * <p>The listing verbs return a presentation view — {@code msgId} printed as {@code id},
 * {@code unreadCount} printed as {@code unread}, a contact's name derived from four fields, a chat's
 * recency derived from its messages. The {@code query} verb goes at storage. Nothing used to say the
 * two disagreed, and ArcadeDB answers a projection of a property that does not exist with a column of
 * nulls rather than with an error — correctly, because documents are sparse and a missing field is
 * ordinary. So the natural guess came back empty and looked like an empty table. Measured on this
 * index: {@code select id, ts, text from WaMessage} gave three rows with a null id, and
 * {@code where id = '<a real id>'} gave none at all.
 *
 * <p>So {@code query} resolves the printed name to the stored one before running, renames the column
 * back to what the caller asked for, and says on stderr what it did. Where a printed column is
 * derived rather than renamed there is nothing to rewrite, and the note carries the SQL that produces
 * it instead. {@code columns} prints the whole map so none of it has to be guessed.
 *
 * <p><b>Every field name here comes from the generated {@code $} constants, never from a string
 * literal.</b> That is not decoration: a catalogue of column names typed out by hand is the same
 * defect this class exists to cure, one level up — rename a field in the model and a literal
 * catalogue compiles clean and starts lying. {@link #ALIASES} is also the single definition of the
 * rename, read by {@link Queries} when it builds a listing, so the presentation view and the map of
 * it cannot drift apart.
 */
public final class Columns {

    private Columns() {}

    /** A printed name that is one stored property under another name. Rewritable. */
    private record Alias(String type, String printed, Field_I<?, ?> stored, String why) {
        String storedName() {
            return stored.name();
        }
    }

    /** A printed name with no stored equivalent. Not rewritable; the SQL that yields it is the answer. */
    private record Derived(String type, String printed, String sql, String why) {}

    /** A declared model, so the property set is the compiler's answer rather than the schema's. */
    private record Model(String type, List<? extends Field_I<?, ?>> fields) {}

    private static final List<Model> MODELS = List.of(
            new Model(WaMessage.__, WaMessage.FIELDS),
            new Model(WaChat.__, WaChat.FIELDS),
            new Model(WaContact.__, WaContact.FIELDS),
            new Model(WaJob.__, WaJob.FIELDS));

    private static final List<Alias> ALIASES = List.of(
            new Alias(WaMessage.__, "id", WaMessage.$msgId,
                    "messages, search and media all print " + n(WaMessage.$msgId) + " as 'id', and react,"
                            + " star, reply and fetch take it as --id"),
            new Alias(WaChat.__, "unread", WaChat.$unreadCount,
                    "the chats verb prints " + n(WaChat.$unreadCount) + " as 'unread'"));

    private static final List<Derived> DERIVED = List.of(
            new Derived(WaContact.__, "name",
                    "select " + n(WaContact.$jid) + ", coalesce(" + names(WaContact.$fullName,
                            WaContact.$businessName, WaContact.$pushName, WaContact.$firstName)
                            + ") as name from " + WaContact.__,
                    "WaContact stores no name at all; the contacts verb takes the first non-blank of "
                            + names(WaContact.$fullName, WaContact.$businessName, WaContact.$pushName,
                            WaContact.$firstName)),
            new Derived(WaChat.__, "messages",
                    "select " + n(WaMessage.$chat) + ", count(*) as messages from " + WaMessage.__
                            + " group by " + n(WaMessage.$chat),
                    "the chats verb counts the message rows themselves"),
            new Derived(WaChat.__, "attachments",
                    "select " + n(WaMessage.$chat) + ", count(*) as attachments from " + WaMessage.__
                            + " where " + n(WaMessage.$mediaKind) + " is not null group by " + n(WaMessage.$chat),
                    "the chats verb counts the media rows themselves"));

    /**
     * Stored, real, and not the answer to the question it looks like it answers. Worse than a missing
     * property, because a query over one of these comes back with numbers rather than with nulls.
     */
    private static final Map<String, String> UNRELIABLE = Map.of(
            WaChat.__ + "." + n(WaChat.$lastMsgTs),
            "STORED BUT ALWAYS 0 — the conversation records history sync sends carry no timestamp. The"
                    + " chats verb ignores this field and derives recency with: select " + n(WaMessage.$chat)
                    + ", max(" + n(WaMessage.$ts) + ") as " + n(WaChat.$lastMsgTs) + " from " + WaMessage.__
                    + " group by " + n(WaMessage.$chat),
            WaChat.__ + "." + n(WaChat.$msgCount),
            "STORED BUT EMPTY — the chats verb counts the message rows instead: select "
                    + n(WaMessage.$chat) + ", count(*) as messages from " + WaMessage.__ + " group by "
                    + n(WaMessage.$chat));

    private static String n(Field_I<?, ?> f) {
        return f.name();
    }

    private static String names(Field_I<?, ?>... fields) {
        var parts = new ArrayList<String>(fields.length);
        for (var f : fields) parts.add(f.name());
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- used by the listing verbs

    /**
     * What a listing calls this stored property. The only definition of the rename, so the map cannot
     * describe an output shape the code does not actually produce.
     */
    public static String printedName(String type, String stored) {
        for (var a : ALIASES) if (a.type().equals(type) && a.storedName().equals(stored)) return a.printed();
        return stored;
    }

    // ---------------------------------------------------------------- the columns verb

    /** Every column of every type: what is stored, what is renamed on output, what is derived. */
    public static List<Map<String, Object>> describe(String typeFilter) {
        var out = new ArrayList<Map<String, Object>>();
        for (var model : MODELS) {
            if (typeFilter != null && !typeFilter.equalsIgnoreCase(model.type())) continue;
            for (var f : model.fields()) {
                out.add(row(model.type(), f.name(), "stored", f.name(), storedNote(model.type(), f.name())));
            }
            for (var a : ALIASES) {
                if (a.type().equals(model.type())) {
                    out.add(row(model.type(), a.printed(), "alias", a.storedName(), a.why()));
                }
            }
            for (var d : DERIVED) {
                if (d.type().equals(model.type())) {
                    out.add(row(model.type(), d.printed(), "derived", d.sql(), d.why()));
                }
            }
        }
        if (out.isEmpty() && typeFilter != null) {
            throw new IllegalArgumentException("no such type: " + typeFilter + " — try one of " + typeNames());
        }
        return out;
    }

    private static String storedNote(String type, String property) {
        var unreliable = UNRELIABLE.get(type + "." + property);
        if (unreliable != null) return unreliable;
        var printed = printedName(type, property);
        return printed.equals(property) ? "" : "the listing verbs print this as '" + printed + "'";
    }

    private static Map<String, Object> row(String type, String name, String kind, String useInSql, String note) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("name", name);
        m.put("kind", kind);
        m.put("useInSql", useInSql);
        m.put("note", note);
        return m;
    }

    // ---------------------------------------------------------------- the query verb

    /** Rows, plus whatever had to be said about them. */
    public static Map<String, Object> ask(Database db, String sql, int limit) {
        Sql.requireRead(sql);
        var toks = tokens(sql);
        var referenced = referencedTypes(toks);
        var props = propertiesOf(referenced);
        var aliasesInSql = asAliases(toks);
        var notes = new ArrayList<String>();
        var renameBack = new LinkedHashMap<String, String>();

        var rewritten = rewrite(sql, toks, referenced, props, aliasesInSql, notes, renameBack);
        var rows = Sql.query(db, rewritten, limit);
        if (!renameBack.isEmpty()) rows = renameKeys(rows, renameBack);
        notes.addAll(inspect(rows, toks, referenced, props, aliasesInSql, renameBack));

        var out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("notes", notes);
        return out;
    }

    /**
     * Printed name to stored name, in place, for the types this statement actually names.
     *
     * <p>Gated three ways, because {@code id} is a real stored property of {@code WaJob} and a
     * nonexistent one of {@code WaMessage}: a token that any referenced type declares is left alone, a
     * token the statement itself defines with {@code as} is left alone, and a token claimed by more
     * than one referenced type is left alone and reported rather than guessed at.
     */
    private static String rewrite(String sql, List<Tok> toks, Set<String> referenced, Set<String> props,
                                  Set<String> aliasesInSql, List<String> notes, Map<String, String> renameBack) {
        var spelled = new LinkedHashSet<String>();
        for (var t : toks) spelled.add(t.text());
        var said = new LinkedHashSet<String>();
        var b = new StringBuilder();
        var cut = 0;
        for (var i = 0; i < toks.size(); i++) {
            var t = toks.get(i);
            if (i > 0 && toks.get(i - 1).text().equalsIgnoreCase("as")) continue;
            if (aliasesInSql.contains(t.text()) || props.contains(t.text()) || referenced.contains(t.text())) continue;
            var hits = new ArrayList<Alias>();
            for (var a : ALIASES) if (referenced.contains(a.type()) && a.printed().equals(t.text())) hits.add(a);
            if (hits.size() != 1) {
                if (hits.size() > 1 && said.add(t.text())) {
                    notes.add("'" + t.text() + "' names a different stored field in each of the types this"
                            + " statement mentions, so it was left exactly as written. Spell out the one you"
                            + " mean — 'whatsappcli columns' lists both.");
                }
                continue;
            }
            var a = hits.get(0);
            b.append(sql, cut, t.start()).append(a.storedName());
            cut = t.end();
            var keepName = !spelled.contains(a.storedName());
            if (keepName) renameBack.put(a.storedName(), a.printed());
            if (said.add(a.printed())) {
                notes.add("'" + a.printed() + "' is not a property of " + a.type() + " — it is stored as '"
                        + a.storedName() + "', so the query ran against " + a.storedName()
                        + (keepName ? " and the column came back named '" + a.printed() + "' as you asked for it." : ".")
                        + " Why they differ: " + a.why() + ".");
            }
        }
        b.append(sql, cut, sql.length());
        return b.toString();
    }

    /**
     * What came back, judged against what exists.
     *
     * <p>Checking the result rather than parsing the statement is what makes this cheap and quiet: a
     * column that legitimately exists and happens to be empty — {@code reaction} in a chat nobody has
     * reacted in — is a declared property, so nothing is said about it.
     */
    private static List<String> inspect(List<Map<String, Object>> rows, List<Tok> toks, Set<String> referenced,
                                        Set<String> props, Set<String> aliasesInSql, Map<String, String> renameBack) {
        var notes = new ArrayList<String>();
        if (rows.isEmpty()) {
            var spelled = new LinkedHashSet<String>();
            for (var t : toks) spelled.add(t.text());
            for (var d : DERIVED) {
                if (!referenced.contains(d.type()) || !spelled.contains(d.printed())) continue;
                if (props.contains(d.printed()) || aliasesInSql.contains(d.printed())) continue;
                notes.add(derivedNote(d) + " Nothing matched at all, which is what an unknown column in a"
                        + " WHERE clause looks like from out here.");
            }
            return notes;
        }
        var cols = new LinkedHashSet<String>();
        for (var r : rows) cols.addAll(r.keySet());
        for (var c : cols) {
            if (props.contains(c) || aliasesInSql.contains(c) || renameBack.containsValue(c)) continue;
            var allNull = true;
            for (var r : rows) {
                if (r.get(c) != null) {
                    allNull = false;
                    break;
                }
            }
            if (!allNull) continue;
            var derived = derivedFor(referenced, c);
            notes.add(derived != null ? derivedNote(derived)
                    : "'" + c + "' is not a property of " + describeTypes(referenced) + ", and came back empty"
                            + " on every row. Run 'whatsappcli columns' for the real names.");
        }
        return notes;
    }

    private static Derived derivedFor(Set<String> referenced, String printed) {
        for (var d : DERIVED) if (referenced.contains(d.type()) && d.printed().equals(printed)) return d;
        return null;
    }

    private static String derivedNote(Derived d) {
        return "'" + d.printed() + "' is not a stored property of " + d.type() + " — " + d.why()
                + ". The query that gives it: " + d.sql();
    }

    private static String describeTypes(Set<String> referenced) {
        return referenced.isEmpty() ? "the type queried" : String.join(" or ", new TreeSet<>(referenced));
    }

    private static List<Map<String, Object>> renameKeys(List<Map<String, Object>> rows, Map<String, String> rename) {
        var out = new ArrayList<Map<String, Object>>(rows.size());
        for (var r : rows) {
            var m = new LinkedHashMap<String, Object>();
            for (var e : r.entrySet()) m.put(rename.getOrDefault(e.getKey(), e.getKey()), e.getValue());
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- the model, and tokens

    private static Set<String> typeNames() {
        var out = new TreeSet<String>();
        for (var m : MODELS) out.add(m.type());
        return out;
    }

    private static Set<String> referencedTypes(List<Tok> toks) {
        var out = new LinkedHashSet<String>();
        for (var t : toks) {
            for (var m : MODELS) if (m.type().equalsIgnoreCase(t.text())) out.add(m.type());
        }
        return out;
    }

    private static Set<String> propertiesOf(Set<String> types) {
        var out = new LinkedHashSet<String>();
        for (var m : MODELS) {
            if (!types.contains(m.type())) continue;
            for (var f : m.fields()) out.add(f.name());
        }
        return out;
    }

    /** Names the statement defines for itself, which are never ours to reinterpret. */
    private static Set<String> asAliases(List<Tok> toks) {
        var out = new LinkedHashSet<String>();
        for (var i = 1; i < toks.size(); i++) {
            if (toks.get(i - 1).text().equalsIgnoreCase("as")) out.add(toks.get(i).text());
        }
        return out;
    }

    private record Tok(int start, int end, String text) {}

    /**
     * Identifiers only, with quoted text stepped over rather than read.
     *
     * <p>This is why the substitution is done here and not with a string replace: a search for a
     * message containing the word "id" must not have its own search term rewritten.
     */
    private static List<Tok> tokens(String s) {
        var out = new ArrayList<Tok>();
        var i = 0;
        while (i < s.length()) {
            var c = s.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(s, i, c);
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                var j = i + 1;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(new Tok(i, j, s.substring(i, j)));
                i = j;
                continue;
            }
            i++;
        }
        return out;
    }

    private static int skipQuoted(String s, int i, char quote) {
        var j = i + 1;
        while (j < s.length()) {
            if (s.charAt(j) != quote) {
                j++;
                continue;
            }
            if (j + 1 < s.length() && s.charAt(j + 1) == quote) {
                j += 2;
                continue;
            }
            return j + 1;
        }
        return s.length();
    }
}
