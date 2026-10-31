// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

/** Minimal dependency-free JSON encoder/parser (good enough for this app). */
public final class Json {

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    /** Human-readable encoding (import files are meant to be hand-editable). */
    public static String encodePretty(Object value, int indent) {
        StringBuilder sb = new StringBuilder();
        writePretty(sb, value, 0, indent);
        return sb.toString();
    }

    private static void writePretty(StringBuilder sb, Object v, int depth, int indent) {
        String pad = " ".repeat(indent * depth);
        String padIn = " ".repeat(indent * (depth + 1));
        if (v instanceof java.util.Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(padIn).append('"').append(e.getKey().toString()).append("\": ");
                writePretty(sb, e.getValue(), depth + 1, indent);
            }
            sb.append('\n').append(pad).append('}');
            return;
        }
        if (v instanceof java.util.Collection<?> c) {
            if (c.isEmpty()) { sb.append("[]"); return; }
            // short scalar lists stay on one line, objects get one per line
            boolean scalars = c.stream().allMatch(o -> !(o instanceof java.util.Map) && !(o instanceof java.util.Collection));
            if (scalars) { write(sb, v); return; }
            sb.append("[\n");
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(padIn);
                writePretty(sb, o, depth + 1, indent);
            }
            sb.append('\n').append(pad).append(']');
            return;
        }
        write(sb, v);
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { sb.append('"'); escape(sb, s); sb.append('"'); return; }
        if (v instanceof Boolean b) { sb.append(b.booleanValue()); return; }
        if (v instanceof Number n) { sb.append(n.toString()); return; }
        if (v instanceof java.util.Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey().toString()).append("\":");
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof java.util.Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
            return;
        }
        sb.append('"');
        escape(sb, v.toString());
        sb.append('"');
    }

    private static void escape(StringBuilder sb, String s) {
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
    }

    public static Object parse(String s) {
        P p = new P(s);
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new IllegalArgumentException("Trailing JSON at " + p.i);
        return v;
    }

    private static final class P {
        private final String s;
        private int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            return number();
        }
        void expect(String lit) {
            if (!s.startsWith(lit, i)) throw new IllegalArgumentException("Invalid literal at " + i);
            i += lit.length();
        }
        Object object() {
            java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (s.charAt(i) != '"') throw new IllegalArgumentException("Expected key at " + i);
                String key = string();
                ws();
                if (s.charAt(i) != ':') throw new IllegalArgumentException("Expected ':' at " + i);
                i++;
                m.put(key, value());
                ws();
                if (i >= s.length()) throw new IllegalArgumentException("Unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at " + (i - 1));
            }
        }
        Object array() {
            java.util.ArrayList<Object> l = new java.util.ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at " + (i - 1));
            }
        }
        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: throw new IllegalArgumentException("Bad escape \\" + e);
                    }
                } else sb.append(c);
            }
        }
        Object number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }
    }
}
