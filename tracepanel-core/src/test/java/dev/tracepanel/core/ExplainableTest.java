package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Which statements EXPLAIN runs, and that ANALYZE leaves nothing behind. Public: H2 calls its NOTE(). */
public class ExplainableTest {
    private static final JdbcDataSource H2 = new JdbcDataSource();
    /** How often NOTE() ran: outside the database, so no rollback undoes it. */
    private static final AtomicInteger CALLS = new AtomicInteger();

    /** The cases the agent's schema/explainable.json holds: a statement, why EXPLAIN refuses it, why ANALYZE does. */
    static Stream<Arguments> cases() throws IOException {
        try (InputStream in = ExplainableTest.class.getResourceAsStream("explainable.json")) {
            List<?> cases = (List<?>) Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return cases.stream().map(found -> {
                Map<?, ?> one = (Map<?, ?>) found;
                return Arguments.of(one.get("sql"), one.get("explain"), one.get("analyze"));
            });
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void the_shared_cases(String sql, String explain, String analyze) {
        assertEquals(Arrays.asList(explain, analyze), Arrays.asList(Explainable.refusal(sql, false), Explainable.refusal(sql, true)));
    }

    /** A note written by the function `NOTE()` on the connection that calls it: a SELECT with a side effect. */
    public static int note(Connection connection) throws SQLException {
        CALLS.incrementAndGet();
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("INSERT INTO noted VALUES (1)");
        }
    }

    @BeforeAll
    static void database() throws SQLException {
        H2.setURL("jdbc:h2:mem:explainable;DB_CLOSE_DELAY=-1");
        try (Connection connection = H2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE book (id INT PRIMARY KEY, title VARCHAR(100))");
            statement.execute("INSERT INTO book VALUES (1, 'Dune')");
            statement.execute("CREATE TABLE noted (n INT)");
            statement.execute("CREATE ALIAS NOTE FOR 'dev.tracepanel.core.ExplainableTest.note'");
        }
        Jdbc.register(H2, "explainable");
    }

    private static Map<String, Object> explain(String sql) throws SQLException {
        return Explain.rows(Map.of("sql", sql, "params", List.of(), "alias", "explainable"));
    }

    private static long noted() throws SQLException {
        try (Connection connection = H2.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM noted")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void a_with_query_is_explained_on_the_database() throws SQLException {
        Map<String, Object> answer = explain("EXPLAIN WITH dune AS (SELECT * FROM book WHERE id = 1) SELECT title FROM dune");

        assertEquals(List.of("PLAN"), answer.get("columns"));
        assertTrue(String.valueOf(answer.get("rows")).contains("\"PUBLIC\".\"BOOK\""), String.valueOf(answer.get("rows")));
    }

    @Test
    void a_query_that_changes_data_inside_with_is_refused() {
        IllegalArgumentException plain = assertThrows(IllegalArgumentException.class,
                () -> explain("EXPLAIN WITH gone AS (DELETE FROM book WHERE id = 1 RETURNING id) SELECT * FROM gone"));
        IllegalArgumentException analyzed = assertThrows(IllegalArgumentException.class,
                () -> explain("EXPLAIN ANALYZE WITH gone AS (DELETE FROM book WHERE id = 1 RETURNING id) SELECT * FROM gone"));
        IllegalArgumentException locks = assertThrows(IllegalArgumentException.class,
                () -> explain("EXPLAIN ANALYZE SELECT * FROM book FOR UPDATE"));

        assertEquals("This query changes data (DELETE inside WITH): it isn't explained.", plain.getMessage());
        assertEquals("This query changes data (DELETE inside WITH): it isn't explained.", analyzed.getMessage());
        assertEquals("This query locks rows (FOR UPDATE): it can be explained, not analyzed.", locks.getMessage());
    }

    @Test
    void what_analyze_does_is_rolled_back() throws SQLException {
        long before = noted();
        int calls = CALLS.get();

        Map<String, Object> answer = explain("EXPLAIN ANALYZE SELECT NOTE() FROM book");

        assertTrue(String.valueOf(answer.get("rows")).contains("NOTE"), String.valueOf(answer.get("rows")));
        // The function ran and wrote its note, which is gone with the transaction.
        assertTrue(CALLS.get() > calls);
        assertEquals(before, noted());
    }
}
