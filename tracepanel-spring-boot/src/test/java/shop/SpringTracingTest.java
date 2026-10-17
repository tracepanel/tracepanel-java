package shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tracepanel.testing.FakeAgent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
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
        assertThat(attrs).containsEntry("tracepanel.framework", "Spring Boot " + SpringBootVersion.getVersion());

        assertThat(spans.stream().map(span -> span.get("kind")).toList()).startsWith("http", "view");
        assertThat(sql).hasSize(5);
        assertThat(sql.get(1).get("name")).isEqualTo("SELECT author");
        assertThat(lookup).containsEntry("db.fingerprint", "SELECT name FROM author WHERE id = ?")
                .containsEntry("db.system", "h2")
                .containsEntry("db.params", List.of(1));
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
    void the_console_explains_a_recorded_query_on_the_app_s_database() throws Exception {
        books();
        get("/shelves/fiction/books");
        Map<String, Object> trace = FakeAgent.traceAt("/shelves/fiction/books");
        Map<String, Object> lookup = ((List<Map<String, Object>>) trace.get("spans")).stream()
                .filter(span -> "SELECT author".equals(span.get("name"))).findFirst().orElseThrow();

        HttpResponse<String> plan = get("/__tracepanel__/api/explain?trace=" + trace.get("id") + "&span=" + lookup.get("id"));
        HttpResponse<String> missing = get("/__tracepanel__/api/explain?trace=" + trace.get("id") + "&span=nope");

        assertThat(plan.statusCode()).isEqualTo(200);
        assertThat(plan.body()).contains("\"vendor\":\"h2\"", "\"alias\":\"default\"", "\"analyzed\":false")
                .containsIgnoringCase("author");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(get("/__tracepanel__/api/capabilities").body()).isEqualTo("{\"explain\":true,\"users\":false}");
    }

    @Test
    void a_failed_request_s_answer_from_the_error_page_has_its_trace_s_headers() throws Exception {
        HttpResponse<String> response = get("/boom");
        String id = response.headers().firstValue("Tracepanel-Id").orElseThrow();

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Tracepanel-Url")).hasValueSatisfying(url -> assertThat(url).endsWith("/t/" + id));
        assertThat(response.headers().firstValue("Server-Timing")).isPresent();
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_async_handler_that_fails_keeps_its_route_not_the_error_page_s() throws Exception {
        HttpResponse<String> response = get("/later/boom/7");
        Map<String, Object> trace = FakeAgent.traceAt("/later/boom/7");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(trace).containsEntry("name", "GET /later/boom/{shelf}").containsEntry("status", "error");
        // Spring's ServletException around it is no error of its own.
        assertThat((List<Map<String, Object>>) trace.get("errors")).singleElement()
                .satisfies(error -> assertThat(error).containsEntry("type", "java.lang.IllegalStateException"));
        assertThat(response.headers().firstValue("Tracepanel-Url")).hasValueSatisfying(url -> assertThat(url).endsWith("/t/" + trace.get("id")));
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

    @ParameterizedTest
    @ValueSource(strings = {"rest-client", "rest-template", "web-client", "jdk", "jdk-async", "rest-client-jdk"})
    @SuppressWarnings("unchecked")
    void a_call_to_an_http_api_is_a_span_and_the_app_it_reaches_links_its_trace_to_it(String client) throws Exception {
        books();
        assertThat(get("/relay/" + client).statusCode()).isEqualTo(200);
        Map<String, Object> caller = FakeAgent.traceAt("/relay/" + client);
        Map<String, Object> called = FakeAgent.traceAt("/shelves/" + client + "/books");

        List<Map<String, Object>> spans = (List<Map<String, Object>>) caller.get("spans");
        List<Map<String, Object>> calls = spans.stream().filter(span -> "http.client".equals(span.get("kind"))).toList();
        assertThat(calls).hasSize(1);
        Map<String, Object> call = calls.get(0);
        Map<String, Object> attrs = (Map<String, Object>) call.get("attrs");
        Map<String, Object> calledAttrs = (Map<String, Object>) called.get("attrs");

        assertThat(call).containsEntry("name", "GET 127.0.0.1:" + port + "/shelves/" + client + "/books").containsEntry("status", "ok");
        assertThat(attrs).containsEntry("http.request.method", "GET")
                .containsEntry("http.response.status_code", 200)
                .containsEntry("server.address", "127.0.0.1")
                .containsEntry("server.port", port);
        assertThat(String.valueOf(attrs.get("code.origin"))).matches("src/test/java/shop/RelayController\\.java:\\d+ in (relay|send)");

        assertThat(calledAttrs).containsEntry("w3c.parent_span_id", attrs.get("w3c.span_id"))
                .containsEntry("w3c.trace_id", ((Map<String, Object>) caller.get("attrs")).get("w3c.trace_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void work_a_proxied_bean_hands_to_the_executor_is_the_request_s_not_a_task() throws Exception {
        books();
        assertThat(get("/quote").statusCode()).isEqualTo(200);
        Map<String, Object> request = FakeAgent.traceAt("/quote");

        List<Map<String, Object>> spans = (List<Map<String, Object>>) request.get("spans");

        assertThat(spans.stream().map(span -> span.get("kind")).toList()).contains("http.client").doesNotContain("task.enqueue");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_signed_in_user_is_named_from_spring_security_or_by_the_app() throws Exception {
        assertThat(get("/account/signed-in").body()).isEqualTo("ann");
        assertThat(get("/account/named").body()).isEqualTo("bob");
        Map<String, Object> signedIn = (Map<String, Object>) FakeAgent.traceAt("/account/signed-in").get("attrs");
        Map<String, Object> named = (Map<String, Object>) FakeAgent.traceAt("/account/named").get("attrs");

        assertThat(signedIn).containsEntry("user.id", "ann").containsEntry("user.username", "ann");
        assertThat(named).containsEntry("user.id", "7").containsEntry("user.username", "bob");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_signed_in_user_of_a_request_answered_later_is_named() throws Exception {
        assertThat(get("/account/later").body()).isEqualTo("later");
        Map<String, Object> attrs = (Map<String, Object>) FakeAgent.traceAt("/account/later").get("attrs");

        assertThat(attrs).containsEntry("user.id", "ann");
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_async_method_is_a_task_linked_to_the_request_that_queued_it() throws Exception {
        get("/queue");
        Map<String, Object> request = FakeAgent.traceAt("/queue");
        Map<String, Object> task = FakeAgent.trace("Jobs.sendDigest");

        List<Map<String, Object>> requestSpans = (List<Map<String, Object>>) request.get("spans");
        Map<String, Object> queued = requestSpans.stream().filter(span -> "task.enqueue".equals(span.get("kind"))).findFirst().orElseThrow();
        List<Map<String, Object>> taskSpans = (List<Map<String, Object>>) task.get("spans");

        assertThat(queued).containsEntry("name", "Jobs.sendDigest");
        assertThat(String.valueOf(((Map<String, Object>) queued.get("attrs")).get("code.origin"))).contains("Jobs.java");
        assertThat(task).containsEntry("kind", "task");
        assertThat(taskSpans.get(0)).containsEntry("kind", "task.run");
        assertThat(taskSpans.stream().map(span -> span.get("kind")).toList()).contains("sql");
        assertThat((Map<String, Object>) task.get("attrs")).containsEntry("task.name", "Jobs.sendDigest")
                .containsEntry("task.trigger", "request")
                .containsEntry("service.role", "web");
        assertThat((List<Map<String, Object>>) task.get("links")).singleElement().satisfies(link -> assertThat(link)
                .containsEntry("kind", "enqueued_by")
                .containsEntry("trace_id", request.get("id"))
                .containsEntry("span_id", queued.get("id")));
        assertThat((List<Map<String, Object>>) task.get("logs")).singleElement()
                .satisfies(log -> assertThat(log).containsEntry("message", "digest for ann@example.com"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_scheduled_method_and_a_runner_are_commands() throws Exception {
        Map<String, Object> scheduled = FakeAgent.trace("Jobs.nightly");
        Map<String, Object> runner = FakeAgent.trace("Jobs.run");

        assertThat(scheduled).containsEntry("kind", "command");
        assertThat((Map<String, Object>) scheduled.get("attrs")).containsEntry("tracepanel.framework", "Spring Boot " + SpringBootVersion.getVersion());
        assertThat((Map<String, Object>) scheduled.get("attrs")).containsEntry("command.schedule", "every 1 hours")
                .containsEntry("command.trigger", "schedule")
                .containsEntry("service.role", "command");
        assertThat((Map<String, Object>) runner.get("attrs")).containsEntry("command.trigger", "startup");
        assertThat((List<Map<String, Object>>) runner.get("spans")).anySatisfy(span -> assertThat(span).containsEntry("kind", "sql"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_async_request_ends_with_its_answer() throws Exception {
        HttpResponse<String> response = get("/later");
        Map<String, Object> trace = FakeAgent.traceAt("/later");

        assertThat(response.body()).isEqualTo("later 42");
        assertThat(response.headers().firstValue("Tracepanel-Id")).hasValue((String) trace.get("id"));
        assertThat(trace).containsEntry("name", "GET /later");
        assertThat(((Number) trace.get("duration")).longValue()).isGreaterThanOrEqualTo(50_000L);
        assertThat((Map<String, Object>) trace.get("attrs")).containsEntry("http.status_code", 200);
        assertThat((List<Map<String, Object>>) trace.get("spans"))
                .anySatisfy(span -> assertThat(span).containsEntry("kind", "sql").containsEntry("name", "SELECT"));
        assertThat(FakeAgent.TRACES.stream().filter(t -> "/later".equals(((Map<String, Object>) t.get("attrs")).get("http.path")))).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_suspend_function_keeps_its_trace_on_the_threads_it_resumes_on() throws Exception {
        assumeTrue(SpringBootVersion.getVersion().startsWith("4."), "the Kotlin app runs on Spring Boot 4's suite");
        HttpResponse<String> response = get("/coroutine");
        Map<String, Object> trace = FakeAgent.traceAt("/coroutine");
        List<Map<String, Object>> sql = ((List<Map<String, Object>>) trace.get("spans")).stream()
                .filter(span -> "sql".equals(span.get("kind"))).toList();

        assertThat(response.body()).isEqualTo("sum 3");
        assertThat(trace).containsEntry("name", "GET /coroutine");
        assertThat(sql).extracting(span -> ((Map<String, Object>) span.get("attrs")).get("db.statement"))
                .containsExactly("SELECT 1 AS io", "SELECT 2 AS resumed");
        List<Map<String, Object>> views = ((List<Map<String, Object>>) trace.get("spans")).stream()
                .filter(span -> "view".equals(span.get("kind"))).toList();
        assertThat(views).singleElement().satisfies(view -> {
            assertThat(((Number) view.get("duration")).longValue()).isGreaterThan(0);
            assertThat(sql).allSatisfy(query -> assertThat(query).containsEntry("parent_id", view.get("id")));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_exception_that_is_a_4xx_answer_is_not_an_error() throws Exception {
        assertThat(get("/missing").statusCode()).isEqualTo(404);
        Map<String, Object> trace = FakeAgent.traceAt("/missing");

        assertThat((List<Object>) trace.get("errors")).isEmpty();
        assertThat(trace).containsEntry("status", "ok");
    }

    @Test
    void an_async_page_gets_the_widget() throws Exception {
        HttpResponse<String> response = get("/later/page");
        Map<String, Object> trace = FakeAgent.traceAt("/later/page");

        assertThat(response.body()).contains("later").contains("widget.min.js").contains((String) trace.get("id"));
        assertThat(response.headers().firstValue("Content-Length")).hasValue(String.valueOf(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
    }
}
