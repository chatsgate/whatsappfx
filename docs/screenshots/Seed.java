import module java.base;
import module java.desktop;

import chatsgate.whatsappfx.core.IndexDb;
import chatsgate.whatsappfx.core.Ingest;
import chatsgate.whatsappfx.core.Journal;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/// Fills a scratch WhatsappFx home with INVENTED people and conversations, for the README screenshots. Nothing here is real:
/// the names, the phone numbers (555 test range) and the texts are made up, and the pictures are drawn.
///
/// Run with the app NOT running and WHATSAPPFX_HOME pointing at an empty scratch folder:
///   WHATSAPPFX_HOME=/tmp/wa-demo java -cp ui/target/whatsappfx.jar docs/screenshots/Seed.java
public class Seed {

    static final long NOW = System.currentTimeMillis() / 1000;
    static int serial = 1000;

    public static void main(String[] a) throws Exception {
        var db = IndexDb.open().db();
        personal(db);
        studio(db);
        family(db);
        IndexDb.open().shutdown();
        System.out.println("seeded");
    }

    // ---- the three numbers --------------------------------------------------------------------------------------

    static void personal(com.arcadedb.database.Database db) {
        var w = new World("default", "15550100001", db);
        w.person("15550100101", "Ava Martin", 38, 0);
        w.say("15550100101", false, 38, "Are we still on for the climb on Saturday? ⛰️");
        w.say("15550100101", true, 36, "Yes! Leaving at 7, I'll bring the coffee.");
        w.say("15550100101", false, 35, "You're the best. Look what I found on the trail last week:");
        w.image("15550100101", false, 34, "Sunrise from the ridge", 0);
        w.say("15550100101", true, 31, "Wow. That is going on my wall.");
        w.say("15550100101", false, 12, "Weather says clear all day 🙌", 2);
        w.person("15550100102", "Noah Kim", 95, 0);
        w.say("15550100102", false, 95, "Sent you the budget sheet.");
        w.document("15550100102", false, 94, "Trip-budget.xlsx", 48200);
        w.say("15550100102", true, 90, "Perfect, reading it now.");
        w.person("15550100103", "Mia Alvarez", 180, 0);
        w.audio("15550100103", false, 180, 41300);
        w.say("15550100103", true, 170, "Listening on the train, call you later!");
        w.group("120363000000000011", "Weekend Climbers 🧗", 52, 4);
        w.groupSay("120363000000000011", "Ava Martin", "15550100101", 55, "Who is driving on Saturday?");
        w.groupSay("120363000000000011", "Noah Kim", "15550100102", 54, "I can take three people.");
        w.groupSay("120363000000000011", "Mia Alvarez", "15550100103", 53, "I'm in your car 🚗");
        w.groupSay("120363000000000011", "Ava Martin", "15550100101", 52, "Great. Meet at the trailhead café at 7.");
        w.person("15550100104", "Pharmacy", 300, 0);
        w.say("15550100104", false, 300, "Your order is ready for pickup.");
        w.person("15550100105", "Leo", 700, 0);
        w.say("15550100105", true, 700, "Happy birthday, Leo! 🎂");
    }

    static void studio(com.arcadedb.database.Database db) {
        var w = new World("studio", "15550200001", db);
        w.person("15550200101", "Harbor Café", 8, 2);
        w.say("15550200101", false, 9, "Hi! Can you do the menu board in two colours?");
        w.say("15550200101", true, 8, "Absolutely. Sketch by this afternoon.");
        w.image("15550200101", true, 7, "First sketch", 1);
        w.say("15550200101", false, 6, "Love it. Can the logo be a bit bigger?", 2);
        w.person("15550200102", "Print Shop", 70, 0);
        w.say("15550200102", false, 70, "Proofs are ready to approve.");
        w.document("15550200102", false, 69, "Proofs-final.pdf", 902000);
        w.person("15550200103", "Priya Shah", 140, 0);
        w.say("15550200103", false, 141, "Invoice 214 paid ✅");
        w.say("15550200103", true, 140, "Thank you Priya!");
        w.group("120363000000000012", "Studio Team", 20, 3);
        w.groupSay("120363000000000012", "Priya Shah", "15550200103", 24, "Kickoff at 10 tomorrow?");
        w.groupSay("120363000000000012", "Sam Okoye", "15550200104", 22, "Works for me. I'll bring the mood board.");
        w.groupSay("120363000000000012", "Priya Shah", "15550200103", 20, "Perfect.");
        w.person("15550200104", "Sam Okoye", 400, 0);
        w.say("15550200104", false, 400, "Can you send the fonts?");
    }

