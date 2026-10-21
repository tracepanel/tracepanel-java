package dev.tracepanel.core;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a response says about its trace, as every SDK says it: headers for the widget and for fetch() callers
 * (Tracepanel-Id, Tracepanel-Url, Server-Timing), and on an HTML page the widget itself, at the top of its head.
 */
public final class Widget {
    private static final Pattern HEAD = Pattern.compile("<head(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
    /** Read by the page across origins: the API's CORS responses expose them. */
    public static final String EXPOSED = "Tracepanel-Id, Tracepanel-Url, Server-Timing";

    /**
     * The requests that answered a page's navigation with a redirect, by id, on the way to the page the widget will see:
     * its panel lists them before that page (a form's POST, then the page it redirected to).
     */
    public static final String REDIRECTS = "tracepanel_redirect";
    /** How many redirects in a row are kept: a loop of them doesn't grow the cookie without end. */
    private static final int MAX_REDIRECTS = 5;

    private Widget() {}

    /** Whether the browser asked for a page to show (a link, a form): not fetch() or an image. */
    public static boolean navigated(String fetchMode, String accept) {
        return fetchMode != null ? fetchMode.equals("navigate") : accept != null && accept.contains("text/html");
    }

    /**
     * The Set-Cookie naming the request `id` too, for a redirect that answers a page's navigation; null otherwise.
     * `cookies` is the request's Cookie header, which names the redirects before this one.
     */
    public static String redirectCookie(String id, int status, String fetchMode, String accept, String cookies) {
        if (status < 300 || status >= 400 || !navigated(fetchMode, accept)) return null;
        List<String> ids = new ArrayList<>();
        for (String before : sentRedirects(cookies).split(",")) if (!before.isEmpty()) ids.add(before);
        ids.add(id);
        String value = String.join(",", ids.subList(Math.max(0, ids.size() - MAX_REDIRECTS), ids.size()));
        return REDIRECTS + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) + "; Max-Age=30; Path=/; SameSite=Lax";
    }

    /** The redirect cookie's ids as the request sent them, decoded; none for a value that can't be. */
    private static String sentRedirects(String cookies) {
        if (cookies == null) return "";
        for (String part : cookies.split(";")) {
            int equals = part.indexOf('=');
            if (equals < 0 || !part.substring(0, equals).strip().equals(REDIRECTS)) continue;
            try {
                return URLDecoder.decode(part.substring(equals + 1).strip(), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
        return "";
    }

    /** The headers for a response of `trace`; the console at `origin` + the prefix. */
    public static Map<String, String> headers(Trace trace, String origin, String prefix) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Tracepanel-Id", trace.id());
        headers.put("Tracepanel-Url", origin + prefix + "#/t/" + trace.id());
        headers.put("Server-Timing", serverTiming(trace));
        return headers;
    }

    /** db, app and total time, and the issues, which the agent finds after the response: pending. */
    public static String serverTiming(Trace trace) {
        double total = trace.elapsedMillis();
        double db = trace.dbMillis();
        return String.format(Locale.ROOT, "db;dur=%.1f;desc=\"%d queries\", app;dur=%.1f, total;dur=%.1f, tracepanel-issues;desc=\"pending\"",
                db, trace.queries(), Math.max(total - db, 0), total);
    }

    /**
     * The page with the widget at the top of its head, carrying its own request (the widget shows it first), or the
     * page as it was when it has no head.
     */
    public static String decorate(String html, Trace trace, String method, int status, String prefix, String runtime) {
        Matcher head = HEAD.matcher(html);
        if (!head.find()) return html;
        String tag = script(trace, method, status, prefix, runtime);
        return html.substring(0, head.end()) + tag + html.substring(head.end());
    }

    /** The widget's tag, carrying the page's own request; ASCII whatever the page's encoding. */
    public static String script(Trace trace, String method, int status, String prefix, String runtime) {
        return "<script src=\"" + prefix + "widget.min.js\" data-tracepanel-api=\"" + prefix + "\""
                + attr("id", trace.id()) + attr("method", method) + attr("status", String.valueOf(status))
                + attr("total", format(trace.elapsedMillis())) + attr("db", format(trace.dbMillis()))
                + attr("queries", String.valueOf(trace.queries())) + attr("issues", "pending")
                + attr("runtime", runtime) + "></script>";
    }

    /**
     * Whether a page that goes out in pieces gets the widget as they pass: HTML, not compressed, its size not declared
     * (the widget would change it).
     */
    public static boolean streamable(String contentType, String contentEncoding, boolean sized) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")
                && contentEncoding == null && !sized;
    }

    /**
     * The widget put into an HTML page that goes out in pieces, as they pass: right after its head opens, or its body
     * without one. Nothing is held but the end of a piece from its last `<` (a tag that may go on in the next one),
     * until the widget is in; a page streamed {@link #LOOKAHEAD} bytes without either passes as it comes.
     */
    public static final class Stream {
        private static final Pattern OPENING = Pattern.compile("<(?:head|body)(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
        public static final int LOOKAHEAD = 65_536;
        private static final byte[] NOTHING = new byte[0];

        private final byte[] tag;
        private byte[] held = NOTHING;
        private long seen;
        private boolean done;

        public Stream(String tag) {
            this.tag = tag.getBytes(StandardCharsets.US_ASCII);
        }

        /** What goes out now, of the piece and what was held. */
        public byte[] feed(byte[] piece, int offset, int length) {
            if (done) return Arrays.copyOfRange(piece, offset, offset + length);
            byte[] data = new byte[held.length + length];
            System.arraycopy(held, 0, data, 0, held.length);
            System.arraycopy(piece, offset, data, held.length, length);
            held = NOTHING;
            // ISO-8859-1 keeps one character per byte: the indexes found are the bytes'.
            String text = new String(data, StandardCharsets.ISO_8859_1);
            Matcher found = OPENING.matcher(text);
            if (found.find()) {
                done = true;
                byte[] out = new byte[data.length + tag.length];
                System.arraycopy(data, 0, out, 0, found.end());
                System.arraycopy(tag, 0, out, found.end(), tag.length);
                System.arraycopy(data, found.end(), out, found.end() + tag.length, data.length - found.end());
                return out;
            }
            seen += length;
            if (seen > LOOKAHEAD) {
                done = true;
                return data;
            }
            int cut = text.lastIndexOf('<');
            if (cut == -1 || text.indexOf('>', cut) != -1) return data;
            held = Arrays.copyOfRange(data, cut, data.length);
            return Arrays.copyOf(data, cut);
        }

        public byte[] feed(byte[] piece) {
            return feed(piece, 0, piece.length);
        }

        /** What was held, once the body ends. */
        public byte[] end() {
            byte[] rest = held;
            held = NOTHING;
            done = true;
            return rest;
        }
    }

    /**
     * An attribute of the widget's tag, escaped, its characters outside ASCII as character references: the page's
     * encoding may not be UTF-8 (a page served as text/html with no charset is written as ISO-8859-1), and the
     * runtime's "Java 21 · Spring Boot 4.1" must read the same in any.
     */
    private static String attr(String name, String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(" data-tracepanel-").append(name).append("=\"");
        value.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '<' -> out.append("&lt;");
                default -> {
                    if (c < 128) out.append((char) c);
                    else out.append("&#").append(c).append(';');
                }
            }
        });
        return out.append('"').toString();
    }

    private static String format(double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }
}
