package chatsgate.whatsappfx.daemon;

import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Paths2;

import module java.base;

/**
 * {@code run/access.log}: one line for every request a program was allowed to make: time, program and pid, the verb and tier, and
 * the chat id and name. Never message text. It cannot flood: when the day changes (or the file passes {@link #MAX_BYTES}) the
 * current file is renamed {@code access.<date>[.n].log}, files older than {@link #KEEP_DAYS} days are deleted, and no more than
 * {@link #KEEP_FILES} rotated files are kept.
 */
public final class AccessLog {

    static final long MAX_BYTES = 2L * 1024 * 1024;
    static final int KEEP_DAYS = 14;
    static final int KEEP_FILES = 40;

    private final Path dir;
    private LocalDate day;

    public AccessLog() { this(Paths2.home().resolve("run")); }

    AccessLog(Path dir) { this.dir = dir; }

    public synchronized void write(String line) { write(line, LocalDate.now()); }

    synchronized void write(String line, LocalDate today) {
        try {
            Files.createDirectories(dir);
            var cur = dir.resolve("access.log");
            if (day == null && Files.isRegularFile(cur)) day = Files.getLastModifiedTime(cur).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (Files.isRegularFile(cur) && ((day != null && day.isBefore(today)) || Files.size(cur) > MAX_BYTES)) {
                rotate(cur, day == null ? today : day);
                prune(today);
            }
            day = today;
            Files.writeString(cur, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.warn("access.log: " + e.getMessage());
        }
    }

    private void rotate(Path cur, LocalDate date) throws IOException {
        var target = dir.resolve("access." + date + ".log");
        for (var n = 1; Files.exists(target); n++) target = dir.resolve("access." + date + "." + n + ".log");
        Files.move(cur, target);
    }

    private void prune(LocalDate today) throws IOException {
        try (var s = Files.list(dir)) {
            var old = s.filter(p -> p.getFileName().toString().matches("access\\.\\d{4}-\\d{2}-\\d{2}(\\.\\d+)?\\.log")).sorted().toList();
            var cutoff = today.minusDays(KEEP_DAYS).toString();
            var keep = new ArrayList<>(old);
            for (var p : old) {
                if (p.getFileName().toString().substring(7, 17).compareTo(cutoff) < 0) {
                    Files.deleteIfExists(p);
                    keep.remove(p);
                }
            }
            while (keep.size() > KEEP_FILES) Files.deleteIfExists(keep.remove(0));
        }
    }

    /** The line for one allowed request: tab-separated, no message text. */
    public static String line(Instant at, long pid, String program, String verb, Tier tier, String chat, String chatName, String note) {
        return String.join("\t", at.toString(), "pid " + pid, program, verb, tier.word, chat == null ? "-" : chat,
                chatName == null ? "-" : chatName.replace('\t', ' ').replace('\n', ' '), note == null ? "" : note);
    }
}
