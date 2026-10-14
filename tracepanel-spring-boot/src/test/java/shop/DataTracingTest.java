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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** MongoDB and Redis commands as spans: run with a server of each, named by TEST_MONGODB_URI and TEST_REDIS_PORT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataTracingTest {
    @LocalServerPort
    int port;

    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> spans(String path, String kind) throws Exception {
        HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(), HttpResponse.BodyHandlers.ofString());
        List<Map<String, Object>> spans = (List<Map<String, Object>>) FakeAgent.traceAt(path).get("spans");
        return spans.stream().filter(span -> kind.equals(span.get("kind"))).toList();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_MONGODB_URI", matches = ".+")
    @SuppressWarnings("unchecked")
    void each_mongodb_command_is_a_span_with_its_shape_and_values() throws Exception {
        List<Map<String, Object>> finds = spans("/data/mongo", "mongodb").stream()
                .filter(span -> "find authors".equals(span.get("name"))).toList();
        Map<String, Object> first = (Map<String, Object>) finds.get(0).get("attrs");

        assertThat(finds).hasSize(4);
        assertThat(first).containsEntry("db.system", "mongodb")
                .containsEntry("db.operation", "find")
                .containsEntry("db.collection.name", "authors")
                .containsEntry("db.params", List.of("1"))
                .containsEntry("db.response.returned_rows", 1);
        assertThat(String.valueOf(first.get("db.statement"))).contains("\"filter\":{\"_id\":\"?\"}");
        assertThat(String.valueOf(first.get("code.origin"))).matches("src/test/java/shop/DataController\\.java:\\d+ in mongo");
        assertThat(finds.get(0).get("attrs")).isNotEqualTo(finds.get(1).get("attrs"));
        assertThat(((Map<String, Object>) finds.get(2).get("attrs")).get("db.params_hash")).isEqualTo(first.get("db.params_hash"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_REDIS_PORT", matches = ".+")
    @SuppressWarnings("unchecked")
    void each_redis_command_is_a_span_with_its_key() throws Exception {
        List<Map<String, Object>> commands = spans("/data/redis", "redis");
        Map<String, Object> get = commands.stream().filter(span -> "GET book:1".equals(span.get("name"))).findFirst().orElseThrow();
        Map<String, Object> attrs = (Map<String, Object>) get.get("attrs");

        assertThat(commands).hasSize(8);
        assertThat(attrs).containsEntry("db.system", "redis")
                .containsEntry("db.operation", "GET")
                .containsEntry("redis.key", "book:1")
                .containsEntry("server.port", Integer.parseInt(System.getenv("TEST_REDIS_PORT")));
        assertThat(String.valueOf(attrs.get("code.origin"))).matches("src/test/java/shop/DataController\\.java:\\d+ in redis");
    }
}
