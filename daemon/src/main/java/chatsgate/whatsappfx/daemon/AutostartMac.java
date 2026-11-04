package chatsgate.whatsappfx.daemon;

import module java.base;

/** Start at logon on a Mac: a LaunchAgent in the user's own ~/Library/LaunchAgents, which launchd reads at the next login. */
final class AutostartMac {

    static final String LABEL = "io.github.chatsgate.whatsappfx";

    private AutostartMac() {}

    static Path plist(Path userHome) {
        return userHome.resolve("Library").resolve("LaunchAgents").resolve(LABEL + ".plist");
    }

    /** The command launchd runs at logon, one {@code <string>} each: the launcher and {@code --background}, or the jar run by this JVM. */
    static String content(List<String> command) throws IOException {
        var args = command.stream().map(a -> "        <string>" + xml(a) + "</string>").collect(Collectors.joining("\n"));
        try (var in = AutostartMac.class.getResourceAsStream("/launchagent.plist")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{label}}", LABEL).replace("{{arguments}}", args);
        }
    }

    static void write(Path file, List<String> command) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content(command), StandardCharsets.UTF_8);
    }

    static List<String> launcherCommand(Path launcher) {
        return List.of(launcher.toString(), "--background");
    }

    /**
     * No native launcher is installed (none is built for a Mac yet): the jar this app runs from, started by the same java. The menu bar
     * names the app after this property, otherwise it says "java".
     */
    static List<String> jarCommand() {
        var java = ProcessHandle.current().info().command().orElse("java");
        var jar = Path.of(System.getProperty("java.class.path", "").split(File.pathSeparator)[0]).toAbsolutePath();
        if (!jar.toString().endsWith(".jar")) throw new IllegalStateException("not started from a jar: cannot tell what to start at logon");
        return List.of(java, "--enable-native-access=ALL-UNNAMED", "-Dapple.awt.application.name=WhatsappFx", "-jar", jar.toString(), "--background");
    }

    static List<Path> launcherCandidates(Path userHome) {
        return List.of(userHome.resolve(".local/bin/whatsappfx"), Path.of("/opt/homebrew/bin/whatsappfx"), Path.of("/usr/local/bin/whatsappfx"));
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
