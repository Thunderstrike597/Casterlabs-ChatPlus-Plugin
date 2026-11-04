package net.kenji.rumblechat;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import co.casterlabs.rakurai.json.element.JsonObject;
import net.kenji.rumblechat.rumble.Json;

/** Tiny JSON writer: builds a string from Maps/Lists/primitives, then parses it with Rakurai. */
final class JsonOut {
    private JsonOut() {}

    static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    static JsonObject toJsonObject(Map<String, Object> map) {
        JsonObject o = Json.parseObject(write(map));
        if (o == null) throw new IllegalStateException("Failed to build JSON");
        return o;
    }

    static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            str(sb, (String) v);
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Collection<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            str(sb, v.toString());
        }
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
