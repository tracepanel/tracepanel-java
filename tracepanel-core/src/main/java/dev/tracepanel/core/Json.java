package dev.tracepanel.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON a trace goes to the agent as: maps, lists, strings, numbers, booleans and nulls. No library: the app's own stay its own. */
public final class Json {
    private Json() {}

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            string(text, out);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Short) {
            out.append(value);
        } else if (value instanceof Number number) {
            double d = number.doubleValue();
            out.append(Double.isFinite(d) ? number.toString() : "null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                string(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Collection<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            string(String.valueOf(value), out);
        }
    }

    private static void string(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** A JSON text read back: maps, lists, strings, longs or doubles, booleans and nulls (the agent's answers). */
    public static Object read(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.space();
        if (reader.at < text.length()) throw new IllegalArgumentException("JSON: text after the value at " + reader.at);
        return value;
    }

    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        Object value() {
            space();
            if (at >= text.length()) throw new IllegalArgumentException("JSON: a value expected at the end");
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Object word(String word, Object value) {
            if (!text.startsWith(word, at)) throw new IllegalArgumentException("JSON: unexpected text at " + at);
            at += word.length();
            return value;
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            at++;
            space();
            if (text.charAt(at) == '}') {
                at++;
                return map;
            }
            while (true) {
                space();
                String key = string();
                space();
                expect(':');
                map.put(key, value());
                space();
                if (text.charAt(at++) == '}') return map;
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++;
            space();
            if (text.charAt(at) == ']') {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                space();
                if (text.charAt(at++) == ']') return list;
            }
        }

        private void expect(char c) {
            if (text.charAt(at++) != c) throw new IllegalArgumentException("JSON: '" + c + "' expected at " + (at - 1));
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> out.append(escaped);
                }
            }
        }

        private Number number() {
            int start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            String number = text.substring(start, at);
            if (number.isEmpty()) throw new IllegalArgumentException("JSON: unexpected text at " + start);
            if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.parseDouble(number);
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                // Past a long: as written.
                return new BigDecimal(number);
            }
        }
    }
}
