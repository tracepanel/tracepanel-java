package shop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tracepanel.testing.FakeAgent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Kafka and RabbitMQ: run with a broker of each, named by TEST_KAFKA_BOOTSTRAP and TEST_RABBITMQ_PORT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "TEST_KAFKA_BOOTSTRAP", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TEST_RABBITMQ_PORT", matches = ".+")
class MessagingTracingTest {
    @LocalServerPort
    int port;

    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
    }

    @ParameterizedTest
    @CsvSource({"kafka, kafka, onKafkaBook", "rabbit, rabbitmq, onRabbitBook"})
    @SuppressWarnings("unchecked")
    void each_message_sent_is_a_span_and_each_received_a_task_linked_to_it(String path, String system, String listener)
            throws Exception {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/publish/" + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> request = FakeAgent.traceAt("/publish/" + path);
        List<Map<String, Object>> sent = ((List<Map<String, Object>>) request.get("spans")).stream()
                .filter(span -> system.equals(span.get("kind"))).toList();
        Map<String, Object> first = (Map<String, Object>) sent.get(0).get("attrs");

        assertThat(sent).hasSize(3).allSatisfy(span -> assertThat(span).containsEntry("name", "publish books"));
        assertThat(first).containsEntry("messaging.system", system)
                .containsEntry("messaging.operation", "publish")
                .containsEntry("messaging.destination.name", "books");
        assertThat(String.valueOf(first.get("code.origin"))).matches("src/test/java/shop/MessagingController\\.java:\\d+ in " + path);

        Map<String, Object> received = FakeAgent.trace("Listeners." + listener);
        Map<String, Object> attrs = (Map<String, Object>) received.get("attrs");
        assertThat(received).containsEntry("kind", "task");
        assertThat(attrs).containsEntry("task.system", system)
                .containsEntry("task.queue", "books")
                .containsEntry("task.trigger", "message")
                .containsEntry("w3c.trace_id", ((Map<String, Object>) request.get("attrs")).get("w3c.trace_id"));
        assertThat((List<Map<String, Object>>) received.get("links")).singleElement().satisfies(link -> {
            assertThat(link).containsEntry("kind", "sent_by").containsEntry("trace_id", request.get("id"));
            assertThat(sent).extracting(span -> span.get("id")).contains(link.get("span_id"));
        });
    }

    @ParameterizedTest
    @CsvSource({"kafka, kafka, onKafkaBook", "rabbit, rabbitmq, onRabbitBook"})
    @SuppressWarnings("unchecked")
    void a_message_sent_is_queued_in_live_and_runs_named_by_its_listener_until_it_returns(String path, String system,
            String listener) throws Exception {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/publish/" + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> received = FakeAgent.trace("Listeners." + listener);
        Map<String, Object> attrs = (Map<String, Object>) received.get("attrs");
        String id = (String) attrs.get("task.id");
        String worker = (String) attrs.get("task.worker");
        Map<String, Object> publish = ((List<Map<String, Object>>) received.get("links")).get(0);
        List<Map<String, Object>> events = FakeAgent.taskEvents(id, "enqueued", "finished");
        // The sender's enqueued and the listener's events come from two threads: either may reach the agent first.
        List<Map<String, Object>> enqueued = events.stream().filter(event -> "enqueued".equals(event.get("event"))).toList();
        List<Map<String, Object>> ran = events.stream().filter(event -> !"enqueued".equals(event.get("event"))).toList();

        assertThat(worker).matches("[^@]+@.+");

        assertThat(enqueued.stream().map(LiveTasksTest::timeless).toList()).isEqualTo(List.of(
                Map.of("event", "enqueued", "system", system, "id", id, "name", "books", "queue", "books",
                        "trace_id", publish.get("trace_id"), "span_id", publish.get("span_id"))));

        // Named after where it goes until its listener takes it; then after the listener, from the same start.
        assertThat(ran.stream().map(LiveTasksTest::timeless).toList()).isEqualTo(List.of(
                Map.of("event", "started", "system", system, "id", id, "name", "books", "queue", "books", "worker", worker,
                        "retries", 0),
                Map.of("event", "started", "system", system, "id", id, "name", "Listeners." + listener, "queue", "books",
                        "worker", worker, "retries", 0),
                Map.of("event", "finished", "system", system, "id", id, "worker", worker, "state", "SUCCESS")));

        assertThat(ran.get(0).get("time")).isEqualTo(ran.get(1).get("time"));
    }

    /** The trace of the message the request to `path` sent, once its listener handled it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> receivedFrom(String path) throws InterruptedException {
        Object sender = FakeAgent.traceAt(path).get("id");
        for (int waited = 0; waited < 10000; waited += 20) {
            for (Map<String, Object> trace : FakeAgent.TRACES) {
                List<Map<String, Object>> links = (List<Map<String, Object>>) trace.get("links");
                if (links != null && links.stream().anyMatch(link -> sender.equals(link.get("trace_id")))) return trace;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no trace of the message " + path + " sent");
    }

    private void get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (headers.length > 0) request.headers(headers);
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @CsvSource({"kafka, onKafkaReport", "rabbit, onRabbitReport"})
    @SuppressWarnings("unchecked")
    void a_message_sent_by_a_request_that_asked_to_be_profiled_is_profiled_where_it_is_received(String path, String listener)
            throws Exception {
        get("/publish/" + path + "/report/time", "X-Tracepanel-Profile", "1");
        get("/publish/" + path + "/report/memory", "Cookie", "tracepanel_profile=memory");
        get("/publish/" + path + "/report/none");
        get("/publish/" + path + "/report/code");
        Map<String, Object> time = receivedFrom("/publish/" + path + "/report/time");
        Map<String, Object> memory = receivedFrom("/publish/" + path + "/report/memory");
        Map<String, Object> none = receivedFrom("/publish/" + path + "/report/none");
        Map<String, Object> profile = (Map<String, Object>) time.get("profile");

        assertThat(time).containsEntry("name", "Listeners." + listener);
        assertThat(profile).containsEntry("interval_us", 1000);
        assertThat((List<Map<String, Object>>) profile.get("frames"))
                .anySatisfy(frame -> assertThat(frame).containsEntry("function", "OrderController.crunch"));

        assertThat(memory).containsKey("profile");

        assertThat(none).containsEntry("name", "Listeners." + listener).doesNotContainKey("profile");

        // The sender's own code profiled its trace: the message asks for a profile all the same.
        Map<String, Object> code = receivedFrom("/publish/" + path + "/report/code");
        assertThat(code).containsKey("profile");
        assertThat((Map<String, Object>) code.get("attrs")).containsEntry("profile.reason", "parent");
        assertThat((Map<String, Object>) FakeAgent.traceAt("/publish/" + path + "/report/code").get("attrs"))
                .containsEntry("profile.reason", "code");
    }
}
