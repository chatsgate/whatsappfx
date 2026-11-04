package chatsgate.whatsappfx.core;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import chatsgate.whatsappfx.wire.Paths2;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * User settings. Intent a human might edit, so TOML rather than JSON.
 *
 * <p>Fields are read directly: the accessors here are fluent, and Jackson's bean discovery finds no
 * {@code getX()} to work from, which silently produced an empty file rather than an error.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Config {

    String account = "";
    String deviceName = "WhatsappFx";
    boolean fullSync = true;
    int syncDays = 3650;
    int quotaMb = 10240;

    /** Attachments are off everywhere until a chat is named — a shared drive is not a dumping ground. */
    String defaultMediaPolicy = "off";
    List<String> mediaAllow = new ArrayList<>();

    /**
     * Where attachments are filed. Blank means {@code media} under the app's home folder. A synced or shared drive
     * suits it: the sync client owns nothing else of this app. Set in the settings window (F9) or in this file.
     */
    String mediaFolder = "";

    /** Bills are small. A chat-export zip is not a bill, and one of those blocks everything behind it. */
    int maxAttachmentMb = 50;

    /** Rare enough that a human would not reach for it, which is the point of a bot's mark. */
    String reactionEmoji = "🧮";
    int idleLockPromptMinutes = 60;

    public static Config load() {
        var f = Paths2.configFile();
        try {
            var legacy = Paths2.legacyConfigFile();
            if (!Files.exists(f) && Files.exists(legacy)) {
                Files.move(legacy, f);
                Log.info("settings renamed: " + legacy.getFileName() + " -> " + f.getFileName());
            }
            if (!Files.exists(f)) {
                var fresh = new Config();
                fresh.save();
                return fresh;
            }
            return new TomlMapper().readValue(Files.readString(f), Config.class);
        } catch (IOException e) {
            Log.warn("config unreadable, using defaults: " + e.getMessage());
            return new Config();
        }
    }

    public void save() {
        try {
            var f = Paths2.configFile();
            Files.createDirectories(f.getParent());
            ObjectMapper m = new TomlMapper();
            Files.writeString(f, m.writerWithDefaultPrettyPrinter().writeValueAsString(this));
        } catch (IOException e) {
            Log.warn("config not saved: " + e.getMessage());
        }
    }

    public String account() { return account; }
    public void account(String v) { account = v; }
    public String deviceName() { return deviceName; }
    public boolean fullSync() { return fullSync; }
    public int syncDays() { return syncDays; }
    public int quotaMb() { return quotaMb; }
    public String defaultMediaPolicy() { return defaultMediaPolicy; }
    public List<String> mediaAllow() { return mediaAllow; }
    public String mediaFolder() { return mediaFolder == null ? "" : mediaFolder; }

    /** Saves at once: the folder is a person's decision made in the window, not something to lose on a crash. */
    public synchronized void mediaFolder(String v) {
        mediaFolder = v == null ? "" : v.strip();
        save();
    }

    /** The folder attachments are filed under, the setting or its default. */
    public java.nio.file.Path mediaRoot() {
        var v = mediaFolder();
        return v.isBlank() ? Paths2.home().resolve("media") : java.nio.file.Path.of(v.strip());
    }

    public int maxAttachmentMb() { return maxAttachmentMb; }
    public String reactionEmoji() { return reactionEmoji; }
    public int idleLockPromptMinutes() { return idleLockPromptMinutes; }
}
