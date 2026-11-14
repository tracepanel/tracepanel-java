package dev.tracepanel.micronaut;

import static org.junit.jupiter.api.Assertions.*;

import dev.tracepanel.core.Config;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import io.micronaut.core.async.propagation.ReactivePropagation;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class FilterTest {
    private static final List<Map<String, Object>> DOCUMENTS = new CopyOnWriteArrayList<>();
    private static Tracepanel recorder;
    private static final Tracepanel.Tests CAPTURE = new Tracepanel.Tests() {
        public void begun(Trace trace) {}
        public Trace caller() { return null; }
        public boolean finished(Trace trace, Map<String, Object> document) {
            DOCUMENTS.add(document);
            return false;
        }
    };

    @BeforeAll
    static void recorder() {
        recorder = Tracepanel.start(Config.load(Map.of("TRACEPANEL_PROFILE_ENABLED", "0"), "micronaut-test"), List.of("shop"));
        Tracepanel.tests(CAPTURE);
    }

    @AfterAll
    static void restore() { Tracepanel.tests(null); }

    @BeforeEach
    void clear() { DOCUMENTS.clear(); Tracepanel.bind(null); }

    @Test
    void contextRestoresThePreviousTraceAcrossThreads() {
        Trace parent = recorder.begin("command", "parent", Map.of());
        Trace child = recorder.begin("command", "child", Map.of());
        Tracepanel.bind(parent);
        PropagatedContext context = PropagatedContext.getOrEmpty().plus(new TraceContext(child));
        Flux.from(ReactivePropagation.propagate(context, Mono.just(1).subscribeOn(Schedulers.boundedElastic())))
                .doOnNext(value -> assertSame(child, Tracepanel.current())).blockLast();
        assertSame(parent, Tracepanel.current());
        recorder.finish(child);
        recorder.finish(parent);
        assertNull(Tracepanel.current());
    }

    @Test
    void serverRecordsErrorsAndRestoresThreadContext() {
        TracepanelServerFilter filter = new TracepanelServerFilter(recorder, "", request -> "http://localhost",
                () -> { throw new AssertionError("No route lookup expected for a synthetic response"); });
        assertThrows(IllegalStateException.class, () -> Flux.from(filter.doFilter(HttpRequest.GET("/fail"), request -> {
            assertNotNull(Tracepanel.current());
            return Mono.error(new IllegalStateException("broken"));
        })).blockLast());
        assertNull(Tracepanel.current());
        assertEquals("error", DOCUMENTS.get(0).get("status"));
    }

    @Test
    void cancellationFinishesTheTraceExactlyOnce() {
        TracepanelServerFilter filter = new TracepanelServerFilter(recorder, "", request -> "http://localhost",
                () -> { throw new AssertionError("No route lookup expected for a synthetic response"); });
        var subscription = Flux.from(filter.doFilter(HttpRequest.GET("/cancel"), request -> Mono.never())).subscribe();
        subscription.dispose();
        assertEquals(1, DOCUMENTS.size());
        assertEquals("error", DOCUMENTS.get(0).get("status"));
        assertNull(Tracepanel.current());
    }

    @Test
    void preflightBypassesTracingButOrdinaryOptionsDoesNot() {
        TracepanelServerFilter filter = new TracepanelServerFilter(recorder, "", request -> "http://localhost",
                () -> { throw new AssertionError("No route lookup expected for a synthetic response"); });
        Flux.from(filter.doFilter(HttpRequest.OPTIONS("/cors").header("Origin", "https://shop.test")
                .header("Access-Control-Request-Method", "GET"), request -> Mono.just(HttpResponse.ok()))).blockLast();
        assertTrue(DOCUMENTS.isEmpty());
        Flux.from(filter.doFilter(HttpRequest.OPTIONS("/options"), request -> Mono.just(HttpResponse.ok()))).blockLast();
        assertEquals(1, DOCUMENTS.size());
    }

    @Test
    void clientAddsPropagationAndPreservesExplicitHeaders() {
        Trace trace = recorder.begin("http", "outgoing", Map.of());
        TracepanelClientFilter filter = new TracepanelClientFilter();
        Flux.from(filter.doFilter(HttpRequest.GET("http://localhost/items"), request -> {
            assertNotNull(request.getHeaders().get("traceparent"));
            return Mono.just(HttpResponse.ok());
        })).blockLast();
        Flux.from(filter.doFilter(HttpRequest.GET("http://localhost/explicit").header("traceparent", "own"), request -> {
            assertEquals("own", request.getHeaders().get("traceparent"));
            return Mono.just(HttpResponse.ok());
        })).blockLast();
        recorder.finish(trace);
        assertEquals(3, trace.spans().size());
    }
    @Test
    void clientRecordsSynchronousFailuresAndCancellationOnce() {
        Trace trace = recorder.begin("http", "outgoing failures", Map.of());
        TracepanelClientFilter filter = new TracepanelClientFilter();
        assertThrows(IllegalStateException.class, () -> Flux.from(filter.doFilter(HttpRequest.GET("http://localhost/fail"),
                request -> { throw new IllegalStateException("client failure"); })).blockLast());
        var subscription = Flux.from(filter.doFilter(HttpRequest.GET("http://localhost/cancel"), request -> Mono.never())).subscribe();
        subscription.dispose();
        recorder.finish(trace);
        assertEquals(2, trace.spans().stream().filter(span -> "http.client".equals(span.get("kind"))).count());
        assertEquals(2, trace.spans().stream().filter(span -> "error".equals(span.get("status"))).count());
    }

    @Test
    void handledClientErrorsKeepTheirStatusWithoutAnException() {
        TracepanelServerFilter filter = new TracepanelServerFilter(recorder, "", request -> "http://localhost",
                () -> { throw new AssertionError("No route lookup expected for a synthetic response"); });
        Flux.from(filter.doFilter(HttpRequest.GET("/invalid"), request -> Mono.just(HttpResponse.unprocessableEntity()))).blockLast();
        Map<String, Object> trace = DOCUMENTS.get(0);
        assertEquals("ok", trace.get("status"));
        assertTrue(trace.get("attrs") instanceof Map<?, ?> attrs && Integer.valueOf(422).equals(attrs.get("http.status_code")));
    }

    @Test
    void htmlInjectionPreservesCharsetAndDropsStaleEntityHeaders() {
        TracepanelServerFilter filter = new TracepanelServerFilter(recorder, "/shop/", request -> "http://localhost",
                () -> { throw new AssertionError("No route lookup expected for a synthetic response"); });
        var response = HttpResponse.ok("<html><head></head><body>Café</body></html>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                .contentType("text/html; charset=ISO-8859-1").header("ETag", "old").header("Content-Length", "55");
        io.micronaut.http.MutableHttpResponse<?> result;
        Tracepanel.tests(null); // The core disables badge injection while a test recorder is installed.
        try {
            result = Flux.from(filter.doFilter(HttpRequest.GET("/shop/page"), request -> Mono.just(response))).blockLast();
        } finally {
            Tracepanel.tests(CAPTURE);
        }
        assertNotNull(result);
        assertTrue(result.getBody().orElseThrow() instanceof byte[] bytes
                && new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Café"));
        assertTrue(result.getBody().orElseThrow() instanceof byte[] bytes
                && new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("/shop/__tracepanel__/widget.min.js"));
        assertFalse(result.getHeaders().contains("ETag"));
        assertFalse(result.getHeaders().contains("Content-Length"));
    }

    @Test
    void encodedAndStreamingResponsesStayUnchanged() {
        TracepanelServerFilter filter = new TracepanelServerFilter(recorder, "", request -> "http://localhost",
                () -> { throw new AssertionError("No route lookup expected for a synthetic response"); });
        var compressed = HttpResponse.ok("compressed").contentType("text/html").header("Content-Encoding", "gzip");
        assertSame(compressed, Flux.from(filter.doFilter(HttpRequest.GET("/gzip"), request -> Mono.just(compressed))).blockLast());
        assertEquals("compressed", compressed.body());
        var body = Mono.just("<html>stream</html>");
        var streaming = HttpResponse.ok(body).contentType("text/html");
        Flux.from(filter.doFilter(HttpRequest.GET("/stream"), request -> Mono.just(streaming))).blockLast();
        assertSame(body, streaming.body());
    }

}
