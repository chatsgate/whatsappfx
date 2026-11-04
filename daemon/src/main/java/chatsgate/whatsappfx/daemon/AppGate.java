package chatsgate.whatsappfx.daemon;

/**
 * The in-app path: the chat window, the notification cards and the settings window call the same operations as
 * whatsappcli, but the person is typing in the app's own window, so nothing is asked. Every such call goes through
 * {@link #allow} so that a check can be added in this one place.
 */
public final class AppGate {

    public void allow(String what) {
    }
}
