package dev.tracepanel.core;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a request carried, as attributes of its trace: headers, query, body, cookies, the session; the Python SDK's
 * names and masks, so the console's Request tab reads a Java request as it reads a Python one.
 *
 * <p>They stay on this machine (the agent keeps them out of what it sends anywhere), and the values under a sensitive
 * name are masked before they are recorded. A body is only what the app read itself, up to MAX_BODY characters:
 * Tracepanel never reads a stream for the app, nor holds an upload.
 */
public final class RequestDetails {
    /** How much of a body is kept, in characters. */
    public static final int MAX_BODY = 16_384;
    // A form as people read it: encoded, but its spaces, masks and file notes as they are.
    private static final String READABLE = "*<>,. /:@";
    // A query as Python's urlencode writes it, a space as +.
    private static final String QUERY_SAFE = "*/:";
    // A response's cookies are a session's keys, whatever their name.
    private static final Set<String> ALWAYS_MASKED = Set.of("set-cookie");
    // Spring Security's context in a session: the authentication with its details, the session's id among them. The
    // user it names is user.* already.
    private static final Set<String> SESSION_MASKED = Set.of("SPRING_SECURITY_CONTEXT");
    private static final int MAX_REPR = 160;
    private static final Pattern UNKEYED = Pattern.compile("[^a-z0-9_]");
    private static final Pattern BOUNDARY = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAM = Pattern.compile("(?:^|;)\\s*(name|filename)=(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^;\\s]*))",
            Pattern.CASE_INSENSITIVE);

    private RequestDetails() {}

    /** A name as one segment of an attribute's key (content-type: content_type), as the trace format has keys. */
    public static String key(String name) {
        String key = UNKEYED.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("_");
        return key.isEmpty() ? "_" : key;
    }

    /** Whether a secret goes under the name: x-api-key, csrftoken, sessionid, password. */
    public static boolean sensitive(String name) {
        return Secrets.secret(name.replace('-', '_'));
    }

