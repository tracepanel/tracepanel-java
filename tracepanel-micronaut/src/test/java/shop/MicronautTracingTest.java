package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tracepanel.testing.FakeAgent;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Tag("integration")
@MicronautTest(environments = "dev", transactional = false)
@Property(name = "datasources.default.url", value = "jdbc:h2:mem:micronaut;DB_CLOSE_DELAY=-1")
@Property(name = "datasources.default.driver-class-name", value = "org.h2.Driver")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MicronautTracingTest implements TestPropertyProvider {
    @Inject @Client("/") HttpClient http;

    @Override
    public Map<String, String> getProperties() {
        try {
            FakeAgent.start();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return Map.of();
    }

    @Test
    void routeHasSqlClientCallLogsAndTraceHeaders() throws Exception {
        var response = http.toBlocking().exchange(HttpRequest.GET("/probe/books/7"), String.class);
        Map<String, Object> trace = FakeAgent.traceAt("/probe/books/7");
        Map<String, Object> echoed = FakeAgent.traceAt("/probe/echo");
        Map<?, ?> client = spans(trace).get(2);

        assertEquals("book 7 echo", response.body());
        assertEquals(trace.get("id"), response.header("Tracepanel-Id"));

        assertEquals("GET /probe/books/{id}", trace.get("name"));
        assertEquals("ok", trace.get("status"));
        assertEquals(List.of("http GET /probe/books/{id}", "sql SELECT 1", "http.client GET /probe/echo 200"),
                spans(trace).stream().map(MicronautTracingTest::said).toList());
        assertEquals(List.of("info loaded book 7"), ((List<?>) trace.get("logs")).stream()
                .map(value -> (Map<?, ?>) value).map(log -> log.get("level") + " " + log.get("message")).toList());

        // The call carried the trace on: the echo's trace names the client span as its parent.
        assertEquals(((Map<?, ?>) client.get("attrs")).get("w3c.span_id"),
                ((Map<?, ?>) echoed.get("attrs")).get("w3c.parent_span_id"));
    }

    @Test
    void reactiveControllerKeepsItsTraceAfterSwitchingThreads() throws Exception {
        assertEquals("reactive", http.toBlocking().retrieve("/probe/reactive/8"));
        Map<String, Object> trace = FakeAgent.traceAt("/probe/reactive/8");

        assertEquals("GET /probe/reactive/{id}", trace.get("name"));
        assertEquals(List.of("http GET /probe/reactive/{id}", "sql SELECT 1"),
                spans(trace).stream().map(MicronautTracingTest::said).toList());
    }

    @Test
    void frameworkErrorResponseKeepsTheException() throws Exception {
        assertThrows(HttpClientResponseException.class, () -> http.toBlocking().retrieve("/probe/failure"));
        Map<String, Object> trace = FakeAgent.traceAt("/probe/failure");
        Map<?, ?> error = (Map<?, ?>) ((List<?>) trace.get("errors")).get(0);
        // The app's frame nearest the throw: the controller's method, not the bean definition Micronaut compiled.
        Map<?, ?> own = ((List<?>) error.get("frames")).stream().map(value -> (Map<?, ?>) value)
                .filter(frame -> Boolean.TRUE.equals(frame.get("in_app"))).findFirst().orElseThrow();

        assertEquals("error", trace.get("status"));
        assertEquals(List.of("java.lang.IllegalStateException", "broken controller", false),
                List.of(error.get("type"), error.get("message"), error.get("handled")));
        assertEquals(List.of("shop.MicronautTracingTest$ProbeController", "failure"),
                List.of(own.get("module"), own.get("function")));
    }

    @Test
    void htmlHasTheBadgeAndTheConsoleDoesNotTraceItself() {
        String page = http.toBlocking().retrieve("/probe/page");
        assertTrue(page.contains("/__tracepanel__/widget.min.js"), page);
        String answer = http.toBlocking().retrieve("/__tracepanel__/api/services");
        assertTrue(answer.startsWith("console "));
        String posted = http.toBlocking().retrieve(HttpRequest.POST("/__tracepanel__/api/settings", "{\"theme\":\"dark\"}")
                .contentType("application/json"));
        assertTrue(posted.startsWith("console "));
        assertTrue(FakeAgent.CONSOLE_CALLS.contains("POST /api/settings {\"theme\":\"dark\"}"));
        assertTrue(FakeAgent.TRACES.stream().noneMatch(trace -> String.valueOf(trace.get("name")).contains("__tracepanel__")));
    }

    private static List<Map<?, ?>> spans(Map<String, Object> trace) {
        return ((List<?>) trace.get("spans")).stream().<Map<?, ?>>map(value -> (Map<?, ?>) value).toList();
    }

    /** A span as the test reads it: its kind and what it is (a statement, a call's path and status). */
    private static String said(Map<?, ?> span) {
        Map<?, ?> attrs = (Map<?, ?>) span.get("attrs");
        return span.get("kind") + " " + switch (String.valueOf(span.get("kind"))) {
            case "sql" -> attrs.get("db.statement");
            case "http.client" -> attrs.get("http.request.method") + " " + URI.create(String.valueOf(attrs.get("url.full")))
                    .getPath() + " " + attrs.get("http.response.status_code");
            default -> span.get("name");
        };
    }

    @Client("/")
    interface EchoClient {
        @Get("/probe/echo") String echo();
    }

    @Controller("/probe")
    @ExecuteOn(TaskExecutors.BLOCKING)
    static class ProbeController {
        private final DataSource database;
        private final EchoClient client;

        ProbeController(DataSource database, EchoClient client) { this.database = database; this.client = client; }

        @Get("/books/{id}")
        String book(long id) throws SQLException {
            query();
            org.slf4j.LoggerFactory.getLogger(ProbeController.class).info("loaded book {}", id);
            return "book " + id + " " + client.echo();
        }

        @Get("/reactive/{id}")
        Mono<String> reactive(long id) {
            return Mono.fromCallable(() -> { query(); return "reactive"; }).subscribeOn(Schedulers.boundedElastic());
        }

        @Get("/echo") String echo() { return "echo"; }
        @Get("/failure") String failure() { throw new IllegalStateException("broken controller"); }
        @Get(value = "/page", produces = "text/html") String page() { return "<html><head></head><body>Shop</body></html>"; }

        private void query() throws SQLException {
            try (var connection = database.getConnection(); var statement = connection.prepareStatement("SELECT 1");
                    var rows = statement.executeQuery()) {
                assertTrue(rows.next());
            }
        }
    }
}
