package dev.tracepanel.junit;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.W3c;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A test run as a trace: what TracepanelExtension records, and what a test reads of it, as a parameter of the test
 * method or from TracepanelExtension.testTrace().
 *
 *     assertThat(trace.queries()).isLessThanOrEqualTo(5);
 *     assertThat(trace.issues()).isEmpty();
 *
 * Its queries are its own and those of the requests it made of the app (MockMvc, TestRestTemplate, WebTestClient,
 * Ktor's testApplication client): each request is a call of the test's trace, and the app's trace of it is linked to
 * the test by the W3C trace id the request carries.
 */
public final class TestTrace {
    // How long to wait for the traces of the requests a test made to end: one whose response never ended doesn't.
    private static final long SETTLE_MS = 2000;

    private final String name;
    private final Map<String, Object> attrs;
    private final AgentConsole console;
    private volatile Trace trace;
    private volatile String w3cTraceId;
    // The test's own trace once it ended, as it went to the agent.
    private volatile Map<String, Object> document;
    // The app's traces of the requests the test made: ended, and still running.
    private final List<Map<String, Object>> served = new CopyOnWriteArrayList<>();
    private final Set<Trace> open = ConcurrentHashMap.newKeySet();

    TestTrace(String name, Map<String, Object> attrs, AgentConsole console) {
        this.name = name;
        this.attrs = attrs;
        this.console = console;
    }

    /** The test begins: its trace is this thread's, and the requests it makes are linked to it. */
    void start(dev.tracepanel.core.Tracepanel tracepanel) {
        trace = tracepanel.begin("test", "function", name, attrs);
        w3cTraceId = W3c.traceId(trace);
        Session.INSTANCE.started(this);
    }

    /** The test ended, failed with `failure` (an error of its trace), its requests' traces once they ended. */
    void stop(dev.tracepanel.core.Tracepanel tracepanel, Throwable failure) {
        Trace own = trace;
        if (own == null) return;
        try {
            if (failure != null) {
                own.error(failure, tracepanel.stacks(), false);
                own.failed();
            }
            tracepanel.finish(own);
            settle();
        } finally {
            Session.INSTANCE.stopped(this);
        }
    }

    Trace trace() {
        return trace;
    }

    String w3cTraceId() {
        return w3cTraceId;
    }

    AgentConsole console() {
        return console;
    }

    /** A trace of this test's ended: its own, or the app's of a request it made. */
    void finished(Trace ended, Map<String, Object> sent) {
        if (ended == trace) {
            document = sent;
        } else {
            served.add(sent);
            open.remove(ended);
        }
    }

    /** The app's trace of a request the test made began. */
    void begun(Trace request) {
        if (request != trace) open.add(request);
    }

    /** Waits for the traces of the requests the test made to end. */
    void settle() {
        long deadline = System.nanoTime() + SETTLE_MS * 1_000_000;
        while (!open.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The test's trace's id, or null before it began. */
    public String id() {
        Trace own = trace;
        return own == null ? null : own.id();
    }

    /** The test's name, as its trace is named: the class, then the test ("shop.FeedTest > feed()"). */
    public String name() {
        return name;
    }

    /** The test's trace in the agent's console, or null without an agent. */
    public String url() {
        String id = id();
        return console == null || id == null ? null : AgentConsole.traceUrl(console.url, id);
    }

    /** The queries the test made so far, its requests' included, in the order they ran in each trace. */
    public List<Query> sql() {
        return sqlSpans().stream().map(Query::of).toList();
    }

    /** How many queries the test made so far, its requests' included. */
    public int queries() {
        return sqlSpans().size();
    }

    /**
     * What the agent found so far: in the requests the test made; its own trace's once the test has ended. Empty
     * without an agent.
     */
    public List<Finding> issues() {
        if (console == null) return List.of();
        settle();
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> own = document;
        if (own != null) traces.add(own);
        traces.addAll(served);
        return console.findings(traces);
    }

    /** The test's trace and those of its requests, as sent: what the agent's findings are read from. */
    List<Map<String, Object>> traces() {
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<String, Object> own = document;
        if (own != null) traces.add(own);
        traces.addAll(served);
        return traces;
    }

    /** The SQL spans so far: the test's own, then its requests' (ended, then still running). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sqlSpans() {
        // The running ones first: one that ends meanwhile is among the ended after, and counted once, by its spans' ids.
        List<Trace> running = new ArrayList<>(open);
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> own = document;
        Trace test = trace;
        if (own != null) spans.addAll((List<Map<String, Object>>) own.get("spans"));
        else if (test != null) spans.addAll(test.spans());
        for (Map<String, Object> request : served) spans.addAll((List<Map<String, Object>>) request.get("spans"));
        for (Trace request : running) spans.addAll(request.spans());
        Map<Object, Map<String, Object>> queries = new java.util.LinkedHashMap<>();
        for (Map<String, Object> span : spans) if ("sql".equals(span.get("kind"))) queries.putIfAbsent(span.get("id"), span);
        return new ArrayList<>(queries.values());
    }
}
