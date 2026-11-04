package chatsgate.whatsappfx.ui.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import chatsgate.whatsappfx.core.Log;
import chatsgate.whatsappfx.wire.Paths2;

import module java.base;

/**
 * Files waiting to be sent to the open chat, and the small card above the write box that shows them: a thumbnail for one image, a
 * file mark and the names otherwise, "Enter sends, Esc cancels". They arrive by paste (a screenshot, or copied files), by drop, or
 * from the attach button; the text in the write box goes as the caption of the first. Images go as images, everything else as a
 * document.
 */
final class Attach {

    static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "webp");

    final List<Path> files = new ArrayList<>();
    final HBox bar = new HBox(12);
    private final ImageView thumb = new ImageView();
    private final Label mark = new Label("📄"), name = new Label(), hint = new Label();
    /** Told when the person closes the card with its cross. */
    Runnable onCancel;

    Attach() {
        bar.getStyleClass().add("attach-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 16, 8, 16));
        thumb.setFitWidth(64);
        thumb.setFitHeight(64);
        thumb.setPreserveRatio(true);
        mark.setStyle("-fx-font-size: 34px;");
        name.getStyleClass().add("attach-name");
        hint.getStyleClass().add("attach-hint");
        var text = new VBox(2, name, hint);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        var close = new Button("✕");
        close.getStyleClass().add("attach-close");
        close.setFocusTraversable(false);
        close.setOnAction(e -> { if (onCancel != null) onCancel.run(); });
        bar.getChildren().addAll(thumb, mark, text, new Region(), close);
        bar.setVisible(false);
        bar.setManaged(false);
    }

    boolean pending() { return !files.isEmpty(); }

    static boolean isImage(Path p) {
        var n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return IMAGES.contains(n.substring(n.lastIndexOf('.') + 1));
    }

    void set(List<Path> now) {
        files.clear();
        files.addAll(now);
        var one = files.size() == 1 ? files.getFirst() : null;
        var image = one != null && isImage(one);
        thumb.setImage(image ? new Image(one.toUri().toString(), 128, 128, true, true, true) : null);
        thumb.setVisible(image);
        thumb.setManaged(image);
        mark.setVisible(!image);
        mark.setManaged(!image);
        name.setText(one != null ? one.getFileName().toString()
                : files.size() + " files: " + files.stream().limit(3).map(f -> f.getFileName().toString()).collect(Collectors.joining(", ")) + (files.size() > 3 ? " …" : ""));
        hint.setText(size(files) + (image ? "  ·  sent as a picture" : "  ·  sent as a document") + "  ·  Enter sends, Esc cancels. What you type is the caption.");
        bar.setVisible(true);
        bar.setManaged(true);
    }

    void clear() {
        files.clear();
        thumb.setImage(null);
        bar.setVisible(false);
        bar.setManaged(false);
    }

    private static String size(List<Path> fs) {
        var n = fs.stream().mapToLong(f -> { try { return Files.size(f); } catch (IOException e) { return 0; } }).sum();
        return n >= 1 << 20 ? String.format("%.1f MB", n / 1048576.0) : Math.max(1, n / 1024) + " KB";
    }

    /** Copied files (from Explorer or Finder), else a pasted or dropped picture when no text came with it; empty when it is only text. */
    static List<Path> from(Clipboard cb) {
        try {
            if (cb.hasFiles()) return cb.getFiles().stream().map(File::toPath).filter(Files::isRegularFile).toList();
            if (cb.hasImage() && !cb.hasString()) return List.of(save(cb.getImage()));
        } catch (Exception e) {
            Log.warn("nothing taken from the clipboard: " + e.getMessage());
        }
        return List.of();
    }

    /** A pasted picture is written as a PNG under run/paste; files older than a day there are removed. */
    static Path save(Image image) throws IOException {
        var dir = Paths2.home().resolve("run").resolve("paste");
        Files.createDirectories(dir);
        try (var old = Files.list(dir)) {
            for (var f : old.toList()) if (Files.getLastModifiedTime(f).toMillis() < System.currentTimeMillis() - 86_400_000L) Files.deleteIfExists(f);
        }
        var file = dir.resolve("paste-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png");
        var w = (int) image.getWidth();
        var h = (int) image.getHeight();
        var out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var in = image.getPixelReader();
        for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) out.setRGB(x, y, in.getArgb(x, y));
        javax.imageio.ImageIO.write(out, "png", file.toFile());
        return file;
    }
}
