package chatsgate.whatsappfx.ui.notify;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.util.Duration;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.daemon.Calls;
import chatsgate.whatsappfx.daemon.Hub;
import chatsgate.whatsappfx.ui.Sound;
import chatsgate.whatsappfx.ui.chat.ChatWindow;

import java.util.List;

/**
 * An incoming call. It rings until the call ends, is answered or declined anywhere, or the person acts here. This PC
 * cannot pick up (whatsmeow carries call signalling, not audio), so the card offers what WhatsApp's phone app offers
 * instead: decline with a quick reply (F1-F3, or typed), decline (F4), silence (F6), open the chat (F5). Once the call
 * ends unanswered it becomes a missed-call card that stays until closed, and replies still go.
 */
final class CallCard extends Card {

    static final List<String> QUICK = List.of(
            "I'll call you right back.",
            "Can't talk now, I'll call you later.",
            "Please send me a message.");
    static final double RING_MAX_S = 60;

    final String callId, name;
    Sound.Ring ring;
    boolean ended, declining;

    CallCard(Hub hub, String chat, String callId, String name, boolean video) {
        super(hub, "call:" + callId, chat, 0);
        this.callId = callId;
        this.name = name;
        node.getStyleClass().add("call");
        title.setText("📞  " + name);
        text.setText("Incoming WhatsApp " + (video ? "video " : "") + "call. Pick up on your phone, or reply from here.");
        reply.setPromptText("Decline with a message, Enter sends");
        for (int i = 0; i < QUICK.size(); i++) {
            var t = QUICK.get(i);
            action(KeyCode.valueOf("F" + (i + 1)), t, "green", () -> answer(t));
        }
        action(KeyCode.F4, "Decline", "red", () -> answer(null));
        action(KeyCode.F5, "Open chat", "blue", () -> ChatWindow.show(hub, chat));
        action(KeyCode.F6, "Silence", "grey", this::silence);
        ring = Sound.ring();
        var stop = new PauseTransition(Duration.seconds(RING_MAX_S));
        stop.setOnFinished(e -> silence());
        stop.play();
    }

    @Override
    boolean important() {
        return !ended;
    }

    @Override
    void send(String text) {
        answer(text);
    }

    /**
     * Declines the ringing call and sends {@code message} if given.
     * After the call has ended there is nothing to decline, and the message just goes.
     */
    void answer(String message) {
        if (declining || sending) return;
        hub.app.allow("decline a call");
        silence();
        if (ended) { if (message != null) super.send(message); return; }
        declining = true;
        status.setText("Declining…");
        Thread.ofVirtual().start(() -> {
            String err = null;
            try {
                if (chat == null) throw new IllegalStateException("test card: there is no real call to decline");
                Calls.reject(hub, chat, callId);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.warn("call decline from a notification failed: " + err);
            }
            var failed = err;
            Platform.runLater(() -> {
                declining = false;
                if (failed == null) ended("declined");
                status.setText(failed == null ? "Declined." : "Not declined: " + failed);
                if (message != null) super.send(message);
                else if (failed == null) sent(null);
            });
        });
    }

    void silence() {
        if (ring != null) ring.stop();
        ring = null;
    }

    /** FX thread: the call was answered, declined or ended somewhere (this PC, the phone, the caller). */
    void ended(String state) {
        silence();
        if (ended) return;
        ended = true;
        var answered = "accept".equals(state);
        var declined = "declined".equals(state);
        title.setText((answered ? "📞  Answered: " : declined ? "📞  Declined: " : "📞  Missed call: ") + name);
        text.setText(answered ? "The call was picked up on another device." : "You can still send a message from here.");
        chips.getChildren().removeIf(n -> n instanceof javafx.scene.control.Button b && (b.getText().startsWith("F4") || b.getText().startsWith("F6")));
        actions.remove(KeyCode.F4);
        actions.remove(KeyCode.F6);
        reply.setPromptText("Type a message, Enter sends");
        if (answered) { var t = new PauseTransition(Duration.seconds(8)); t.setOnFinished(e -> { if (!busy()) dismiss(); }); t.play(); }
        Cards.place();
    }

    @Override
    void closed() {
        super.closed();
        silence();
    }
}
