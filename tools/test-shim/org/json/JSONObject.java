package org.json;

import java.util.HashMap;
import java.util.Map;

/** Minimal shim mirroring the behaviour of android org.json for unit tests. */
public class JSONObject {
    private final Map<String, Object> map = new HashMap<String, Object>();

    public JSONObject() {
    }

    public JSONObject(String source) throws JSONException {
        if (source == null || source.trim().isEmpty()) return;
        String s = source.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) throw new JSONException("not an object: " + s);
        String body = s.substring(1, s.length() - 1).trim();
        if (body.isEmpty()) return;
        for (String part : body.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            int eq = part.indexOf(':');
            if (eq < 0) throw new JSONException("bad pair: " + part);
            String key = unquote(part.substring(0, eq).trim());
            String value = part.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                map.put(key, unquote(value));
            } else if ("true".equals(value) || "false".equals(value)) {
                map.put(key, Boolean.valueOf(value));
            } else if ("null".equals(value)) {
                map.put(key, null);
            } else {
                try {
                    map.put(key, Long.valueOf(value));
                } catch (NumberFormatException e) {
                    try {
                        map.put(key, Double.valueOf(value));
                    } catch (NumberFormatException e2) {
                        map.put(key, unquote(value));
                    }
                }
            }
        }
    }

    private static String unquote(String s) {
        int start = s.indexOf('"'), end = s.lastIndexOf('"');
        if (start >= 0 && end > start) {
            String inner = s.substring(start + 1, end);
            return inner.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n");
        }
        return s;
    }

    public JSONObject put(String key, Object value) throws JSONException {
        if (key == null) throw new JSONException("null key");
        map.put(key, value);
        return this;
    }

    public String optString(String key, String fallback) {
        Object o = map.get(key);
        return o == null ? fallback : String.valueOf(o);
    }

    public long optLong(String key, long fallback) {
        Object o = map.get(key);
        if (o == null) return fallback;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"')
                        .append(String.valueOf(v).replace("\\", "\\\\")
                                .replace("\"", "\\\"").replace("\n", "\\n"))
                        .append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }
}
