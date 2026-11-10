package chatsgate.whatsappfx.core;

import chatsgate.whatsappfx.wire.Paths2;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Puts the connector where the app looks for it. A release jar (built with {@code -DembedConnector}) carries the
 * connector for its platform as the resource {@code /connector/<name>}; the launcher that fetched this jar has no zip
 * to unpack, so the app copies it into the data folder itself, at every start, when the file there is missing or is
 * another build. Never called while another app is running (the launcher asks the running one to show itself and ends
 * first), so the file being replaced is not in use by this app; if something else holds it, it is left and logged.
 */
public final class ConnectorProvision {

    private ConnectorProvision() {}

    public static void ensure() {
        var name = Paths2.connectorName();
        try (var in = ConnectorProvision.class.getResourceAsStream("/connector/" + name)) {
            if (in == null) return; // a build folder or a Mac archive: the connector is installed by other means
            var target = Paths2.connectorExe();
            var embedded = sha256(in);
            if (Files.isRegularFile(target)) {
                try (var have = Files.newInputStream(target)) {
                    if (embedded.equals(sha256(have))) return;
                }
            }
            Files.createDirectories(target.getParent());
            var part = target.resolveSibling(name + ".part");
            try (var again = ConnectorProvision.class.getResourceAsStream("/connector/" + name)) {
                Files.copy(again, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            if (!Paths2.connectorName().endsWith(".exe")) target.toFile().setExecutable(true);
            Log.info("connector installed from the app: " + target + " (sha256 " + embedded.substring(0, 12) + ")");
        } catch (Exception e) {
            Log.info("connector not installed from the app: " + e);
        }
    }

    private static String sha256(InputStream in) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        var buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        return HexFormat.of().formatHex(md.digest());
    }
}
