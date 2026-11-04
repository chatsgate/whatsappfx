// Run: WHATSAPPFX_HOME=<scratch folder> java --enable-native-access=ALL-UNNAMED -cp daemon/target/whatsappfx-daemon-1.0.jar prp/12-prp.01.gatecheck.java   (about 30 s; updated for the PRP 18 API: read and write are the READ and SEND tiers over no chat)
import chatsgate.whatsappfx.daemon.*;
import io.javalin.Javalin;
import leasegate.core.access.Approval;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class GateCheck {

    static int failures;
    static final HttpClient http = HttpClient.newHttpClient();

    static String get(int port, String tier) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/" + tier)).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + " " + r.body();
    }

    static void expect(String what, Object got, Object want) {
        var ok = got.toString().startsWith(want.toString());
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + what + "  got=" + got + (ok ? "" : "  want=" + want));
    }

    public static void main(String[] args) throws Exception {
        var env = new CallerGate.Env() {
            public String account() { return "default"; }
            public String ownUser() { return "254700000001"; }
            public boolean walletOpen() { return false; }
            public Unlocks unlocks() { return new Unlocks(); }
            public AccessLog access() { return new AccessLog(); }
            public ChatInfo.Info chat(String jid) { return new ChatInfo.Info(jid, null, ChatKind.NONE); }
        };
        var gate = new CallerGate(env);
        var asked = new AtomicInteger();
        var answer = new AtomicBoolean(true);
        var delay = new AtomicLong();
        var app = Javalin.create(c -> c.showJavalinBanner = false);
        app.get("/{tier}", ctx -> {
            try { gate.check(ctx.req(), new Need(Tier.of(ctx.pathParam("tier")), null, false, false, null), "test-" + ctx.pathParam("tier")); ctx.result("ok"); }
            catch (SecurityException e) { ctx.status(403).result(e.getMessage()); }
        });
        app.start("127.0.0.1", 0);
        var port = app.port();

        expect("1 no window registered: refused", get(port, "read"), "403 WhatsappFx has no window");

        gate.approval(req -> {
            asked.incrementAndGet();
            try { Thread.sleep(delay.get()); } catch (InterruptedException e) { }
            return answer.get() ? Optional.of(new Approval.Decision<>(req.chain().get(0), 60, 480, req.requested())) : Optional.empty();
        });
        expect("2 first read asks", get(port, "read"), "200 ok");
        expect("2 asked once", asked.get(), 1);
        expect("3 second read is covered", get(port, "read"), "200 ok");
        expect("3 still asked once", asked.get(), 1);
        expect("4 first write asks again", get(port, "write"), "200 ok");
        expect("4 asked twice", asked.get(), 2);
        expect("5 read after write is covered", get(port, "read"), "200 ok");
        expect("5 write again is covered", get(port, "write"), "200 ok");
        expect("5 still asked twice", asked.get(), 2);
        expect("5b approvals listed", gate.approvals(), 2);

        gate.revokeAll();
        expect("6 revoked: approvals 0", gate.approvals(), 0);
        expect("6 read asks again", get(port, "read"), "200 ok");
        expect("6 asked 3", asked.get(), 3);

        gate.revokeAll();
        answer.set(false);
        expect("7 declined write is refused", get(port, "write"), "403 not approved");
        expect("7 asked 4", asked.get(), 4);
        expect("7 nothing granted", gate.approvals(), 0);

        gate.revokeAll();
        answer.set(true);
        delay.set(600);
        asked.set(0);
        var pool = Executors.newFixedThreadPool(5);
        var all = new ArrayList<Future<String>>();
        for (var i = 0; i < 5; i++) all.add(pool.submit(() -> get(port, "read")));
        var codes = new ArrayList<String>();
        for (var f : all) codes.add(f.get());
        expect("8 five parallel reads all pass", codes.stream().filter(s -> s.startsWith("200")).count(), 5);
        expect("8 one window for five parallel requests", asked.get(), 1);
        pool.shutdown();
        app.stop();
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
