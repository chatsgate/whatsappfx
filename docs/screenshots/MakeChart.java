import module java.base;
import module java.desktop;

/// Draws memory.png from the numbers measured on one Windows 11 PC (working set, MB). Run: java MakeChart.java
public class MakeChart {

    record Bar(String label, String sub, int mb, Color c) {}

    public static void main(String[] a) throws Exception {
        var official = new Color(0x7A8189);
        var green = new Color(0x21C063);
        var violet = new Color(0xBC8CFF);
        var bars = java.util.List.of(
                new Bar("WhatsApp (official)", "window open", 1082, official),
                new Bar("WhatsApp (official)", "minimised", 880, official),
                new Bar("WhatsappFx, 1 number", "window open", 340, green),
                new Bar("WhatsappFx, 1 number", "window closed", 134, green),
                new Bar("WhatsappFx, 3 numbers", "windows open", 438, violet),
                new Bar("WhatsappFx, 3 numbers", "windows closed", 270, violet));
        int w = 1200, h = 620, left = 80, bottom = 150, top = 90;
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x161717));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 28));
        g.drawString("Memory in use, MB (working set, lower is better)", left, 52);
        int plotH = h - top - bottom, max = 1200;
        g.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        for (int v = 0; v <= max; v += 200) {
            int y = top + plotH - v * plotH / max;
            g.setColor(new Color(0x2B2D2D));
            g.drawLine(left, y, w - 30, y);
            g.setColor(new Color(0x9A9C9C));
            g.drawString(String.valueOf(v), 30, y + 5);
        }
        int slot = (w - left - 30) / bars.size(), bw = slot - 50;
        for (int i = 0; i < bars.size(); i++) {
            var b = bars.get(i);
            int x = left + i * slot + 25, bh = b.mb * plotH / max, y = top + plotH - bh;
            g.setColor(b.c);
            g.fill(new RoundRectangle2D.Double(x, y, bw, bh, 10, 10));
            g.setColor(Color.WHITE);
            g.setFont(new Font("Segoe UI", Font.BOLD, 22));
            var t = b.mb + " MB";
            g.drawString(t, x + (bw - g.getFontMetrics().stringWidth(t)) / 2, y - 10);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 15));
            for (var line : new String[]{b.label, b.sub}) {
                int ty = top + plotH + 28 + (line == b.sub ? 22 : 0);
                g.setColor(line == b.sub ? new Color(0x9A9C9C) : Color.WHITE);
                g.drawString(line, x + (bw - g.getFontMetrics().stringWidth(line)) / 2, ty);
            }
        }
        g.setColor(new Color(0x9A9C9C));
        g.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        g.drawString("One Windows 11 PC, measured once after a few minutes of settling. WhatsappFx = app + its connector processes; official = whole process tree.", left, h - 22);
        ImageIO.write(img, "png", new File("memory.png"));
        System.out.println("written");
    }
}
