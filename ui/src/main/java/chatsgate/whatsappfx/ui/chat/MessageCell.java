package chatsgate.whatsappfx.ui.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** A message bubble: ours right and green-tinted, theirs left and grey; a day chip over the first of a day. */
final class MessageCell extends ListCell<Msg> {

    final MessagePane pane;
    final Label day = new Label();
    final HBox dayRow = new HBox(day);
    final Label who = new Label();
    final RichText body = new RichText(0);
    final Label time = new Label();
    final HBox timeRow = new HBox(time);
    /** The picture of a picture, video or sticker message: its thumbnail at once, the file when it is here. */
    static final double MAX = 300;
    final ImageView picView = new ImageView();
    final javafx.scene.control.Label play = new javafx.scene.control.Label("▶");
    final StackPane pic = new StackPane(picView, play);
    /** An attachment without a picture (a document, a voice note, a picture whose thumbnail did not come): what it is, how big, and what a click does. */
    final Label cardIcon = new Label();
    final Label cardName = new Label();
    final Label cardSub = new Label();
    final HBox card = new HBox(10, cardIcon, new VBox(1, cardName, cardSub));
    final VBox bubble = new VBox(2, who, pic, card, body, timeRow);
    final HBox line = new HBox(bubble);
    final VBox root = new VBox(4, dayRow, line);

