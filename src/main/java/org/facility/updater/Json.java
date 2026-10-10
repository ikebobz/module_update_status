package org.facility.updater;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer.
 * Objects become LinkedHashMap, arrays ArrayList, numbers Long or Double.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("Trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) o;
    }

    // ------------------------------------------------------------------ reading

    private Object value() {
        if (i >= s.length()) throw err("Unexpected end of input");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("Unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            if (!peek('"')) throw err("Expected string key");
            String k = string();
            ws(); expect(':'); ws();
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++; ws();
        if (peek(']')) { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: throw err("Bad escape");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek('-')) i++;
        boolean dbl = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { i++; }
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { dbl = true; i++; }
            else break;
        }
        String n = s.substring(start, i);
        return dbl ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("Expected " + w);
        i += w.length();
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private void expect(char c) {
        if (!peek(c)) throw err("Expected '" + c + "'");
        i++;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("JSON parse error at " + i + ": " + m);
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o, 0);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object o, int indent) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String) { quote(b, (String) o); return; }
        if (o instanceof Number || o instanceof Boolean) { b.append(o); return; }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            Iterator<Map.Entry<String, Object>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                pad(b, indent + 1);
                quote(b, e.getKey());
                b.append(": ");
                write(b, e.getValue(), indent + 1);
                if (it.hasNext()) b.append(',');
                b.append('\n');
            }
            pad(b, indent);
            b.append('}');
            return;
        }
        if (o instanceof Iterable) {
            Iterator<?> it = ((Iterable<?>) o).iterator();
            if (!it.hasNext()) { b.append("[]"); return; }
            b.append("[\n");
            while (it.hasNext()) {
                pad(b, indent + 1);
                write(b, it.next(), indent + 1);
                if (it.hasNext()) b.append(',');
                b.append('\n');
            }
            pad(b, indent);
            b.append(']');
            return;
        }
        quote(b, o.toString());
    }

    private static void pad(StringBuilder b, int n) {
        for (int k = 0; k < n; k++) b.append("  ");
    }

    private static void quote(StringBuilder b, String v) {
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------ helpers

    /** Reads a possibly dotted path ("data.items") from a parsed JSON value. */
    @SuppressWarnings("unchecked")
    public static Object path(Object root, String dotted) {
        if (dotted == null || dotted.trim().isEmpty()) return root;
        Object cur = root;
        for (String part : dotted.split("\\.")) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(part);
        }
        return cur;
    }

    public static String str(Object o) { return o == null ? null : o.toString(); }

    public static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        return o != null && Boolean.parseBoolean(o.toString());
    }

    public static long lng(Object o, long def) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return def;
        try { return Long.parseLong(o.toString().trim()); } catch (NumberFormatException e) { return def; }
    }
}
