package chatsgate.whatsappfx.daemon;

import com.google.gson.reflect.TypeToken;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.DaemonClient;
import chatsgate.whatsappfx.wire.Paths2;

import module java.base;

/**
 * {@code accounts.json} in the home folder: the numbers, in the order the person keeps them. Absent on a home from before there
 * were several: then the one linked number is account {@code default}, exactly where its data already is, so nothing is moved and
 * nothing is paired again.
 */
public final class AccountStore {

    public static final String FIRST_ID = "default", FIRST_TITLE = "WhatsappFx";

    private static final Type LIST = new TypeToken<List<AccountSpec>>() {}.getType();

    public List<AccountSpec> load() {
        var f = Paths2.accountsFile();
        try {
            if (Files.isRegularFile(f)) {
                List<AccountSpec> read = DaemonClient.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), LIST);
                if (read != null && !read.isEmpty()) return fill(read);
            }
        } catch (Exception e) {
            Log.warn("accounts.json unreadable, using the folders found: " + e.getMessage());
        }
        var found = new ArrayList<AccountSpec>();
        found.add(new AccountSpec(FIRST_ID, FIRST_TITLE, Palette.COLOURS.getFirst()));
        try (var dirs = Files.isDirectory(Paths2.accountsDir()) ? Files.list(Paths2.accountsDir()) : Stream.<Path>empty()) {
            for (var d : dirs.filter(Files::isDirectory).sorted().toList()) {
                var id = d.getFileName().toString();
                if (found.stream().noneMatch(a -> a.id().equals(id))) found.add(new AccountSpec(id, id, Palette.free(found.stream().map(AccountSpec::colour).toList())));
            }
        } catch (IOException e) {
            Log.warn("account folders not listed: " + e.getMessage());
        }
        save(found);
        return found;
    }

    /** A hand-edited file may leave a title or colour out: they are filled in rather than refused. */
    private static List<AccountSpec> fill(List<AccountSpec> read) {
        var out = new ArrayList<AccountSpec>();
        for (var a : read) {
            var colour = a.colour() == null || a.colour().isBlank() ? Palette.free(out.stream().map(AccountSpec::colour).toList()) : a.colour();
            out.add(new AccountSpec(a.id(), a.title() == null || a.title().isBlank() ? a.id() : a.title(), colour, a.shortName()));
        }
        return out;
    }

    public synchronized void save(List<AccountSpec> all) {
        try {
            var f = Paths2.accountsFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, DaemonClient.GSON.newBuilder().setPrettyPrinting().create().toJson(all), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("accounts.json not saved: " + e.getMessage());
        }
    }
}
