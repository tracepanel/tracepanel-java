package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A call the app makes: what it asks of the app it reaches, a profile its trace's request asked for too. */
class HttpCallsTest {
    private HttpServer server;
    private URI asked;

    @BeforeEach
    void serve() throws Exception {
        // What the app it reaches was asked: its traceparent, and the profile, each "none" when it wasn't.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/asked", exchange -> {
            String parent = exchange.getRequestHeaders().getFirst("traceparent");
            String profile = exchange.getRequestHeaders().getFirst("X-Tracepanel-Profile");
            byte[] body = ((parent == null ? "none" : "traceparent") + " " + (profile == null ? "none" : profile))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        asked = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/asked");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        Tracepanel.bind(null);
    }

    /** A call through the JDK's client in `trace`, with `headers` of the app's own: what the other app was asked. */
    private String call(Trace trace, String... headers) throws Exception {
        Tracepanel.bind(trace);
        HttpRequest.Builder request = HttpRequest.newBuilder(asked);
        if (headers.length > 0) request.headers(headers);
        return HttpCalls.wrap(HttpClient.newHttpClient()).send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void a_call_asks_for_the_profile_its_trace_s_request_asked_for() throws Exception {
        Trace time = new Trace("http", "GET /time", Map.of());
        time.asked = "1";
        Trace memory = new Trace("http", "GET /memory", Map.of());
        memory.asked = "memory";

        assertEquals("traceparent 1", call(time));
        assertEquals("traceparent memory", call(memory));
        assertEquals("1", HttpCalls.start(time, "GET", asked).profile());
    }

    @Test
    void a_trace_its_request_didn_t_ask_to_profile_asks_nothing_of_its_calls() throws Exception {
        // Profiled by the setting (TRACEPANEL_PROFILE) or not at all: nothing asked, each app's setting decides.
        Trace trace = new Trace("http", "GET /", Map.of());

        assertEquals("traceparent none", call(trace));
        assertNull(HttpCalls.start(trace, "GET", asked).profile());
    }

    @Test
    void the_app_s_own_header_is_the_one_that_goes() throws Exception {
        Trace trace = new Trace("http", "GET /", Map.of());
        trace.asked = "memory";

        assertEquals("traceparent 1", call(trace, "X-Tracepanel-Profile", "1"));
        assertEquals("traceparent memory", call(trace, "traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
    }

    @Test
    void outside_a_trace_a_call_carries_neither() throws Exception {
        assertEquals("none none", call(null));
    }
}
