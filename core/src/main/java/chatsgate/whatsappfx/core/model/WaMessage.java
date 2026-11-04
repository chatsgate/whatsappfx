package chatsgate.whatsappfx.core.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

import static datapotter.arcadedbhelper.ArcadeType.VERTEX;

/**
 * One message, indexed for search. Flat by necessity: the select DSL filters single keys only, and a
 * nested field fails silently by matching nothing.
 *
 * <p>The full protojson body is deliberately absent — it lives in the chat's journal file, and
 * {@code joff} is the byte offset that gets it back. Thumbnails alone would otherwise put tens of
 * kilobytes per media message into the index.
 */
@ArcadeData(type = VERTEX)
public final class WaMessage extends WaMessage_A {

    String key;
    String account;
    String chat;
    String sender;
    String senderPn;
    String chatPn;
    String msgId;
    Boolean fromMe;
    Boolean isGroup;
    Long ts;
    String pushName;
    String type;
    String text;
    String source;

    String mediaKind;
    String mimetype;
    String filename;
    Long size;

    /** none | queued | done | expired | failed — what happened to the attachment, if any. */
    String mediaState;
    String localPath;
    String localSha256;

    /** Byte offset of this message's line in its chat journal. */
    Long joff;

    String reaction;
    Boolean starred;

    public static final TypeDef<WaMessage> TYPEDEF =
            schemaBuilder()
                    .factory(WaMessage::new)
                    .unique($key)
                    .lsmIndex($chat)
                    .lsmIndex($account)
                    .lsmIndex($ts)
                    .lsmIndex($mediaKind)
                    .lsmIndex($mediaState)
                    .__();

    public static String keyOf(String account, String chat, String msgId) {
        return account + "|" + chat + "|" + msgId;
    }
}
