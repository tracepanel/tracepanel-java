package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent's EXPLAIN asked down the app's connection, as `Apps::ask` in the agent sends it, over a fake agent. */
class AgentRequestsTest {
    private static final CountDownLatch SLOW = new CountDownLatch(1);

    /** An author table in H2, the database of alias `books`, and `slow`, which waits for SLOW to open a connection. */
    @BeforeAll
    static void databases() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:agent-requests;DB_CLOSE_DELAY=-1");
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE author (id INT PRIMARY KEY, name VARCHAR(100))");
        }
        Jdbc.register(h2, "books");
        Jdbc.register((DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) SLOW.await();
                    return method.invoke(h2, args);
                }), "slow");
    }

    private static String explain(long id, String alias, String sql) {
        return Json.write(Map.of("id", id, "explain", Map.of(
                "sql", sql, "params", List.of(7), "vendor", "h2", "alias", alias, "analyze", false)));
    }

    private static Sender sender(Path root, String address) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        Map<String, String> env = Map.of("TRACEPANEL_ROOT", root.toString(), "TRACEPANEL_DIR", store.toString());
        Files.writeString(store.resolve("agent.addr"), address + "\n");
        return new Sender(Config.load(env, "shop"), new Agent(store, env));
    }

    @SuppressWarnings("unchecked")
    private static void assertPlan(Object answer, long id) {
        Map<String, Object> found = (Map<String, Object>) answer;
        List<List<Object>> rows = (List<List<Object>>) found.get("rows");

        assertEquals(Map.of("answer", id, "columns", List.of("PLAN"), "rows", rows), found);

        assertEquals(1, rows.size());
        assertTrue(String.valueOf(rows.get(0).get(0)).contains("FROM \"PUBLIC\".\"AUTHOR\""), rows.toString());
    }

    @Test
    void the_app_answers_the_agent_s_explain_with_the_rows_of_its_database(@TempDir Path root) throws Exception {
        try (SenderTest.OneAgent agent = new SenderTest.OneAgent()) {
            sender(root, agent.address()).submit(Map.of("id", "first"));
            assertTrue(agent.got("first"));

            agent.ask(explain(1, "books", "EXPLAIN SELECT name FROM author WHERE id = ?"));
            agent.ask(explain(2, "nowhere", "EXPLAIN SELECT 1"));
            agent.ask(explain(3, "books", "DELETE FROM author"));
            agent.ask(explain(4, "books", "EXPLAIN SELECT nope FROM author"));

            assertPlan(agent.answer(1), 1);

            assertEquals(Map.of("answer", 2L, "error", "The app has no database nowhere to run EXPLAIN on."), agent.answer(2));
            assertEquals(Map.of("answer", 3L, "error", "Only an EXPLAIN is run."), agent.answer(3));
            Map<?, ?> failed = (Map<?, ?>) agent.answer(4);
            assertEquals(Set.of("answer", "error"), failed.keySet());
            assertEquals(4L, failed.get("answer"));
            assertTrue(String.valueOf(failed.get("error")).contains("NOPE"), failed.toString());

            assertTrue(agent.hellos.get(0).contains("\"capabilities\":[\"explain\","), agent.hellos.get(0));
            assertTrue(agent.hellos.get(0).contains("\"console\":[\"explain\"]"), agent.hellos.get(0));
        }
    }

    @Test
    void a_request_right_behind_the_answer_to_the_hello_is_answered_too(@TempDir Path root) throws Exception {
        try (SenderTest.OneAgent agent = new SenderTest.OneAgent(explain(5, "books", "EXPLAIN SELECT name FROM author WHERE id = ?"))) {
            Sender sender = sender(root, agent.address());
            sender.submit(Map.of("id", "first"));

            assertPlan(agent.answer(5), 5);

            assertTrue(agent.got("first"));
        }
    }

    @Test
    void on_a_unix_socket_too(@TempDir Path root) throws Exception {
        try (SenderTest.UnixAgent agent = new SenderTest.UnixAgent(root.resolve("a.sock"))) {
            sender(root, "unix:" + agent.path).submit(Map.of("id", "first"));
            assertTrue(agent.got("first"));

            agent.ask(explain(6, "books", "EXPLAIN SELECT name FROM author WHERE id = ?"));

            assertPlan(agent.answer(6), 6);
        }
    }

    @Test
    void the_traces_go_on_while_a_database_takes_its_time(@TempDir Path root) throws Exception {
        try (SenderTest.OneAgent agent = new SenderTest.OneAgent()) {
            Sender sender = sender(root, agent.address());
            sender.submit(Map.of("id", "first"));
            assertTrue(agent.got("first"));

            agent.ask(explain(7, "slow", "EXPLAIN SELECT name FROM author WHERE id = ?"));
            agent.ask(explain(8, "books", "EXPLAIN SELECT name FROM author WHERE id = ?"));
            sender.submit(Map.of("id", "meanwhile"));

            assertTrue(agent.got("meanwhile"));
            assertPlan(agent.answer(8), 8);

            SLOW.countDown();

            assertPlan(agent.answer(7), 7);
        }
    }

    @Test
    void the_connection_after_the_agent_restarted_answers(@TempDir Path root) throws Exception {
        Sender sender;
        try (SenderTest.OneAgent first = new SenderTest.OneAgent()) {
            sender = sender(root, first.address());
            sender.submit(Map.of("id", "before"));
            assertTrue(first.got("before"));
        }
        try (SenderTest.OneAgent second = new SenderTest.OneAgent()) {
            Files.writeString(root.resolve(".tracepanel/agent.addr"), second.address() + "\n");
            TimeUnit.MILLISECONDS.sleep(100);
            sender.submit(Map.of("id", "after"));
            assertTrue(second.got("after"));

            second.ask(explain(9, "books", "EXPLAIN SELECT name FROM author WHERE id = ?"));

            assertPlan(second.answer(9), 9);
        }
    }

    @Test
    void frames_are_read_whole_however_the_bytes_come() throws Exception {
        List<Map<String, Object>> answers = new CopyOnWriteArrayList<>();
        AgentRequests requests = new AgentRequests(answers::add);
        byte[] bytes = new byte[0];
        for (String request : List.of(explain(10, "nowhere", "EXPLAIN SELECT 1"), "{\"id\": 11}", "not json",
                explain(12, "nowhere", "EXPLAIN SELECT 1"))) {
            byte[] frame = SenderTest.frame(request);
            byte[] joined = new byte[bytes.length + frame.length];
            System.arraycopy(bytes, 0, joined, 0, bytes.length);
            System.arraycopy(frame, 0, joined, bytes.length, frame.length);
            bytes = joined;
        }

        // A byte at a time; the request that asks nothing, and the frame that isn't JSON, go unanswered.
        for (byte b : bytes) requests.read(new byte[] {b}, 1);

        for (int i = 0; i < 250 && answers.size() < 2; i++) TimeUnit.MILLISECONDS.sleep(20);
        assertEquals(List.of(
                        Map.of("answer", 10L, "error", "The app has no database nowhere to run EXPLAIN on."),
                        Map.of("answer", 12L, "error", "The app has no database nowhere to run EXPLAIN on.")),
                answers.stream().sorted((a, b) -> Long.compare((Long) a.get("answer"), (Long) b.get("answer"))).toList());
    }
}
