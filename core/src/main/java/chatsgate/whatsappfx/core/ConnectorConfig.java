package chatsgate.whatsappfx.core;

import chatsgate.whatsappfx.wire.Paths2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * How the connector is launched. The sync-shaping values here reach WhatsApp only in the pairing
 * payload, so changing them after a device is linked has no effect until it is unlinked and paired
 * again — they are recorded rather than tuned.
 */
public record ConnectorConfig(Path exe, String accountId, String deviceName, boolean fullSync,
                              int syncDays, int quotaMb, boolean autoConnect) {

    public static ConnectorConfig of(String accountId, Config c) {
        return new ConnectorConfig(locateExe(), accountId, c.deviceName(), c.fullSync(),
                c.syncDays(), c.quotaMb(), true);
    }

    /** Installed next to the app first, then the dev build under connector/. */
    public static Path locateExe() {
        var installed = Paths2.connectorExe();
        if (Files.isRegularFile(installed)) return installed;
        var dev = Path.of("connector", Paths2.connectorName()).toAbsolutePath();
        if (Files.isRegularFile(dev)) return dev;
        return installed;
    }

    public List<String> command() {
        var c = new ArrayList<String>();
        c.add(exe.toString());
        c.add("--store");
        c.add(Paths2.sessionDb(accountId).toString());
        c.add("--spool");
        c.add(Paths2.spoolDir(accountId).toString());
        c.add("--device-name");
        c.add(deviceName);
        c.add("--full-sync=" + fullSync);
        c.add("--sync-days=" + syncDays);
        c.add("--quota-mb=" + quotaMb);
        c.add("--connect=" + autoConnect);
        return c;
    }
}
