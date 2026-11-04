package chatsgate.whatsappfx.daemon;

import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Os;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Start the app at logon, because the deadline this guards is not a convenience.
 *
 * <p>WhatsApp deregisters a linked device that stays offline for roughly a fortnight. Losing the link
 * costs a re-pair and another full history sync — so the app being up is the normal state of this
 * machine, not something to remember.
 *
 * <p>A shortcut in the user's Startup folder rather than a scheduled task. A logon task was the first
 * choice and it does not work here: {@code schtasks /create /sc onlogon} answers "Access is denied" to
 * an unelevated shell, and an autostart toggle that needs an administrator is a toggle nobody will
 * use. The Startup folder is the user's own, needs no elevation, and Windows lists what is in it under
 * Settings → Startup Apps, so the switch is visible in the place a person looks for it. On a Mac the same job is
 * a LaunchAgent (AutostartMac), which the user sees under System Settings → General → Login Items.
 */
public final class Autostart {

    /** The filename a person will read in the Startup folder and in Windows' own list. */
    public static final String LINK = "whatsappfx.lnk";

    private Autostart() {}

    private static volatile Boolean cachedEnabled;

    /**
     * Cheap enough to sit in the status block, which the window rebuilds every two seconds. This one
     * is a file check rather than a process, but the cache stays: the answer changes only when this
     * class changes it, or when the user deletes the shortcut by hand.
     */
    public static boolean enabled() {
        var cached = cachedEnabled;
        if (cached != null) return cached;
        var link = link();
        var value = link != null && Files.exists(link);
        cachedEnabled = value;
        return value;
    }

    public static Map<String, Object> status() {
        var m = new LinkedHashMap<String, Object>();
        var exe = exe();
        var link = link();
        m.put("supported", isWindows() || isMac());
        m.put("mechanism", isMac() ? "launch agent" : "startup folder shortcut");
        m.put("shortcut", link == null ? null : link.toString());
        m.put("launcher", exe == null ? null : exe.toString());
        m.put("enabled", link != null && Files.exists(link));
        if (exe == null) m.put("note", launcherName() + " not found on PATH; enable will fail");
        return m;
    }

    public static Map<String, Object> enable() {
        if (!isWindows() && !isMac()) throw new IllegalStateException("start at logon is built for Windows and macOS");
        var exe = exe();
        if (exe == null && !isMac()) {
            throw new IllegalStateException("cannot find " + launcherName() + " — put it on PATH, then enable again");
        }
        var link = link();
        if (link == null) throw new IllegalStateException("cannot locate the Startup folder");
        if (isMac()) return enableMac(link, exe == null ? AutostartMac.jarCommand() : AutostartMac.launcherCommand(exe));
        // WScript.Shell is the only thing on the machine that writes a .lnk, and there is no JDK API
        // for it; driving it through PowerShell keeps the alternative — hand-rolling the Shell Link
        // binary format — out of this project.
        var script = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('" + psQuote(link.toString())
                + "'); $s.TargetPath = '" + psQuote(exe.toString())
                + "'; $s.Arguments = '--background'; $s.WorkingDirectory = '" + psQuote(exe.getParent().toString())
                + "'; $s.Description = 'Holds the WhatsApp session and the index for whatsappcli'; $s.Save()";
        var r = run("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        if (r.code() != 0 || !Files.exists(link)) {
            throw new IllegalStateException("shortcut not created: " + r.text());
        }
        cachedEnabled = null;
        Log.info("autostart enabled: " + link);
        return status();
    }

    public static Map<String, Object> disable() {
        if (!isWindows() && !isMac()) throw new IllegalStateException("start at logon is built for Windows and macOS");
        var link = link();
        try {
            if (link != null) Files.deleteIfExists(link);
        } catch (Exception e) {
            throw new IllegalStateException("shortcut not removed: " + e.getMessage());
        }
        cachedEnabled = null;
        Log.info("autostart disabled");
        return status();
    }

    /** {@code %APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup}, without asking the shell for it. */
    private static Path link() {
        if (isMac()) return AutostartMac.plist(Paths.get(System.getProperty("user.home")));
        var appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) return null;
        return Paths.get(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup", LINK);
    }

    /**
     * The launcher to start, found the way the user starts it: the {@code .exe} on PATH. The JVM's own
     * command line is no help — it names java, not the wrapper that knows the module path.
     */
    private static Path exe() {
        var path = System.getenv("PATH");
        if (path != null) {
            for (var dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                try {
                    var candidate = Paths.get(dir.trim(), launcherName());
                    if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath();
                } catch (Exception ignored) {
                    // An unparseable PATH entry is not this method's problem.
                }
            }
        }
        if (isMac()) return AutostartMac.launcherCandidates(Paths.get(System.getProperty("user.home"))).stream().filter(Files::isRegularFile).findFirst().orElse(null);
        var fallback = Paths.get("C:", "user", "Apps", "cmdtools", "whatsappfx.exe");
        return Files.isRegularFile(fallback) ? fallback : null;
    }

    /** Single quotes are what closes a PowerShell literal string; doubling one escapes it. */
    private static String psQuote(String s) {
        return s.replace("'", "''");
    }

    private static boolean isWindows() {
        return Os.WINDOWS;
    }

    private static boolean isMac() {
        return Os.MAC;
    }

    private static String launcherName() {
        return isWindows() ? "whatsappfx.exe" : "whatsappfx";
    }

    /** Written, not loaded: launchd reads it at the next login, and loading it now would start a second copy of this app. */
    private static Map<String, Object> enableMac(Path plist, List<String> command) {
        try {
            AutostartMac.write(plist, command);
        } catch (Exception e) {
            throw new IllegalStateException("launch agent not written: " + e.getMessage());
        }
        cachedEnabled = null;
        Log.info("autostart enabled: " + plist);
        return status();
    }

    private record Result(int code, String text) {}

    private static Result run(String... command) {
        try {
            var p = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
            var out = new String(p.getInputStream().readAllBytes());
            return new Result(p.waitFor(), out.strip());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e.getMessage()));
        }
    }
}
