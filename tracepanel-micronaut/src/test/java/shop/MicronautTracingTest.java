package shop;

import static org.junit.jupiter.api.Assertions.*;

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
        assertNotNull(response.header("Tracepanel-Id"));
        assertEquals("book 7 echo", response.body());
        Map<String, Object> trace = FakeAgent.traceAt("/probe/books/7");
        assertEquals("GET /probe/books/{id}", trace.get("name"));
        assertTrue(trace.get("spans") instanceof List<?> spans && spans.stream().anyMatch(value ->
                value instanceof Map<?, ?> span && "sql".equals(span.get("kind"))));
        assertTrue(trace.get("spans") instanceof List<?> spans && spans.stream().anyMatch(value ->
                value instanceof Map<?, ?> span && "http.client".equals(span.get("kind"))));
        assertTrue(trace.get("logs") instanceof List<?> logs && !logs.isEmpty());
        Map<String, Object> echoed = FakeAgent.traceAt("/probe/echo");
        assertTrue(echoed.get("attrs") instanceof Map<?, ?> attrs && attrs.containsKey("w3c.parent_span_id"));
    }

    @Test
    void reactiveControllerKeepsItsTraceAfterSwitchingThreads() throws Exception {
        assertEquals("reactive", http.toBlocking().retrieve("/probe/reactive/8"));
        Map<String, Object> trace = FakeAgent.traceAt("/probe/reactive/8");
        assertEquals("GET /probe/reactive/{id}", trace.get("name"));
        assertTrue(trace.get("spans") instanceof List<?> spans && spans.size() > 1);
    }

    @Test
    void frameworkErrorResponseKeepsTheException() throws Exception {
        assertThrows(HttpClientResponseException.class, () -> http.toBlocking().retrieve("/probe/failure"));
        Map<String, Object> trace = FakeAgent.traceAt("/probe/failure");
        assertEquals("error", trace.get("status"));
        assertTrue(trace.get("errors") instanceof List<?> errors && !errors.isEmpty());
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
        assertTrue(FakeAgent.CONSOLE_CALLS.stream().anyMatch(call -> call.equals("POST /api/settings {\"theme\":\"dark\"}")));
        assertTrue(FakeAgent.TRACES.stream().noneMatch(trace -> String.valueOf(trace.get("name")).contains("__tracepanel__")));
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
