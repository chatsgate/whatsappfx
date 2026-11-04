package chatsgate.whatsappfx.ui.chat;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The function keys: bound on the scene (seen before any focused control) and drawn as one quiet row along the bottom, a small
 * key cap and a word each, every one also clickable. A key bound with no label works but takes no room on the row. No
 * modifiers: a key with Ctrl, Alt or Shift held is left to the control.
 */
final class Legend {

    final Map<KeyCode, Runnable> actions = new LinkedHashMap<>();
    final HBox bar = new HBox(16);

    Legend() {
        bar.getStyleClass().add("fkeys");
        bar.setAlignment(Pos.CENTER_LEFT);
    }

    Legend bind(KeyCode key, String label, Runnable action) {
        actions.put(key, action);
        if (label == null) return this;
        var k = new Label(key == KeyCode.ESCAPE ? "Esc" : key.getName());
        k.getStyleClass().add("fkey-key");
        var l = new Label(label);
        l.getStyleClass().add("fkey-label");
        var chip = new HBox(6, k, l);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("fkey");
        chip.setOnMouseClicked(e -> action.run());
        bar.getChildren().add(chip);
        return this;
    }

    /** Returns true when the key was one of ours and has been run. */
    boolean handle(KeyEvent e) {
        var action = actions.get(e.getCode());
        if (action == null || e.isAltDown() || e.isControlDown() || e.isShiftDown() || e.isMetaDown()) return false;
        action.run();
        e.consume();
        return true;
    }
}
