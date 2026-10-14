package shop;

import static org.assertj.core.api.Assertions.assertThat;

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
}
