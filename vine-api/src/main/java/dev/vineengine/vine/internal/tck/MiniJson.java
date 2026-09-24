package dev.vineengine.vine.internal.tck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the in-process TCK harness (sub-21 Stage B): enough
 * of the format for scenario files (objects, arrays, strings, numbers, booleans),
 * with no dependency — the engine's own Gson usage is for content descriptors, and
 * the harness deliberately shares nothing with the external runner's parser so a
 * disagreement about a scenario file surfaces as a parse failure instead of
 * diverging silently.
 *
 * <p>Deliberately strict: a scenario file the external runner would reject must
 * not silently parse here, or the two harness paths could disagree about what a
 * scenario says.
 */
final class MiniJson {

    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MiniJson json = new MiniJson(text);
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.at != text.length()) {
            throw new IllegalArgumentException("trailing content at offset " + json.at);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        return (List<Object>) value;
    }

    static String string(Object value) {
        return (String) value;
    }

    private Object readValue() {
        skipWhitespace();
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> out = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            out.put(key, readValue());
            skipWhitespace();
            char c = peek();
            at++;
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at offset " + (at - 1));
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> out = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            out.add(readValue());
            skipWhitespace();
            char c = peek();
            at++;
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at offset " + (at - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + escape);
                }
                continue;
            }
            out.append(c);
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, at)) {
            throw new IllegalArgumentException("bad literal at offset " + at);
        }
        at += literal.length();
        return value;
    }

    private Object readNumber() {
        int start = at;
        while (at < text.length() && "-+.eE0123456789".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        return Double.parseDouble(text.substring(start, at));
    }

    private void skipWhitespace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw new IllegalArgumentException("unexpected end of document");
        }
        return text.charAt(at);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' at offset " + at);
        }
        at++;
    }
}
