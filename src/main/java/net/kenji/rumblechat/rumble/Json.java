package net.kenji.rumblechat.rumble;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Null-safe helpers over Rakurai's JSON types. Rumble is inconsistent about
 * whether IDs are strings or numbers, so everything is read leniently.
 */
public final class Json {
    private Json() {}

    public static @Nullable JsonObject parseObject(String raw) {
        try {
            return Rson.DEFAULT.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable JsonElement raw(@Nullable JsonObject obj, String key) {
        if (obj == null || !obj.containsKey(key)) return null;
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e;
    }

    public static @Nullable String str(@Nullable JsonObject obj, String key) {
        JsonElement e = raw(obj, key);
        if (e == null) return null;
        if (e.isJsonString()) return e.getAsString();
        if (e.isJsonObject() || e.isJsonArray()) return null;
        return e.toString(); // numbers / booleans
    }

    public static String str(@Nullable JsonObject obj, String key, String def) {
        String s = str(obj, key);
        return (s == null || s.isEmpty()) ? def : s;
    }

    public static boolean bool(@Nullable JsonObject obj, String key) {
        String s = str(obj, key);
        return s != null && (s.equalsIgnoreCase("true") || s.equals("1"));
    }

    public static double num(@Nullable JsonObject obj, String key, double def) {
        String s = str(obj, key);
        if (s == null) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static @Nullable JsonObject obj(@Nullable JsonObject obj, String key) {
        JsonElement e = raw(obj, key);
        return (e != null && e.isJsonObject()) ? e.getAsObject() : null;
    }

    public static @Nullable JsonArray arr(@Nullable JsonObject obj, String key) {
        JsonElement e = raw(obj, key);
        return (e != null && e.isJsonArray()) ? e.getAsArray() : null;
    }

    public static int size(@Nullable JsonArray arr) {
        if (arr == null) return 0;
        int n = 0;
        for (JsonElement ignored : arr) n++;
        return n;
    }

    /** Element of an array as a string (handles numeric IDs). */
    public static @Nullable String elemStr(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonString()) return e.getAsString();
        return e.toString();
    }
}
