package chatsgate.whatsappfx.core;


import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Where an attachment lands on the shared drive.
 *
 * <p>Short by design — the long form blew past the Windows path limit on group names — while keeping
 * a one-to-one association: the eight-character message id is always present, so a file on disk
 * names exactly one message and nothing else.
 */
public final class MediaPlacer {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneId.systemDefault());

    private MediaPlacer() {}

    public static Path target(Path root, String accountId, String chatJid, String chatName, String msgId,
                              long tsSeconds, String originalName, String kind) {
        var year = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(tsSeconds));
        return root
                .resolve(accountId)
                .resolve(Slug.chat(chatJid, chatName))
                .resolve(year)
                .resolve(fileName(msgId, tsSeconds, originalName, kind));
    }

    public static String fileName(String msgId, long tsSeconds, String originalName, String kind) {
        var stamp = STAMP.format(Instant.ofEpochSecond(tsSeconds));
        var id = shortId(msgId);
        var name = Slug.file(originalName, Slug.FILE_MAX);
        if (name.isBlank()) name = kind + defaultExt(kind);
        return stamp + "_" + id + "_" + name;
    }

    public static String shortId(String msgId) {
        if (msgId == null || msgId.isBlank()) return "noid";
        return msgId.length() <= 8 ? msgId : msgId.substring(msgId.length() - 8);
    }

    private static String defaultExt(String kind) {
        return switch (kind == null ? "" : kind) {
            case "image" -> ".jpg";
            case "video" -> ".mp4";
            case "audio" -> ".ogg";
            case "sticker" -> ".webp";
            default -> ".bin";
        };
    }
}
