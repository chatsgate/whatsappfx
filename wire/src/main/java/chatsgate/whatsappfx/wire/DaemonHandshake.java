package chatsgate.whatsappfx.wire;

/** Contents of daemon.json: how to reach a running daemon, and whose process it is. */
public record DaemonHandshake(int port, String token, long pid, String startedAt, String version) {
}
