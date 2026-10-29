package shop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tracepanel.core.Secrets;
import dev.tracepanel.testing.FakeAgent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** What a request carried, for the console's Request tab, and where its time went, for its Profile tab. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestTracingTest {
    private static final String M = Secrets.MASK;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
    }

    private HttpResponse<String> send(String method, String path, String type, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (type != null) request.header("Content-Type", type);
        if (headers.length > 0) request.headers(headers);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(String path) throws InterruptedException {
        return (Map<String, Object>) FakeAgent.traceAt(path).get("attrs");
    }

    /** The trace of the request that sent X-Test: `name`, once the SDK sent it. */
    private static Map<String, Object> traceNamed(String name) throws InterruptedException {
        for (int waited = 0; waited < 5000; waited += 20) {
            for (Map<String, Object> trace : FakeAgent.TRACES) {
                if (name.equals(((Map<?, ?>) trace.get("attrs")).get("http.request.header.x_test"))) return trace;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no trace of " + name);
    }

    @Test
    void a_json_request_s_headers_query_cookies_body_session_and_answer_are_recorded_secrets_masked() throws Exception {
        HttpResponse<String> response = send("POST", "/orders?coupon=SPRING&api_key=k", "application/json",
                "{\"items\":[\"a\",\"b\"],\"card\":{\"number\":\"4242\",\"token\":\"tok_1\"}}",
                "X-Api-Key", "k1", "Cookie", "theme=dark; JSESSIONID=abc");
        Map<String, Object> attrs = attrs("/orders");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(attrs).containsEntry("http.request.header.content_type", "application/json")
                .containsEntry("http.request.header.x_api_key", M)
                .containsEntry("url.query", "coupon=SPRING&api_key=" + M)
                .containsEntry("http.request.cookie.theme", "dark")
                .containsEntry("http.request.cookie.jsessionid", M)
                .doesNotContainKey("http.request.header.cookie");

        assertThat(attrs).containsEntry("http.request.body",
                "{\"items\":[\"a\",\"b\"],\"card\":{\"number\":\"4242\",\"token\":\"" + M + "\"}}");

        assertThat(attrs).containsEntry("session.cart", "'3 books'").containsEntry("session.csrf_token", M);

        assertThat(attrs).containsEntry("http.response.header.x_order", "17")
                .containsEntry("http.response.header.set_cookie", M)
                .containsEntry("http.status_code", 200);
    }

    @Test
    void a_form_the_container_parsed_is_its_fields_without_the_query_s_a_password_masked() throws Exception {
        send("POST", "/sign-up?ref=mail", "application/x-www-form-urlencoded", "email=ann%40example.com&password=hunter2");
        Map<String, Object> attrs = attrs("/sign-up");

        assertThat(attrs).containsEntry("http.request.body", "email=ann@example.com&password=" + M)
                .containsEntry("url.query", "ref=mail");
    }

    @Test
    void a_form_with_a_file_lists_the_file_by_name_and_size() throws Exception {
        String body = "--XyZ\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nDune\r\n"
                + "--XyZ\r\nContent-Disposition: form-data; name=\"cover\"; filename=\"dune.png\"\r\nContent-Type: image/png\r\n\r\n"
                + "0123456789\r\n--XyZ--\r\n";
        HttpResponse<String> response = send("POST", "/covers", "multipart/form-data; boundary=XyZ", body);
        Map<String, Object> attrs = attrs("/covers");

        assertThat(response.body()).isEqualTo("Dune 10");
        assertThat(attrs).containsEntry("http.request.body", "title=Dune&cover=<file dune.png, 10 bytes>");
    }

    @Test
    void a_body_the_handler_never_read_is_not_read_for_it() throws Exception {
        send("POST", "/ignored", "text/plain", "never read");
        Map<String, Object> attrs = attrs("/ignored");

        assertThat(attrs).doesNotContainKey("http.request.body").containsEntry("http.request.header.content_type", "text/plain");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_request_that_asks_is_profiled_its_handler_s_frames_the_app_s() throws Exception {
        send("GET", "/report", null, null, "X-Tracepanel-Profile", "1", "X-Test", "profiled");
        Map<String, Object> trace = traceNamed("profiled");
        Map<String, Object> profile = (Map<String, Object>) trace.get("profile");
        List<Map<String, Object>> frames = (List<Map<String, Object>>) profile.get("frames");
        Map<String, Object> crunch = frames.stream().filter(frame -> "OrderController.crunch".equals(frame.get("function")))
                .findFirst().orElseThrow();

        assertThat(profile).containsEntry("interval_us", 1000);
        assertThat((Integer) profile.get("samples")).isGreaterThan(5);
        assertThat(crunch).containsEntry("module", "shop.OrderController")
                .containsEntry("file", "src/test/java/shop/OrderController.java")
                .containsEntry("in_app", true);
        assertThat(frames).noneSatisfy(frame -> assertThat(String.valueOf(frame.get("module"))).startsWith("dev.tracepanel."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_panel_s_cookie_profiles_a_request_answered_later_on_the_thread_that_answers() throws Exception {
        send("GET", "/report/later", null, null, "Cookie", "tracepanel_profile=1");
        Map<String, Object> profile = (Map<String, Object>) FakeAgent.traceAt("/report/later").get("profile");
        List<Map<String, Object>> frames = (List<Map<String, Object>>) profile.get("frames");

        assertThat(frames).anySatisfy(frame -> assertThat(frame).containsEntry("function", "OrderController.crunch"));
    }

    @Test
    void a_request_that_doesn_t_ask_is_not_profiled_and_the_hello_says_what_the_app_records() throws Exception {
        send("GET", "/report", null, null, "X-Test", "unprofiled");

        assertThat(traceNamed("unprofiled")).doesNotContainKey("profile");

        assertThat(FakeAgent.HELLOS).anySatisfy(hello -> assertThat(hello).contains("\"capabilities\":[\"explain\",\"profile\",\"request\",\"live_tasks\"]"));
    }
}
