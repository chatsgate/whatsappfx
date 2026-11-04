package chatsgate.whatsappfx.core.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

import static datapotter.arcadedbhelper.ArcadeType.VERTEX;

/** A name for a JID, merged from every source that offers one. */
@ArcadeData(type = VERTEX)
public final class WaContact extends WaContact_A {

    String key;
    String account;
    String jid;
    String pushName;
    String fullName;
    String firstName;
    String businessName;

    public static final TypeDef<WaContact> TYPEDEF =
            schemaBuilder()
                    .factory(WaContact::new)
                    .unique($key)
                    .lsmIndex($account)
                    .lsmIndex($jid)
                    .__();

    public static String keyOf(String account, String jid) {
        return account + "|" + jid;
    }

    public String best() {
        for (var v : new String[]{fullName, businessName, pushName, firstName}) {
            if (v != null && !v.isBlank()) return v;
        }
        return jid;
    }
}