    /**
     * http.request.header.&lt;name&gt;, the name as a key: several values joined; Cookie is read as cookies instead.
     * Each entry is one value of a header, as the request had them.
     */
    public static Map<String, Object> requestHeaders(List<Map.Entry<String, String>> headers) {
        List<Map.Entry<String, String>> sent = new ArrayList<>();
        boolean length = false;
        for (Map.Entry<String, String> header : headers) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (name.equals("cookie") || header.getValue() == null || header.getValue().isEmpty()) continue;
            if (name.equals("content-length")) length = true;
            sent.add(header);
        }
        // A request without a body has no type: one a server filled in is none the client sent.
        if (!length) sent.removeIf(header -> header.getKey().equalsIgnoreCase("content-type"));
        return headers("http.request.header.", sent);
    }

    /** http.response.header.&lt;name&gt;: several values joined, Set-Cookie masked. */
    public static Map<String, Object> responseHeaders(List<Map.Entry<String, String>> headers) {
        return headers("http.response.header.", headers);
    }

    private static Map<String, Object> headers(String prefix, List<Map.Entry<String, String>> headers) {
        Map<String, Object> found = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers) {
            String lowered = header.getKey().toLowerCase(Locale.ROOT);
            String attr = prefix + key(lowered);
            if (ALWAYS_MASKED.contains(lowered) || sensitive(lowered)) found.put(attr, Secrets.MASK);
            else found.merge(attr, header.getValue(), (before, value) -> before + ", " + value);
        }
        return found;
    }

    /** url.query: the query string, the values of sensitive parameters masked. */
    public static Map<String, Object> query(String raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        List<String[]> pairs = new ArrayList<>();
        for (String[] pair : pairs(raw)) pairs.add(new String[] {pair[0], sensitive(pair[0]) ? Secrets.MASK : pair[1]});
        return Map.of("url.query", encode(pairs, QUERY_SAFE, true));
    }

    /** http.request.cookie.&lt;name&gt;, from the Cookie header; a session id or a CSRF token masked. */
    public static Map<String, Object> cookies(String header) {
        Map<String, Object> found = new LinkedHashMap<>();
        if (header == null) return found;
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            String name = equals < 0 ? "" : part.substring(0, equals).strip();
            if (name.isEmpty()) continue;
            found.put("http.request.cookie." + key(name), sensitive(name) ? Secrets.MASK : part.substring(equals + 1).strip());
        }
        return found;
    }

    /** session.&lt;key&gt;: each value's short text, a sensitive one masked. */
    public static Map<String, Object> session(Map<String, Object> items) {
        Map<String, Object> found = new LinkedHashMap<>();
        items.forEach((name, value) -> found.put("session." + key(name),
                sensitive(name) || SESSION_MASKED.contains(name) ? Secrets.MASK : repr(value)));
        return found;
    }

    /** A value as a short text: a string quoted, anything cut past MAX_REPR characters; never an exception. */
    static String repr(Object value) {
        String text;
        try {
            text = value instanceof CharSequence chars ? "'" + chars + "'" : String.valueOf(value);
        } catch (RuntimeException e) {
            return "<unrepresentable " + value.getClass().getSimpleName() + ">";
        }
        if (text.length() <= MAX_REPR) return text;
        int head = (MAX_REPR - 3) / 2;
        return text.substring(0, head) + "..." + text.substring(text.length() - (MAX_REPR - 3 - head));
    }

    /**
     * http.request.body from what the app read: JSON and forms with secrets masked, text as it is, binary not.
     * `complete` is false when only the start of the body is at hand: a form or JSON can't be read from a part, and a
     * multipart upload past it is not kept.
     */
    public static Map<String, Object> body(String contentType, byte[] data, boolean complete) {
        if (data == null || data.length == 0) return Map.of();
        String type = contentType == null ? "" : contentType;
        String kind = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String text;
        if (kind.equals("application/json") || kind.endsWith("+json")) {
            text = complete ? json(data) : null;
        } else if (kind.equals("application/x-www-form-urlencoded")) {
            text = complete ? formText(pairs(new String(data, StandardCharsets.UTF_8)), List.of()) : null;
        } else if (kind.equals("multipart/form-data")) {
            text = complete ? multipart(type, data) : null;
        } else if (kind.startsWith("text/") || kind.equals("application/xml") || kind.equals("application/graphql")) {
            text = new String(data, StandardCharsets.UTF_8);
        } else {
            return Map.of();
        }
        return text == null ? Map.of() : cut(text);
    }

    /**
     * The start of a body as the app reads it, kept as it passes: never more than MAX_BODY + 1 bytes, never the rest
     * of an upload. A framework's integration feeds it what its stream hands the app.
     */
    public static final class Kept {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long length;
        private long size;

        /** `length`: the request's Content-Length, or -1 without one (chunked). */
        public Kept(long length) {
            this.length = length;
        }

        public synchronized void add(byte[] data, int offset, int count) {
            if (count <= 0) return;
            if (size <= MAX_BODY) bytes.write(data, offset, (int) Math.min(count, MAX_BODY + 1 - size));
            size += count;
        }

        public synchronized void add(int value) {
            if (value < 0) return;
            if (size <= MAX_BODY) bytes.write(value);
            size++;
        }

        /** http.request.body as far as the app read it; one without a length is whole when it is short enough. */
        public synchronized Map<String, Object> attrs(String contentType) {
            boolean whole = size <= MAX_BODY && (length <= 0 || size >= length);
            return body(contentType, bytes.toByteArray(), whole);
        }
    }

    /** A form's file: its field, its name and its size. */
    public record Upload(String field, String name, long size) {}

    /** http.request.body from a form a framework already parsed: its fields, then its files by name and size. */
    public static Map<String, Object> form(List<Map.Entry<String, String>> fields, List<Upload> files) {
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, String> field : fields) pairs.add(new String[] {field.getKey(), field.getValue()});
        String text = formText(pairs, files);
        return text.isEmpty() ? Map.of() : cut(text);
    }

    private static String formText(List<String[]> fields, List<Upload> files) {
        List<String[]> pairs = new ArrayList<>();
        for (String[] field : fields) pairs.add(new String[] {field[0], sensitive(field[0]) ? Secrets.MASK : field[1]});
        for (Upload file : files) pairs.add(new String[] {file.field(), "<file " + file.name() + ", " + file.size() + " bytes>"});
        return encode(pairs, READABLE, false);
    }

    private static Map<String, Object> cut(String text) {
        if (text.length() <= MAX_BODY) return Map.of("http.request.body", text);
        Map<String, Object> found = new LinkedHashMap<>();
        found.put("http.request.body", text.substring(0, MAX_BODY));
        found.put("http.request.body.truncated", true);
        return found;
    }

    /** JSON with the values under sensitive keys masked, at any depth; text that isn't JSON as it is. */
    private static String json(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        try {
            return Json.write(masked(Json.read(text)));
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static Object masked(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), sensitive(String.valueOf(key)) ? Secrets.MASK : masked(item)));
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(RequestDetails::masked).toList();
        return value;
    }

    /** A multipart form's fields and files, read whole; null when it can't be read as one. */
    private static String multipart(String contentType, byte[] data) {
        Matcher found = BOUNDARY.matcher(contentType);
        if (!found.find()) return null;
        byte[] boundary = ("--" + (found.group(1) != null ? found.group(1) : found.group(2))).getBytes(StandardCharsets.ISO_8859_1);
        List<String[]> fields = new ArrayList<>();
        List<Upload> files = new ArrayList<>();
        byte[] delimiter = new byte[boundary.length + 2];
        delimiter[0] = '\r';
        delimiter[1] = '\n';
        System.arraycopy(boundary, 0, delimiter, 2, boundary.length);
        byte[] blank = {'\r', '\n', '\r', '\n'};
        int at = indexOf(data, boundary, 0);
        if (at < 0) return null;
        while (true) {
            int start = at + boundary.length;
            // The closing delimiter: --boundary--.
            if (start + 1 < data.length && data[start] == '-' && data[start + 1] == '-') break;
            int headersEnd = indexOf(data, blank, start);
            if (headersEnd < 0) return null;
            int next = indexOf(data, delimiter, headersEnd);
            if (next < 0) return null;
            String headers = new String(data, start, headersEnd - start, StandardCharsets.UTF_8);
            String name = null;
            String filename = null;
            for (String line : headers.split("\r\n")) {
                if (!line.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) continue;
                Matcher param = PARAM.matcher(line.substring(line.indexOf(':') + 1));
                while (param.find()) {
                    String value = param.group(2) != null ? param.group(2).replaceAll("\\\\(.)", "$1") : param.group(3);
                    if (param.group(1).equalsIgnoreCase("name")) name = value;
                    else filename = value;
                }
            }
            int contentStart = headersEnd + 4;
            if (name != null) {
                if (filename != null) files.add(new Upload(name, filename, next - contentStart));
                else fields.add(new String[] {name, new String(data, contentStart, next - contentStart, StandardCharsets.UTF_8)});
            }
            at = next + 2;
        }
        return formText(fields, files);
    }

    private static int indexOf(byte[] data, byte[] part, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= data.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) if (data[i + j] != part[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** name=value pairs of a query or a form, decoded (+ as a space), blank values kept. */
    static List<String[]> pairs(String raw) {
        List<String[]> pairs = new ArrayList<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int equals = part.indexOf('=');
            String name = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            pairs.add(new String[] {decode(name), decode(value)});
        }
        return pairs;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    /** Pairs percent-encoded as Python's urlencode writes them: `safe` left as it is, a space as + when `plus`. */
    private static String encode(List<String[]> pairs, String safe, boolean plus) {
        StringBuilder out = new StringBuilder();
        for (String[] pair : pairs) {
            if (out.length() > 0) out.append('&');
            quote(pair[0], safe, plus, out);
            out.append('=');
            quote(pair[1], safe, plus, out);
        }
        return out.toString();
    }

    private static void quote(String text, String safe, boolean plus, StringBuilder out) {
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "_.-~".indexOf(c) >= 0
                    || (c < 0x80 && safe.indexOf(c) >= 0)) {
                out.append((char) c);
            } else if (c == ' ' && plus) {
                out.append('+');
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
    }
}