    MessageCell(MessagePane pane, ListView<Msg> view) {
        this.pane = pane;
        day.getStyleClass().add("day-chip");
        dayRow.setAlignment(Pos.CENTER);
        dayRow.setPadding(new Insets(8, 0, 6, 0));
        who.getStyleClass().add("msg-who");
        picView.setPreserveRatio(true);
        picView.setSmooth(true);
        play.setStyle("-fx-text-fill: white; -fx-font-size: 30px; -fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 30; -fx-padding: 4 12 4 14;");
        pic.setAlignment(Pos.CENTER);
        pic.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        var round = new javafx.scene.shape.Rectangle();
        round.setArcWidth(10);
        round.setArcHeight(10);
        round.widthProperty().bind(pic.widthProperty());
        round.heightProperty().bind(pic.heightProperty());
        pic.setClip(round);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(230);
        card.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 6; -fx-cursor: hand;");
        cardIcon.setStyle("-fx-font-size: 26px; -fx-text-fill: -wa-text;");
        cardName.setStyle("-fx-text-fill: -wa-text; -fx-font-size: 14px; -fx-font-weight: bold;");
        cardSub.setStyle("-fx-text-fill: -wa-muted; -fx-font-size: 12px;");
        // one click on a picture or a card opens the file; when it is not here yet it is downloaded first, into the Folder of Settings, Attachments
        pic.setOnMouseClicked(e -> { if (e.getClickCount() == 1 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) open(); });
        card.setOnMouseClicked(e -> { if (e.getClickCount() == 1 && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) open(); });
        time.getStyleClass().add("msg-time");
        timeRow.setAlignment(Pos.CENTER_RIGHT);
        bubble.setPadding(new Insets(6, 9, 5, 9));
        bubble.maxWidthProperty().bind(view.widthProperty().multiply(0.66));
        bubble.setMinWidth(70);
        root.setPadding(new Insets(1, 56, 1, 56));
        setPrefWidth(0);
        setText(null);
        // right click (or the Menu key) on a bubble: the actions for that message
        setOnContextMenuRequested(e -> {
            var m = getItem();
            var w = ChatWindow.current(pane.data.hub);
            if (m == null || m.id() == null || w == null) return;
            MessageMenu.of(w, m).show(bubble, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private void showCard(Msg.Pic p) {
        cardIcon.setText(switch (p.kind()) { case "image", "sticker" -> "🖼"; case "video" -> "🎞"; case "audio" -> "🎧"; default -> "📄"; });
        var named = p.name() != null && !p.name().isBlank();
        cardName.setText(named ? p.name() : switch (p.kind()) { case "image" -> "Photo"; case "video" -> "Video"; case "audio" -> "Audio"; case "sticker" -> "Sticker"; default -> "File"; });
        cardSub.setText((p.size() > 0 ? size(p.size()) + "  ·  " : "") + (p.file() != null ? "Click to open" : "Click to download and open"));
    }

    /** A picture file JavaFX can draw (not webp, not a video). */
    static boolean drawable(Msg.Pic p) {
        return p.file() != null && "image".equals(p.kind()) && p.file().toLowerCase().matches(".*[.](png|jpe?g|gif|bmp)");
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format("%.1f MB", b / 1048576.0);
    }

    /** Opens the attachment of this bubble in the system viewer, fetching it first when it is not on this machine. */
    private void open() {
        var m = getItem();
        if (m == null || m.pic() == null) return;
        var p = m.pic();
        var w = ChatWindow.current(pane.data.hub);
        java.util.function.Consumer<String> say = t -> javafx.application.Platform.runLater(() -> { if (w != null) w.status.setText(t); });
        say.accept(p.file() != null ? "opening …" : "downloading into " + pane.data.hub.config.mediaRoot() + " …");
        pane.data.io.submit(() -> {
            try {
                var f = pane.data.fileOf(p);
                java.awt.Desktop.getDesktop().open(f.toFile());
                say.accept(p.file() != null ? "" : "saved in " + f.getParent());
            } catch (Exception ex) {
                chatsgate.whatsappfx.core.Log.warn("could not open the attachment: " + ex.getMessage());
                say.accept("could not open it: " + ex.getMessage());
            }
        });
    }

    /** The thumbnail first (it is inside the message, so it is there at once), then the file in its place when it has loaded. */
    private void showPic(Msg m) {
        var p = m.pic();
        var drawn = p != null && (p.thumb() != null || drawable(p));
        card.setVisible(p != null && !drawn);
        card.setManaged(card.isVisible());
        pic.setVisible(drawn);
        pic.setManaged(drawn);
        if (p != null && !drawn) { showCard(p); picView.setImage(null); return; }
        if (p == null) { picView.setImage(null); return; }
        play.setVisible("video".equals(p.kind()));
        Image thumb = p.thumb() == null ? null : new Image(new java.io.ByteArrayInputStream(p.thumb()));
        picView.setImage(thumb);
        double w = p.w() > 0 ? p.w() : thumb != null ? thumb.getWidth() * 2.4 : MAX;
        double h = p.h() > 0 ? p.h() : thumb != null ? thumb.getHeight() * 2.4 : MAX;
        var scale = Math.min(MAX / w, MAX / h);
        picView.setFitWidth(Math.max(40, w * scale));
        picView.setFitHeight(Math.max(40, h * scale));
        pic.setCursor(p.file() != null ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
        if (drawable(p)) {
            var full = new Image(new java.io.File(p.file()).toURI().toString(), MAX * 2, MAX * 2, true, true, true);
            full.progressProperty().addListener((o, was, now) -> {
                if (now.doubleValue() >= 1 && !full.isError() && getItem() == m) picView.setImage(full);
            });
        }
    }

    @Override
    protected void updateItem(Msg m, boolean empty) {
        super.updateItem(m, empty);
        if (empty || m == null) {
            setGraphic(null);
            return;
        }
        day.setText(m.day());
        dayRow.setVisible(m.day() != null);
        dayRow.setManaged(m.day() != null);
        var showWho = pane.group && !m.fromMe() && m.who() != null && !m.who().isBlank();
        who.setText(showWho ? m.who() : "");
        who.setVisible(showWho);
        who.setManaged(showWho);
        if (showWho) who.setStyle("-fx-text-fill: " + Avatar.COLORS[Math.floorMod(m.who().hashCode(), Avatar.COLORS.length)] + ";");
        showPic(m);
        var caption = m.text() != null && !m.text().isBlank();
        body.setVisible(caption || m.pic() == null);
        body.setManaged(body.isVisible());
        body.set(m.text(), "-fx-font-size: 14px;");
        time.setText(m.id() == null ? "sending…"
                : (m.starred() ? "★ " : "") + (m.reaction() == null || m.reaction().isBlank() ? "" : m.reaction() + "  ") + Times.clock(m.ts()));
        bubble.getStyleClass().setAll(m.fromMe() ? "bubble-out" : "bubble-in");
        line.setAlignment(m.fromMe() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        setGraphic(root);
    }
}
