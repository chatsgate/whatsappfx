// PRP 13 proof: several numbers in one daemon. Run from the repository root, with a scratch home (never the live one):
//   WHATSAPPFX_HOME=<scratch folder> java --enable-native-access=ALL-UNNAMED -cp daemon/target/whatsappfx-daemon-1.0.jar prp/13-prp.01.accountscheck.java
// Starts the real daemon (a connector child per number, unlinked), approves every program automatically, and drives it over HTTP as whatsappcli does.
// What it cannot prove, and only a person can: the windows, their colours, the tray chooser, pairing a second phone.
import chatsgate.whatsappfx.daemon.*;
import chatsgate.whatsappfx.wire.DaemonClient;
import leasegate.core.access.Approval;
import com.google.gson.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class AccountsCheck {

    static int failures;

    static void expect(String what, Object got, Object want) {
        var ok = String.valueOf(got).contains(String.valueOf(want));
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + what + "  got=" + got + (ok ? "" : "  want=" + want));
    }

    static String call(DaemonClient c, String account, String verb, Map<String, Object> body) {
        DaemonClient.account = account;
        try { return c.callRaw(verb, body); } catch (Exception e) { return "ERROR " + e.getMessage(); }
    }

    public static void main(String[] args) throws Exception {
        var home = Path.of(System.getenv("WHATSAPPFX_HOME"));
        var accounts = DaemonMain.start();
        var asked = new AtomicInteger();
        accounts.callers.approval(req -> {
            asked.incrementAndGet();
            return Optional.of(new Approval.Decision<>(req.chain().get(0), 60, 480, req.requested()));
        });
        var c = DaemonClient.ifRunning().orElseThrow();

        var list = call(c, null, "accounts", Map.of());
        expect("1 one number at first, called default", list, "\"account\":\"default\"");
        expect("1 first colour is WhatsApp green", list, "#21c063");
        expect("1 accounts.json written", Files.isRegularFile(home.resolve("accounts.json")), true);

        var added = call(c, null, "account.add", Map.of("title", "Work"));
        expect("2 add Work: key work", added, "\"account\":\"work\"");
        expect("2 add Work: next free colour is blue", added, "#58a6ff");
        expect("2 two numbers", accounts.count(), 2);
        expect("2 duplicate name refused", call(c, null, "account.add", Map.of("title", "work")), "already called");
        expect("2 add refused colour taken", call(c, null, "account.add", Map.of("title", "Third", "colour", "blue")), "already worn");

        expect("3 status of work", call(c, "work", "status", Map.of()), "\"account\":\"work\"");
        expect("3 status by name, any case", call(c, "WORK", "status", Map.of()), "\"account\":\"work\"");
        expect("3 several numbers and none named: refused, and told what exists", call(c, null, "status", Map.of()), "has 2 numbers: default (WhatsappFx, not linked), work (Work, not linked)");
        expect("3 the list of numbers is open, a global verb needs no number", call(c, null, "ping", Map.of()), "ok");
        expect("3 unknown number says which are known", call(c, "nobody", "status", Map.of()), "Known: default (WhatsappFx");

        asked.set(0);
        accounts.callers.revokeAll();
        call(c, "default", "chats", Map.of());
        call(c, "default", "chats", Map.of());
        expect("4 two reads of default ask once", asked.get(), 1);
        call(c, "work", "chats", Map.of());
        expect("4 the first read of work asks again: an approval is for one number", asked.get(), 2);
        call(c, "work", "chats", Map.of());
        expect("4 and is then covered", asked.get(), 2);

        expect("5 rename", call(c, "work", "account.set", Map.of("title", "Office")), "\"title\":\"Office\"");
        expect("5 recolour", call(c, "office", "account.set", Map.of("colour", "red")), "#e5534b");
        expect("5 a colour another number wears is refused", call(c, "office", "account.set", Map.of("colour", "green")), "already worn");
        expect("5 bad colour", call(c, "office", "account.set", Map.of("colour", "plaid")), "colour must be one of");
        expect("5 key unchanged by rename", accounts.find("Office").account, "work");
        expect("5 saved", Files.readString(home.resolve("accounts.json")), "Office");

        expect("5 short name", call(c, "office", "account.set", Map.of("short", "wk")), "\"short\":\"wk\"");
        expect("5 reachable by its short name", call(c, "wk", "status", Map.of()), "\"account\":\"work\"");
        expect("5 bad short name", call(c, "wk", "account.set", Map.of("short", "Not Valid")), "short name is 1 to 16");
        expect("5 short name taken by another number", call(c, "wk", "account.set", Map.of("short", "default")), "already answers");
        asked.set(0);
        call(c, null, "accounts", Map.of());
        expect("5 listing the numbers asks nobody (like cdpg profiles)", asked.get(), 0);
        expect("6 the folders are separate", Files.isDirectory(home.resolve("accounts").resolve("default")), true);
        expect("6 the connector of work started", accounts.find("work").connector.alive(), true);

        expect("7 remove work", call(c, "work", "account.remove", Map.of()), "\"removed\":\"work\"");
        expect("7 one number again", accounts.count(), 1);
        expect("7 the only one cannot be removed", call(c, null, "account.remove", Map.of()), "only number");
        expect("7 data kept", Files.isDirectory(home.resolve("accounts").resolve("work")), true);
        var back = call(c, null, "account.add", Map.of("title", "Work"));
        expect("7 added back with the same name finds the same key", back, "\"account\":\"work\"");

        accounts.shutdown();
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }
}
