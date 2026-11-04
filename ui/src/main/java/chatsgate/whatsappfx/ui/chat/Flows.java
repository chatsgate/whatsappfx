package chatsgate.whatsappfx.ui.chat;

import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;

/** ListView scrolling that ListView itself lacks: make a row just visible, and scroll by pages. */
final class Flows {

    private Flows() {}

    static VirtualFlow<?> flow(ListView<?> view) {
        return view.lookup(".virtual-flow") instanceof VirtualFlow<?> f ? f : null;
    }

    /** Scrolls only as far as needed, unlike {@code ListView.scrollTo}, which puts the row at the top. */
    static void reveal(ListView<?> view, int index) {
        var f = flow(view);
        if (f == null) view.scrollTo(index);
        else f.scrollTo(index);
    }

    /** Index of the last row on screen; the last row of all when the flow is not laid out yet. */
    static int lastVisible(ListView<?> view) {
        var f = flow(view);
        var c = f == null ? null : f.getLastVisibleCell();
        return c == null ? view.getItems().size() - 1 : c.getIndex();
    }

    static void page(ListView<?> view, int direction) {
        var f = flow(view);
        if (f != null) f.scrollPixels(direction * view.getHeight() * 0.85);
    }
}
