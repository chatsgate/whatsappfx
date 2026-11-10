package chatsgate.whatsappfx.wire;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/** The CLI's and the UI's only route to the daemon: loopback JSON, one verb per call. */
public final class DaemonClient {

    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2), CALL_TIMEOUT = Duration.ofMinutes(30);

    /** Forwarded so the approval dialog can name who is really asking. */
    public static volatile String callerDir, callerCommand;

    /** The number a request is for (a key, a name, or the end of its phone number); null: the first one. Set from {@code --account} or WHATSAPPFX_ACCOUNT. */
    public static volatile String account;

    private final DaemonHandshake handshake;
    private final HttpClient http;

    private DaemonClient(DaemonHandshake h) {
        this.handshake = h;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public static Optional<DaemonHandshake> readHandshake() {
        try {
            var f = Paths2.daemonFile();
            if (!Files.exists(f)) return Optional.empty();
            return Optional.ofNullable(GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), DaemonHandshake.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Optional<DaemonClient> ifRunning() {
        return readHandshake().map(DaemonClient::new).filter(DaemonClient::ping);
    }

    public boolean ping() {
        try {
            return post("ping", java.util.Map.of()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The build of the running app, as it wrote it into daemon.json. */
    public String version() {
        return handshake.version();
    }

    public int port() {
        return handshake.port();
    }

    public <T> T call(String verb, Object body, Class<T> replyType) throws IOException, InterruptedException {
        return GSON.fromJson(callRaw(verb, body), replyType);
    }

    /** Bytes through untouched, so an integer count is never re-emitted as {@code 0.0}. */
    public String callRaw(String verb, Object body) throws IOException, InterruptedException {
        var json = post(verb, body);
        if (json == null) throw new IOException("daemon returned no body for verb: " + verb);
        return json;
    }

    public void callStream(String verb, Object body, Consumer<String> onLine) throws IOException, InterruptedException {
        var res = http.send(build(verb, body).build(), HttpResponse.BodyHandlers.ofLines());
        reject(res.statusCode(), verb, "");
        try (var lines = res.body()) {
            lines.forEach(l -> { if (!l.isBlank()) onLine.accept(l); });
        }
    }

    private HttpRequest.Builder build(String verb, Object body) {
        var b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + handshake.port() + "/wa/" + verb))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Wa-Token", handshake.token())
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        header(b, "X-Wa-Caller-Dir", callerDir);
        header(b, "X-Wa-Caller-Cmd", callerCommand);
        header(b, "X-Wa-Account", account);
        return b;
    }

    /** Base64 because a header value is Latin-1 and a working directory here routinely is not. */
    private static void header(HttpRequest.Builder b, String name, String value) {
        if (value != null) b.header(name, java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String post(String verb, Object body) throws IOException, InterruptedException {
        var res = http.send(build(verb, body).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        reject(res.statusCode(), verb, res.body());
        return res.body();
    }

    private static void reject(int status, String verb, String body) throws IOException {
        if (status == 401) throw new IOException("daemon rejected the token in " + Paths2.daemonFile()
                + " — restart WhatsappFx so it rewrites the handshake");
        if (status / 100 != 2) throw new IOException("daemon HTTP " + status + " on '" + verb + "': " + body);
    }
}