    static void family(com.arcadedb.database.Database db) {
        var w = new World("family", "15550300001", db);
        w.person("15550300101", "Mum", 15, 1);
        w.say("15550300101", false, 16, "Dinner on Sunday? I'm making your favourite.");
        w.say("15550300101", true, 15, "Yes please! Can I bring dessert?");
        w.say("15550300101", false, 15, "Bring the berries 🍓", 1);
        w.person("15550300102", "Dad", 120, 0);
        w.say("15550300102", false, 121, "Look at the garden!");
        w.image("15550300102", false, 120, "The tomatoes are finally here", 2);
        w.person("15550300103", "Sis", 240, 0);
        w.say("15550300103", true, 245, "Did you book the flights?");
        w.say("15550300103", false, 240, "Done. Landing at 18:40.");
        w.group("120363000000000013", "Family 🏡", 60, 0);
        w.groupSay("120363000000000013", "Mum", "15550300101", 62, "Photos from Sunday are in the album.");
        w.groupSay("120363000000000013", "Sis", "15550300103", 60, "Thank you! ❤️");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static final class World {
        final String account, me, ownJid;
        final Ingest ingest;
        final Map<String, Long> last = new HashMap<>(), unread = new HashMap<>(), isGroup = new HashMap<>();
        final Map<String, String> names = new LinkedHashMap<>();

        World(String account, String number, com.arcadedb.database.Database db) {
            this.account = account;
            this.me = number;
            this.ownJid = number + ":3@s.whatsapp.net";
            this.ingest = new Ingest(account, new Journal(account), db);
        }

        void person(String digits, String name, int minutesAgo, int unreadCount) {
            var jid = digits + "@s.whatsapp.net";
            names.put(jid, name);
            unread.put(jid, (long) unreadCount);
            var c = new JsonObject();
            c.addProperty("jid", jid);
            c.addProperty("name", name);
            c.addProperty("pn", digits);
            c.addProperty("unreadCount", unreadCount);
            c.addProperty("lastMsgTs", NOW - minutesAgo * 60L);
            ingest.chat(c);
            var k = new JsonObject();
            k.addProperty("jid", jid);
            k.addProperty("fullName", name);
            k.addProperty("pushName", name);
            ingest.contact(k);
        }

        void group(String digits, String name, int minutesAgo, int unreadCount) {
            var jid = digits + "@g.us";
            var c = new JsonObject();
            c.addProperty("jid", jid);
            c.addProperty("name", name);
            c.addProperty("unreadCount", unreadCount);
            c.addProperty("lastMsgTs", NOW - minutesAgo * 60L);
            ingest.chat(c);
        }

        JsonObject base(String chat, boolean fromMe, int minutesAgo, String senderDigits, String pushName) {
            var m = new JsonObject();
            m.addProperty("chat", chat);
            m.addProperty("id", "3EB0DEMO" + (serial++) + Integer.toHexString(chat.hashCode()).toUpperCase());
            m.addProperty("fromMe", fromMe);
            m.addProperty("isGroup", chat.endsWith("@g.us"));
            m.addProperty("source", fromMe ? "sent" : "live");
            m.addProperty("ts", NOW - minutesAgo * 60L);
            m.addProperty("sender", fromMe ? ownJid : senderDigits + "@s.whatsapp.net");
            m.addProperty("pushName", fromMe ? "Me" : pushName);
            return m;
        }

        void say(String digits, boolean fromMe, int minutesAgo, String text) {
            say(digits, fromMe, minutesAgo, text, 0);
        }

        void say(String digits, boolean fromMe, int minutesAgo, String text, int ignored) {
            var chat = digits + "@s.whatsapp.net";
            var m = base(chat, fromMe, minutesAgo, digits, names.getOrDefault(chat, digits));
            m.addProperty("type", "text");
            m.addProperty("text", text);
            m.add("body", JsonParser.parseString("{\"conversation\":" + quote(text) + "}"));
            ingest.message(m);
        }

        void groupSay(String groupDigits, String who, String senderDigits, int minutesAgo, String text) {
            var chat = groupDigits + "@g.us";
            var m = base(chat, false, minutesAgo, senderDigits, who);
            m.addProperty("type", "text");
            m.addProperty("text", text);
            m.add("body", JsonParser.parseString("{\"conversation\":" + quote(text) + "}"));
            ingest.message(m);
        }

        void image(String digits, boolean fromMe, int minutesAgo, String caption, int scene) {
            var chat = digits + "@s.whatsapp.net";
            var m = base(chat, fromMe, minutesAgo, digits, names.getOrDefault(chat, digits));
            m.addProperty("type", "image");
            m.addProperty("text", caption);
            var thumb = Base64.getEncoder().encodeToString(picture(scene, 160, 120));
            m.add("body", JsonParser.parseString("{\"imageMessage\":{\"mimetype\":\"image/jpeg\",\"width\":1600,\"height\":1200,\"fileLength\":\"240000\",\"JPEGThumbnail\":\"" + thumb + "\",\"caption\":" + quote(caption) + "}}"));
            m.add("media", JsonParser.parseString("{\"expired\":false,\"kind\":\"image\",\"mimetype\":\"image/jpeg\",\"size\":240000}"));
            ingest.message(m);
        }

        void document(String digits, boolean fromMe, int minutesAgo, String file, long size) {
            var chat = digits + "@s.whatsapp.net";
            var m = base(chat, fromMe, minutesAgo, digits, names.getOrDefault(chat, digits));
            m.addProperty("type", "document");
            m.addProperty("text", "");
            m.add("body", JsonParser.parseString("{\"documentMessage\":{\"fileName\":" + quote(file) + ",\"fileLength\":\"" + size + "\"}}"));
            m.add("media", JsonParser.parseString("{\"expired\":false,\"kind\":\"document\",\"filename\":" + quote(file) + ",\"size\":" + size + "}"));
            ingest.message(m);
        }

        void audio(String digits, boolean fromMe, int minutesAgo, long size) {
            var chat = digits + "@s.whatsapp.net";
            var m = base(chat, fromMe, minutesAgo, digits, names.getOrDefault(chat, digits));
            m.addProperty("type", "audio");
            m.addProperty("text", "");
            m.add("body", JsonParser.parseString("{\"audioMessage\":{\"fileLength\":\"" + size + "\",\"PTT\":true}}"));
            m.add("media", JsonParser.parseString("{\"expired\":false,\"kind\":\"audio\",\"mimetype\":\"audio/ogg\",\"size\":" + size + "}"));
            ingest.message(m);
        }
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /// A drawn landscape, three scenes, as a small JPEG: a sky gradient, a sun and layered hills.
    static byte[] picture(int scene, int w, int h) {
        var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color[][] sky = {{new Color(0xFFB36B), new Color(0xFF6B8B)}, {new Color(0x7EC8F2), new Color(0xDDF3FF)}, {new Color(0x9BD97C), new Color(0xE8F7C8)}};
        Color[][] hills = {{new Color(0x3B2A5B), new Color(0x241A3F)}, {new Color(0x2F7D5B), new Color(0x1F5C43)}, {new Color(0x3E8E41), new Color(0x2A6B31)}};
        g.setPaint(new GradientPaint(0, 0, sky[scene][0], 0, h, sky[scene][1]));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 245, 200, 230));
        g.fill(new Ellipse2D.Double(w * 0.62, h * 0.22, h * 0.34, h * 0.34));
        for (var layer = 0; layer < 2; layer++) {
            g.setColor(hills[scene][layer]);
            var p = new Path2D.Double();
            p.moveTo(0, h);
            for (var x = 0; x <= w; x += 4) p.lineTo(x, h * (0.62 + layer * 0.14) + Math.sin(x / (22.0 + layer * 9) + scene + layer) * h * 0.07);
            p.lineTo(w, h);
            p.closePath();
            g.fill(p);
        }
        g.dispose();
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
