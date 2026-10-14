package dev.tracepanel.core;

import java.util.LinkedHashMap;
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

    private Widget() {}

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
        String tag = "<script src=\"" + prefix + "widget.min.js\" data-tracepanel-api=\"" + prefix + "\""
                + attr("id", trace.id()) + attr("method", method) + attr("status", String.valueOf(status))
                + attr("total", format(trace.elapsedMillis())) + attr("db", format(trace.dbMillis()))
                + attr("queries", String.valueOf(trace.queries())) + attr("issues", "pending")
                + attr("runtime", runtime) + "></script>";
        return html.substring(0, head.end()) + tag + html.substring(head.end());
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
