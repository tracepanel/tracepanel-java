package shop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tracepanel.testing.FakeAgent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The console's Live: the task events an app sends for its @Async and @Scheduled work as it goes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveTasksTest {
    @LocalServerPort
    int port;

    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
    }

    private void get(String path) throws Exception {
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** An event as the agent got it, its time checked and left out. */
    static Map<String, Object> timeless(Map<String, Object> event) {
        assertThat(event.get("time")).isInstanceOf(Number.class);
        assertThat(((Number) event.get("time")).longValue()).isPositive();
        Map<String, Object> rest = new LinkedHashMap<>(event);
        rest.remove("time");
        return rest;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> attrs(Map<String, Object> trace) {
        return (Map<String, Object>) trace.get("attrs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void an_async_method_is_queued_in_the_request_then_runs_on_the_app_s_worker_until_it_ends() throws Exception {
        get("/queue/rebuild");
        Map<String, Object> request = FakeAgent.traceAt("/queue/rebuild");
        Map<String, Object> task = FakeAgent.trace("Jobs.rebuildIndex");
        Map<String, Object> queued = ((List<Map<String, Object>>) request.get("spans")).stream()
                .filter(span -> "task.enqueue".equals(span.get("kind"))).findFirst().orElseThrow();
        String id = (String) attrs(task).get("task.id");
        String worker = (String) attrs(task).get("task.worker");
        List<Map<String, Object>> events = FakeAgent.taskEvents(id, "enqueued", "finished");

        assertThat(worker).matches("[^@]+@.+");
        assertThat(attrs(queued)).containsEntry("task.id", id);

        assertThat(events.stream().map(LiveTasksTest::timeless).toList()).isEqualTo(List.of(
                Map.of("event", "enqueued", "system", "spring", "id", id, "name", "Jobs.rebuildIndex",
                        "trace_id", request.get("id"), "span_id", queued.get("id")),
                Map.of("event", "started", "system", "spring", "id", id, "name", "Jobs.rebuildIndex", "worker", worker,
                        "retries", 0),
                Map.of("event", "finished", "system", "spring", "id", id, "worker", worker, "state", "SUCCESS")));
    }

    @Test
    void an_async_method_that_throws_finishes_failed() throws Exception {
        get("/queue/charge");
        Map<String, Object> task = FakeAgent.trace("Jobs.chargeCard");
        String id = (String) attrs(task).get("task.id");
        List<Map<String, Object>> events = FakeAgent.taskEvents(id, "finished");

        assertThat(task).containsEntry("status", "error");

        assertThat(timeless(events.get(events.size() - 1))).isEqualTo(Map.of(
                "event", "finished", "system", "spring", "id", id, "worker", attrs(task).get("task.worker"), "state", "FAILURE"));
    }

    @Test
    void a_scheduled_method_runs_in_live_and_a_runner_is_not_there() throws Exception {
        FakeAgent.trace("Jobs.nightly");
        String id = FakeAgent.TASK_EVENTS.stream()
                .filter(event -> "Jobs.nightly".equals(event.get("name")))
                .map(event -> (String) event.get("id")).findFirst().orElseThrow();
        List<Map<String, Object>> events = FakeAgent.taskEvents(id, "finished");
        Object worker = events.get(0).get("worker");

        assertThat(events.stream().map(LiveTasksTest::timeless).toList()).isEqualTo(List.of(
                Map.of("event", "started", "system", "scheduled", "id", id, "name", "Jobs.nightly", "worker", worker,
                        "retries", 0),
                Map.of("event", "finished", "system", "scheduled", "id", id, "worker", worker, "state", "SUCCESS")));

        assertThat(FakeAgent.TASK_EVENTS).noneSatisfy(event -> assertThat(event).containsEntry("name", "Jobs.run"));
    }

    @Test
    void the_app_is_a_worker_with_a_heartbeat_and_its_hello_says_it_sends_task_events() throws Exception {
        get("/queue/rebuild");
        String worker = (String) attrs(FakeAgent.trace("Jobs.rebuildIndex")).get("task.worker");

        assertThat(FakeAgent.TASK_EVENTS).anySatisfy(event -> {
            assertThat(event).containsEntry("event", "heartbeat").containsEntry("id", "").containsEntry("worker", worker);
            assertThat(event.get("ids")).isInstanceOf(List.class);
        });

        assertThat(FakeAgent.HELLOS).anySatisfy(hello -> assertThat(hello).contains("\"live_tasks\""));
    }
}
