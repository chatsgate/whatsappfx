package chatsgate.whatsappfx.ui.notify;

import com.google.gson.JsonObject;
import javafx.application.Platform;
import chatsgate.whatsappfx.core.Json;
import chatsgate.whatsappfx.daemon.Calls;
import chatsgate.whatsappfx.daemon.Hub;

/** What the rest of the app calls: a message card, and the call card with its ringing. */
public final class Notices {

    private Notices() {}

    /** FX thread. {@code chat} null: several chats at once, so no reply box, F5 opens the chats. */
    public static void message(Hub hub, String title, String text, String chat) {
        MessageCard.show(hub, title, text, chat);
    }

    /**
     * Any call event, on the connector's thread (the caller's name is an index read, so it is looked up here, off the FX
     * thread). An incoming offer raises a ringing card; accept, reject or terminate stops it.
     */
    public static void call(Hub hub, JsonObject data) {
        var call = Json.obj(data, "call");
        if (call == null) return;
        var callId = Json.str(call, "callId");
        var state = Json.str(call, "state");
        if (callId == null || state == null) return;
        if (Calls.isIncomingOffer(data)) {
            var name = Calls.callerName(hub, data);
            var chat = Json.str(data, "chat");
            var video = String.valueOf(Json.str(data, "text")).contains("video");
            Platform.runLater(() -> { if (Cards.find("call:" + callId) == null) Cards.add(new CallCard(hub, chat, callId, name, video)); });
        } else if (!"offer".equals(state)) {
            Platform.runLater(() -> { if (Cards.find("call:" + callId) instanceof CallCard c) c.ended(state); });
        }
    }
}
