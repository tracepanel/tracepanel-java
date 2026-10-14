package dev.tracepanel.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One trace while it runs: a request, a task, a job. Its spans nest as the work does (the open one is the parent of
 * the next), and it goes to the agent as the document every SDK sends, once it ends.
 */
public final class Trace {
    private static final int MAX_SPANS = 5000;

    private final String id = Ulid.next();
    private final String kind;
    private final long start = nowMicros();
    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final List<Map<String, Object>> spans = new ArrayList<>();
    private final List<Map<String, Object>> logs = new ArrayList<>();
    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final Deque<Map<String, Object>> open = new ArrayDeque<>();
    private final Map<String, Object> root;
    private String name;
    private String status = "ok";
    private long duration;
    private int queries;
    private long dbMicros;

    Trace(String kind, String name, Map<String, Object> attrs) {
        this.kind = kind;
        this.name = name;
        this.attrs.putAll(attrs);
        this.root = span(kind, name, Map.of(), start);
    }

    public String id() {
        return id;
    }

    public void name(String name) {
        this.name = name;
        root.put("name", name);
    }

    public void attr(String key, Object value) {
        attrs.put(key, value);
    }

    public Object attr(String key) {
        return attrs.get(key);
    }

    public void failed() {
        status = "error";
        root.put("status", "error");
    }

    /** Queries so far and their time, for the response's Server-Timing. */
    public synchronized int queries() {
        return queries;
    }

    public synchronized double dbMillis() {
        return dbMicros / 1000.0;
    }

    public double elapsedMillis() {
        return (nowMicros() - start) / 1000.0;
    }

    /** A span under the one open now, open until `end`. */
    public synchronized Map<String, Object> begin(String kind, String name, Map<String, Object> attrs) {
        Map<String, Object> span = span(kind, name, attrs, nowMicros());
        open.push(span);
        return span;
    }

    public synchronized void end(Map<String, Object> span, boolean failed) {
        span.put("duration", nowMicros() - (long) span.get("start"));
        if (failed) span.put("status", "error");
        if ("sql".equals(span.get("kind"))) {
            queries++;
            dbMicros += (long) span.get("duration");
        }
        open.remove(span);
    }

    /** A span that already happened: started `micros` ago, with no children. */
    public synchronized Map<String, Object> done(String kind, String name, Map<String, Object> attrs, long startMicros, boolean failed) {
        Map<String, Object> span = span(kind, name, attrs, startMicros);
        span.put("duration", nowMicros() - startMicros);
        if (failed) span.put("status", "error");
        if ("sql".equals(kind)) {
            queries++;
            dbMicros += (long) span.get("duration");
        }
        return span;
    }

    public synchronized void log(String level, String logger, String message, String exception, Map<String, Object> attrs) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("span_id", current());
        log.put("time", nowMicros());
        log.put("level", level);
        log.put("logger", logger);
        log.put("message", message);
        log.put("exception", exception);
        log.put("attrs", attrs);
        logs.add(log);
    }

    /** An exception, once: the same one seen again (rethrown up the stack) isn't another error. */
    public synchronized void error(Throwable error, Stacks stacks, boolean handled) {
        for (Map<String, Object> seen : errors) if (seen.get("_object") == error) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("span_id", current());
        record.put("time", nowMicros());
        record.put("type", error.getClass().getName());
        record.put("message", String.valueOf(error.getMessage()));
        record.put("handled", handled);
        record.put("frames", stacks.frames(error));
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Throwable cause = error.getCause(); cause != null && cause != error && chain.size() < 10; cause = cause.getCause()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("relation", "cause");
            link.put("type", cause.getClass().getName());
            link.put("message", String.valueOf(cause.getMessage()));
            link.put("frames", stacks.frames(cause));
            chain.add(link);
        }
        record.put("chain", chain);
        record.put("_object", error);
        errors.add(record);
    }

    /** Marks the errors recorded so far as handled or not: an exception the app answered with a 4xx was handled. */
    public synchronized void errorsHandled(boolean handled) {
        for (Map<String, Object> error : errors) error.put("handled", handled);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** The trace ended: its document, as the agent takes it. */
    public synchronized Map<String, Object> finish() {
        duration = nowMicros() - start;
        root.put("duration", duration);
        for (Map<String, Object> span : open) span.putIfAbsent("duration", nowMicros() - (long) span.get("start"));
        for (Map<String, Object> error : errors) error.remove("_object");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", id);
        doc.put("kind", kind);
        doc.put("name", name);
        doc.put("start", start);
        doc.put("duration", duration);
        doc.put("status", status);
        doc.put("attrs", attrs);
        doc.put("spans", spans);
        doc.put("logs", logs);
        doc.put("errors", errors);
        doc.put("links", List.of());
        doc.put("issues", List.of());
        return doc;
    }

    private Map<String, Object> span(String kind, String name, Map<String, Object> attrs, long startMicros) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("id", Ulid.next());
        span.put("parent_id", spans.isEmpty() ? null : current());
        span.put("kind", kind);
        span.put("name", name);
        span.put("start", startMicros);
        span.put("duration", 0L);
        span.put("status", "ok");
        span.put("attrs", new LinkedHashMap<>(attrs));
        if (spans.size() < MAX_SPANS) spans.add(span);
        return span;
    }

    private String current() {
        Map<String, Object> top = open.peek();
        return top != null ? (String) top.get("id") : root == null ? null : (String) root.get("id");
    }

    static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }
}
