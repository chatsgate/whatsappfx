package chatsgate.whatsappfx.core;

import com.google.gson.JsonObject;

/** Null-tolerant reads. Connector payloads omit fields rather than sending nulls. */
public final class Json {

    private Json() {}

    public static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    public static Long num(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : null;
    }

    public static Boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : null;
    }

    public static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }
}
