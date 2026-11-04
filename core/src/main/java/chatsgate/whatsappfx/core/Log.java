package chatsgate.whatsappfx.core;

import chatsgate.whatsappfx.wire.Paths2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** One line, one event, to a file — stdout belongs to whatever the caller asked for. */
public final class Log {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static volatile boolean echo = false;

    private Log() {}

    public static void info(String msg) {
        write("INFO ", msg);
    }

    public static void warn(String msg) {
        write("WARN ", msg);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg + (t == null ? "" : " :: " + t));
    }

    private static synchronized void write(String level, String msg) {
        var line = STAMP.format(LocalDateTime.now()) + " " + level + " " + msg;
        if (echo) System.err.println(line);
        try {
            var f = Paths2.logFile();
            Files.createDirectories(f.getParent());
            Files.write(f, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
