package shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.tracepanel.core.Tracepanel;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The console's Live and EXPLAIN with the real agent, which the app starts as it would in development: run by the
 * build's testAgent task, with TEST_AGENT_BIN naming the agent's binary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "TEST_AGENT_BIN", matches = ".+")
class AgentTest {
    private static final Path STORE = Path.of(System.getenv("TRACEPANEL_DIR"));

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate db;

    private final HttpClient http = HttpClient.newHttpClient();

    /** The agent this app starts, not one an earlier run left. */
    @BeforeAll
    static void fresh() throws Exception {
        stopAgent();
        Files.deleteIfExists(STORE.resolve("agent.addr"));
        Files.deleteIfExists(STORE.resolve("console.addr"));
    }

    @AfterAll
    static void stop() {
        Jobs.RELEASE.countDown();
        stopAgent();
    }

    private static void stopAgent() {
        ProcessHandle.allProcesses()
                .filter(process -> process.info().commandLine().orElse("").contains("--dir " + STORE))
                .forEach(ProcessHandle::destroy);
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The agent's console, once the app started it. */
    private static String console() throws InterruptedException {
        for (int waited = 0; waited < 10_000; waited += 50) {
            String console = Tracepanel.get().agent().console();
            if (console != null) return console;
            Thread.sleep(50);
        }
        throw new AssertionError("the agent didn't start");
    }

    /** What `api/tasks/live` answers once `until` holds for it. */
    private Map<String, Object> live(String console, Predicate<Map<String, Object>> until) throws Exception {
        Map<String, Object> live = Map.of();
        for (int waited = 0; waited < 10_000; waited += 50) {
            live = JsonPath.parse(get(console + "api/tasks/live").body()).read("$");
            if (until.test(live)) return live;
            Thread.sleep(50);
        }
        throw new AssertionError("api/tasks/live never got there: " + live);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasks(Map<String, Object> live, String name) {
        return ((List<Map<String, Object>>) live.get("tasks")).stream().filter(task -> name.equals(task.get("name"))).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_agent_lists_an_async_task_running_on_the_app_s_worker_until_it_ends() throws Exception {
        HttpResponse<String> queued = get("http://127.0.0.1:" + port + "/queue/import");
        String request = queued.headers().firstValue("Tracepanel-Id").orElseThrow();
        String console = console();
        String worker = Tracepanel.get().tasks().worker();
        String service = Tracepanel.get().config().service();

        Map<String, Object> live = live(console, now -> tasks(now, "Jobs.importCatalog").size() == 1);
        Map<String, Object> task = new HashMap<>(tasks(live, "Jobs.importCatalog").get(0));
        Map<String, Object> enqueuedBy = (Map<String, Object>) task.remove("enqueued_by");

        assertThat(task.remove("id")).asString().matches("[0-9A-Z]{26}");
        assertThat((Number) task.remove("enqueued_at")).isNotNull();
        assertThat((Number) task.remove("started_at")).isNotNull();
        Map<String, Object> expected = new HashMap<>();
        expected.putAll(Map.of("system", "spring", "name", "Jobs.importCatalog", "service", service, "state", "running",
                "worker", worker, "retries", 0));
        for (String unknown : List.of("queue", "args", "kwargs", "error")) expected.put(unknown, null);
        assertThat(task).isEqualTo(expected);
        assertThat(enqueuedBy).containsEntry("trace_id", request).containsKey("span_id");

        assertThat((List<Map<String, Object>>) live.get("workers")).anySatisfy(found -> assertThat(found)
                .containsEntry("name", worker)
                .containsEntry("service", service)
                .containsEntry("in_flight", 1));

        assertThat(JsonPath.parse(get(console + "api/capabilities").body()).read("$.live_tasks", Boolean.class)).isTrue();

        Jobs.RELEASE.countDown();

        live(console, now -> tasks(now, "Jobs.importCatalog").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_agent_explains_a_recorded_query_asking_the_app_down_its_connection() throws Exception {
        db.execute("CREATE TABLE IF NOT EXISTS author (id INT PRIMARY KEY, name VARCHAR(50))");
        db.execute("CREATE TABLE IF NOT EXISTS book (id INT PRIMARY KEY, title VARCHAR(50), author_id INT)");
        db.execute("MERGE INTO author KEY (id) VALUES (1, 'Ann')");
        db.execute("MERGE INTO book KEY (id) VALUES (1, 'One', 1)");
        String id = get("http://127.0.0.1:" + port + "/shelves/explained/books").headers().firstValue("Tracepanel-Id").orElseThrow();
        String console = console();
        HttpResponse<String> stored = get(console + "api/traces/" + id);
        for (int waited = 0; waited < 10_000 && stored.statusCode() != 200; waited += 50) {
            Thread.sleep(50);
            stored = get(console + "api/traces/" + id);
        }
        List<Map<String, Object>> spans = JsonPath.parse(stored.body()).read("$.spans");
        Map<String, Object> lookup = spans.stream().filter(span -> "SELECT author".equals(span.get("name"))).findFirst().orElseThrow();

        // The agent's own console: the app answers down its connection, not at a console of its own.
        HttpResponse<String> explained = get(console + "api/explain?trace=" + id + "&span=" + lookup.get("id"));

        assertThat(explained.statusCode()).as(explained.body()).isEqualTo(200);
        Map<String, Object> plan = new HashMap<>(JsonPath.parse(explained.body()).read("$", Map.class));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) plan.remove("lines");
        assertThat(plan).isEqualTo(Map.of("vendor", "h2", "alias", "default", "analyzed", false));
        assertThat(lines).anySatisfy(line -> assertThat(line).containsEntry("text", "FROM \"PUBLIC\".\"AUTHOR\""));

        assertThat(JsonPath.parse(get(console + "api/capabilities").body()).read("$.explain", Boolean.class)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_app_s_console_leaves_another_service_s_query_to_the_app_that_recorded_it() throws Exception {
        String console = console();
        // A BFF in front of this app, connected to the same agent, its query run on its own database.
        try (SocketChannel bff = connect(Files.readString(STORE.resolve("agent.addr")).strip())) {
            DataOutputStream out = new DataOutputStream(Channels.newOutputStream(bff));
            DataInputStream in = new DataInputStream(Channels.newInputStream(bff));
            out.write("{\"protocol\":1,\"service\":\"library-bff\",\"capabilities\":[\"explain\"]}\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            assertThat(line(in)).contains("\"ok\":true");
            // A trace id as the agent takes them: 26 of 0-9 and A-Z.
            String id = ("01JBFF" + Long.toString(System.nanoTime(), 36).toUpperCase() + "0".repeat(26)).substring(0, 26);
            String trace = "[{\"id\":\"" + id + "\",\"kind\":\"http\",\"name\":\"GET /cart\",\"start\":0,\"duration\":5,"
                    + "\"status\":\"ok\",\"attrs\":{\"service.name\":\"library-bff\"},\"spans\":[{\"id\":\"q1\","
                    + "\"parent_id\":null,\"kind\":\"sql\",\"name\":\"SELECT cart\",\"start\":1,\"duration\":1,"
                    + "\"status\":\"ok\",\"attrs\":{\"db.system\":\"sqlite\",\"db.statement\":\"SELECT * FROM cart\","
                    + "\"db.params\":[]}}],\"logs\":[]}]";
            byte[] frame = trace.getBytes(StandardCharsets.UTF_8);
            out.writeInt(frame.length);
            out.write(frame);
            out.flush();
            for (int waited = 0; waited < 10_000 && get(console + "api/traces/" + id).statusCode() != 200; waited += 50) {
                Thread.sleep(50);
            }
            // The BFF answers the agent's request with the rows its database gave.
            CompletableFuture<Map<String, Object>> asked = CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] request = new byte[in.readInt()];
                    in.readFully(request);
                    Map<String, Object> read = JsonPath.parse(new String(request, StandardCharsets.UTF_8)).read("$");
                    byte[] answer = ("{\"answer\":" + read.get("id") + ",\"columns\":[\"id\",\"parent\",\"notused\",\"detail\"],"
                            + "\"rows\":[[2,0,0,\"SCAN cart\"]]}").getBytes(StandardCharsets.UTF_8);
                    out.writeInt(answer.length);
                    out.write(answer);
                    out.flush();
                    return read;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            HttpResponse<String> explained = get("http://127.0.0.1:" + port + "/__tracepanel__/api/explain?trace=" + id + "&span=q1");

            assertThat(explained.statusCode()).as(explained.body()).isEqualTo(200);
            assertThat(JsonPath.parse(explained.body()).read("$.vendor", String.class)).isEqualTo("sqlite");
            assertThat(JsonPath.parse(explained.body()).read("$.lines[*].text", List.class)).isEqualTo(List.of("SCAN cart"));
            assertThat((Map<String, Object>) asked.get(5, TimeUnit.SECONDS).get("explain"))
                    .containsEntry("sql", "EXPLAIN QUERY PLAN SELECT * FROM cart");
        }
    }

    /** A connection to the agent at its address: a Unix socket, or TCP. */
    private static SocketChannel connect(String address) throws Exception {
        if (address.startsWith("unix:")) {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(address.substring("unix:".length())));
            return channel;
        }
        String hostPort = address.substring("tcp:".length());
        int colon = hostPort.lastIndexOf(':');
        return SocketChannel.open(new InetSocketAddress(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1))));
    }

    /** The agent's reply to the hello, a byte at a time: its requests follow in frames. */
    private static String line(InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) line.append((char) c);
        return line.toString();
    }
}
