package shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** A Spring MVC app with the dependency alone: what the agent gets, and what the page and a fetch() caller see. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringTracingTest {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate db;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (headers.length > 0) request.headers(headers);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void books() {
        db.execute("CREATE TABLE IF NOT EXISTS author (id INT PRIMARY KEY, name VARCHAR(50))");
        db.execute("CREATE TABLE IF NOT EXISTS book (id INT PRIMARY KEY, title VARCHAR(50), author_id INT)");
        db.execute("MERGE INTO author KEY (id) VALUES (1, 'Ann'), (2, 'Bob')");
        db.execute("MERGE INTO book KEY (id) VALUES (1, 'One', 1), (2, 'Two', 2), (3, 'Three', 1), (4, 'Four', 2)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_request_is_a_trace_named_by_its_route_with_its_handler_queries_and_logs() throws Exception {
        books();
        HttpResponse<String> response = get("/shelves/fiction/books");
        Map<String, Object> trace = FakeAgent.traceAt("/shelves/fiction/books");

        assertThat(trace).containsEntry("name", "GET /shelves/{shelf}/books");
        Map<String, Object> attrs = (Map<String, Object>) trace.get("attrs");
        List<Map<String, Object>> spans = (List<Map<String, Object>>) trace.get("spans");
        List<Map<String, Object>> sql = spans.stream().filter(span -> "sql".equals(span.get("kind"))).toList();
        Map<String, Object> lookup = (Map<String, Object>) sql.get(1).get("attrs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(attrs).containsEntry("http.route", "/shelves/{shelf}/books")
                .containsEntry("http.status_code", 200)
                .containsEntry("code.view", "shop.BookController.books")
                .containsEntry("telemetry.sdk.language", "java")
                .containsEntry("service.role", "web");
        assertThat(String.valueOf(attrs.get("tracepanel.framework"))).startsWith("Spring Boot 4.");

        assertThat(spans.stream().map(span -> span.get("kind")).toList()).startsWith("http", "view");
        assertThat(sql).hasSize(5);
        assertThat(sql.get(1).get("name")).isEqualTo("SELECT author");
        assertThat(lookup).containsEntry("db.fingerprint", "SELECT name FROM author WHERE id = ?")
                .containsEntry("db.system", "h2")
                .containsEntry("db.params", List.of("1"));
        assertThat(String.valueOf(lookup.get("code.origin"))).matches("src/test/java/shop/BookController\\.java:\\d+ in books");

        List<Map<String, Object>> logs = (List<Map<String, Object>>) trace.get("logs");
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log).containsEntry("level", "info").containsEntry("message", "listing shelf fiction")
                    .containsEntry("logger", "shop.BookController");
            assertThat(((Map<String, Object>) log.get("attrs"))).containsEntry("code.filepath", "src/test/java/shop/BookController.java");
        });
    }

    @Test
    void a_response_says_its_trace_and_timing_and_a_page_gets_the_widget() throws Exception {
        books();
        HttpResponse<String> json = get("/shelves/x/books", "Origin", "http://localhost:5173");
        HttpResponse<String> page = get("/page");

        assertThat(json.headers().firstValue("Tracepanel-Id")).isPresent();
        assertThat(json.headers().firstValue("Server-Timing").orElse("")).contains("desc=\"5 queries\"", "tracepanel-issues;desc=\"pending\"");
        assertThat(json.headers().firstValue("Access-Control-Expose-Headers").orElse("")).contains("Tracepanel-Id", "Server-Timing");

        assertThat(page.body()).startsWith("<!doctype html><html><head><script src=\"/__tracepanel__/widget.min.js\"")
                .contains("data-tracepanel-queries=\"1\"", "data-tracepanel-runtime=\"Java ", "<title>Books</title>", "4 books");
        assertThat(page.headers().firstValue("Content-Length")).hasValue(String.valueOf(page.body().getBytes().length));
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_exception_fails_the_request_with_its_stack_the_app_s_frame_with_its_source() throws Exception {
        HttpResponse<String> response = get("/boom");
        Map<String, Object> trace = FakeAgent.trace("GET /boom");
        Map<String, Object> error = ((List<Map<String, Object>>) trace.get("errors")).get(0);
        List<Map<String, Object>> frames = (List<Map<String, Object>>) error.get("frames");
        Map<String, Object> app = frames.stream().filter(frame -> Boolean.TRUE.equals(frame.get("in_app"))).reduce((a, b) -> b).orElseThrow();

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(trace).containsEntry("status", "error");
        assertThat(error).containsEntry("type", "java.lang.IllegalStateException")
                .containsEntry("message", "the shelf is on fire")
                .containsEntry("handled", false);
        assertThat(app).containsEntry("filename", "src/test/java/shop/BookController.java").containsEntry("function", "boom");
        assertThat((List<String>) app.get("context")).anyMatch(line -> line.contains("the shelf is on fire"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_caller_s_traceparent_links_the_request_to_its_trace() throws Exception {
        books();
        get("/shelves/linked/books", "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertThat((Map<String, Object>) FakeAgent.traceAt("/shelves/linked/books").get("attrs"))
                .containsEntry("w3c.trace_id", "4bf92f3577b34da6a3ce929d0e0e4736")
                .containsEntry("w3c.parent_span_id", "00f067aa0ba902b7");
    }

    @Test
    void the_console_answers_on_the_app_s_origin_from_the_agent() throws Exception {
        HttpResponse<String> answer = get("/__tracepanel__/api/counts?q=since:1h");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("console /api/counts?q=since:1h");
        assertThat(FakeAgent.CONSOLE_CALLS).contains("GET /api/counts?q=since:1h");
    }
}
