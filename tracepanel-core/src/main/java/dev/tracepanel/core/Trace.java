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
    /** The most spans and log records a trace is sent with, as the agent stores it; the rest are counted. */
    static final int MAX_SPANS = 2000;
    static final int MAX_LOGS = 1000;

    private final String id = Ulid.next();
    private final String kind;
    private final long start = nowMicros();
    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final List<Map<String, Object>> spans = new ArrayList<>();
    private final List<Map<String, Object>> logs = new ArrayList<>();
    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final Deque<Map<String, Object>> open = new ArrayDeque<>();
    private final List<Map<String, Object>> links = new ArrayList<>();
    private final Map<String, Object> root;
    private String name;
    private String status = "ok";
    // Ended: its document is on its way to the agent, and what comes later (a hook after the response) isn't in it.
    private boolean finished;
    private long duration;
    private int queries;
    private long dbMicros;

    Trace(String kind, String name, Map<String, Object> attrs) {
        this(kind, kind, name, attrs);
    }

    /** `rootKind`: the root span's kind when it isn't the trace's (a task's is task.run). */
    Trace(String kind, String rootKind, String name, Map<String, Object> attrs) {
        this.kind = kind;
        this.name = name;
        this.attrs.putAll(attrs);
        this.root = span(rootKind, name, Map.of(), start);
    }

    public String id() {
        return id;
    }

    public synchronized void name(String name) {
        if (finished) return;
        this.name = name;
        root.put("name", name);
    }

    public synchronized void attr(String key, Object value) {
        if (!finished) attrs.put(key, value);
    }

    public synchronized Object attr(String key) {
        return attrs.get(key);
    }

    public synchronized void failed() {
        if (finished) return;
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
        if (!finished) open.push(span);
        return span;
    }

    public synchronized void end(Map<String, Object> span, boolean failed) {
        if (finished) return;
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
        return done(kind, name, attrs, startMicros, failed, Ulid.next());
    }

    /** The same, with its id given: a call out names its span in its traceparent before it is recorded. */
    public synchronized Map<String, Object> done(
            String kind, String name, Map<String, Object> attrs, long startMicros, boolean failed, String spanId) {
        return done(kind, name, attrs, startMicros, failed, spanId, null);
    }

    /**
     * The same, under `parentId` (null: the span open now): a call that completes later, on another thread, goes
     * where it was made.
     */
    public synchronized Map<String, Object> done(String kind, String name, Map<String, Object> attrs, long startMicros,
            boolean failed, String spanId, String parentId) {
        Map<String, Object> span = span(kind, name, attrs, startMicros);
        span.put("id", spanId);
        if (parentId != null) span.put("parent_id", parentId);
        span.put("duration", nowMicros() - startMicros);
        if (failed) span.put("status", "error");
        if ("sql".equals(kind)) {
            queries++;
            dbMicros += (long) span.get("duration");
        }
        return span;
    }

    public synchronized void log(String level, String logger, String message, String exception, Map<String, Object> attrs) {
        if (finished) return;
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("span_id", current());
        log.put("time", nowMicros());
        log.put("level", level);
        log.put("logger", logger);
        log.put("message", message);
        log.put("exception", exception);
        log.put("attrs", attrs);
        if (logs.size() < MAX_LOGS) logs.add(log);
        else dropped("tracepanel.dropped_logs");
    }

    /** An exception, once: the same one seen again (rethrown up the stack) isn't another error. */
    public synchronized void error(Throwable error, Stacks stacks, boolean handled) {
        if (finished) return;
        if (recorded(error)) return;
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("span_id", current());
        record.put("time", nowMicros());
        record.put("type", error.getClass().getName());
        record.put("message", String.valueOf(error.getMessage()));
        record.put("handled", handled);
        record.put("frames", frames(error, stacks));
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Throwable cause = error.getCause(); cause != null && cause != error && chain.size() < 10; cause = cause.getCause()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("relation", "cause");
            link.put("type", cause.getClass().getName());
            link.put("message", String.valueOf(cause.getMessage()));
            link.put("frames", frames(cause, stacks));
            chain.add(link);
        }
        record.put("chain", chain);
        record.put("_object", error);
        errors.add(record);
    }

    /**
     * An error's frames, or none when reading them failed: recording an error never throws in the app's place, which
     * would answer with Tracepanel's exception and lose the app's.
     */
    private static List<Map<String, Object>> frames(Throwable error, Stacks stacks) {
        try {
            return stacks.frames(error);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Whether this error, or one it wraps, is recorded already: a framework's wrapper (Spring's ServletException
     * "Request processing failed") says nothing the error it wraps didn't.
     */
    private boolean recorded(Throwable error) {
        int depth = 0;
        for (Throwable cause = error; cause != null && depth++ < 10; cause = cause.getCause()) {
            for (Map<String, Object> seen : errors) if (seen.get("_object") == cause) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    /** Marks the errors recorded so far as handled or not: an exception the app answered with a 4xx was handled. */
    public synchronized void errorsHandled(boolean handled) {
        if (finished) return;
        for (Map<String, Object> error : errors) error.put("handled", handled);
    }

    /** A link to another trace: `enqueued_by` from a task to the trace that queued it, at the span that did. */
    public synchronized void link(String kind, String traceId, String spanId) {
        if (finished) return;
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("kind", kind);
        link.put("trace_id", traceId);
        link.put("span_id", spanId);
        links.add(link);
    }

    /** The span open now (where the next one goes), or the root. */
    public synchronized String currentSpan() {
        return current();
    }

    public synchronized boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** The trace ended: its document, as the agent takes it. */
    public synchronized Map<String, Object> finish() {
        finished = true;
        duration = nowMicros() - start;
        root.put("duration", duration);
        // A span still open (its work ended without saying so) lasts until the trace's end.
        for (Map<String, Object> span : open) span.put("duration", nowMicros() - (long) span.get("start"));
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
        doc.put("links", links);
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
        // Spans are added as they start: past the limit, every later one goes, its children with it.
        if (finished) return span;
        if (spans.size() < MAX_SPANS) spans.add(span);
        else dropped("tracepanel.dropped_spans");
        return span;
    }

    private void dropped(String key) {
        attrs.merge(key, 1L, (count, one) -> ((Number) count).longValue() + 1);
    }

    private String current() {
        Map<String, Object> top = open.peek();
        return top != null ? (String) top.get("id") : root == null ? null : (String) root.get("id");
    }

    /** Now, in microseconds since the epoch, as every span's start. */
    public static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }
}
