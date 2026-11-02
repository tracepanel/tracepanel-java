package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The rows a query returned: those the app read from its results, which it reads after the query's span ended. */
class JdbcTest {
    private static final String ROWS = "db.response.returned_rows";
    private static DataSource db;

    @BeforeAll
    static void database() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:rows;DB_CLOSE_DELAY=-1");
        db = Jdbc.dataSource(h2, "default");
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS book (id INT PRIMARY KEY, title VARCHAR(50))");
            statement.execute("MERGE INTO book KEY (id) VALUES (1, 'One'), (2, 'Two'), (3, 'Three'), (4, 'Four')");
        }
    }

    @AfterEach
    void unbind() {
        Tracepanel.bind(null);
    }

    private static Trace trace() {
        Trace trace = Traces.request("GET /books");
        Tracepanel.bind(trace);
        return trace;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> queries(Map<String, Object> doc) {
        return ((List<Map<String, Object>>) doc.get("spans")).stream()
                .filter(span -> "sql".equals(span.get("kind")))
                .map(span -> (Map<String, Object>) span.get("attrs"))
                .toList();
    }

    private static int read(ResultSet rows, int most) throws SQLException {
        int read = 0;
        while (read < most && rows.next()) read++;
        return read;
    }

    @Test
    void a_statement_s_query_counts_the_rows_read_to_the_end() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM book")) {
            read(rows, Integer.MAX_VALUE);
        }

        List<Map<String, Object>> queries = queries(trace.finish());

        assertEquals(1, queries.size());
        assertEquals(4, queries.get(0).get(ROWS));
    }

    @Test
    void a_prepared_query_read_in_part_counts_what_was_read() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT id FROM book WHERE id > ?")) {
            statement.setInt(1, 0);
            try (ResultSet rows = statement.executeQuery()) {
                read(rows, 2);
            }
        }

        assertEquals(2, queries(trace.finish()).get(0).get(ROWS));
    }

    @Test
    void results_left_open_count_what_was_read_when_the_trace_ends() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection()) {
            PreparedStatement statement = connection.prepareStatement("SELECT id FROM book");
            read(statement.executeQuery(), 3);

            assertEquals(3, queries(trace.finish()).get(0).get(ROWS));
        }
    }

    @Test
    void results_never_read_returned_no_rows() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT id FROM book").close();
        }

        assertEquals(0, queries(trace.finish()).get(0).get(ROWS));
    }

    @Test
    void rows_read_after_the_trace_ended_are_not_in_it() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM book")) {
            read(rows, 1);
            Map<String, Object> doc = trace.finish();
            read(rows, Integer.MAX_VALUE);

            assertEquals(1, queries(doc).get(0).get(ROWS));
        }
    }

    @Test
    void execute_counts_the_rows_of_its_results_and_an_update_has_none() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement()) {
            if (statement.execute("SELECT id FROM book")) {
                try (ResultSet rows = statement.getResultSet()) {
                    read(rows, Integer.MAX_VALUE);
                }
            }
            statement.execute("UPDATE book SET title = title WHERE id = 1");
        }

        List<Map<String, Object>> queries = queries(trace.finish());

        assertEquals(4, queries.get(0).get(ROWS));
        assertFalse(queries.get(1).containsKey(ROWS));
    }

    @Test
    void the_spans_read_while_the_trace_runs_have_the_rows_read_so_far() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id FROM book")) {
            read(rows, 2);

            assertEquals(2, ((Map<?, ?>) trace.spans().get(1).get("attrs")).get(ROWS));
        }
    }

    @Test
    void the_statement_running_again_counts_its_last_results() throws SQLException {
        Trace trace = trace();
        try (Connection connection = db.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT id FROM book WHERE id > ?")) {
            statement.setInt(1, 0);
            read(statement.executeQuery(), 4);
            statement.setInt(1, 2);
            read(statement.executeQuery(), 1);
        }

        List<Map<String, Object>> queries = queries(trace.finish());

        assertEquals(4, queries.get(0).get(ROWS));
        assertEquals(1, queries.get(1).get(ROWS));
    }

    @Entity(name = "Book")
    @jakarta.persistence.Table(name = "book")
    public static class Book {
        @Id
        Integer id;

        String title;
    }

    @Test
    void hibernate_s_queries_count_the_rows_it_read() {
        Configuration configuration = new Configuration().addAnnotatedClass(Book.class);
        configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, db);
        try (SessionFactory hibernate = configuration.buildSessionFactory()) {
            Trace trace = trace();
            hibernate.inTransaction(session -> {
                session.createSelectionQuery("from Book", Book.class).getResultList();
                session.createSelectionQuery("from Book where id = 2", Book.class).getSingleResult();
            });

            List<Map<String, Object>> queries = queries(trace.finish()).stream()
                    .filter(attrs -> String.valueOf(attrs.get("db.statement")).startsWith("select")).toList();

            assertEquals(2, queries.size());
            assertEquals(4, queries.get(0).get(ROWS));
            assertEquals(1, queries.get(1).get(ROWS));
        }
    }
}
