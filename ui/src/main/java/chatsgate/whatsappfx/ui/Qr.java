package chatsgate.whatsappfx.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

import java.util.Map;

/** The pairing payload as a scannable image, painted straight into a JavaFX image. */
public final class Qr {

    private Qr() {}

    public static Image image(String payload, int size) {
        try {
            var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L));
            var img = new WritableImage(size, size);
            var px = img.getPixelWriter();
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    px.setArgb(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
