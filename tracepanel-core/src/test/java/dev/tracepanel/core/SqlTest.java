package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The same rules, and the same cases, as the other SDKs': the agent groups their queries alike. */
class SqlTest {
    @Test
    void folds_whitespace_into_one_line() {
        assertEquals("SELECT * FROM books WHERE id = ?", Sql.statement("SELECT *\n  FROM books\n WHERE id = ?"));
    }

    @Test
    void folds_in_lists_as_every_sdk_does() {
        assertEquals("SELECT * FROM books WHERE id IN (...)", Sql.fingerprint("SELECT * FROM books WHERE id IN (?, ?, ?)"));
        assertEquals("SELECT * FROM books WHERE id IN (...)", Sql.fingerprint("SELECT * FROM books WHERE id IN ($1, $2, $3)"));
    }

    @Test
    void names_a_span_after_its_verb_and_table() {
        assertEquals("SELECT authors", Sql.spanName("SELECT name FROM authors WHERE id = ?"));
        assertEquals("SELECT COUNT books", Sql.spanName("select count(*) from books"));
        assertEquals("INSERT books", Sql.spanName("INSERT INTO \"books\" (title) VALUES (?)"));
        assertEquals("BEGIN", Sql.spanName("BEGIN"));
    }

    @Test
    void closes_a_line_comment_and_names_and_groups_without_comments_as_the_agent_does() {
        String sql = "/*controller='orders',traceparent='00-4bf9-01'*/ SELECT id\n-- the page's rows\nFROM orders "
                + "WHERE note = '-- not /* a */ comment' AND id IN ($1, $2)";

        assertEquals(
                List.of(
                        "/*controller='orders',traceparent='00-4bf9-01'*/ SELECT id /* the page's rows */ FROM orders "
                                + "WHERE note = '-- not /* a */ comment' AND id IN ($1, $2)",
                        "SELECT id FROM orders WHERE note = '-- not /* a */ comment' AND id IN (...)",
                        "SELECT orders"),
                List.of(Sql.statement(sql), Sql.fingerprint(sql), Sql.spanName(sql)));
    }

    @Test
    void leaves_out_a_leading_traceparent_comment() {
        String sql = "/*traceparent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'*/ SELECT * FROM orders";

        assertEquals(
                List.of(sql, "SELECT * FROM orders", "SELECT orders"),
                List.of(Sql.statement(sql), Sql.fingerprint(sql), Sql.spanName(sql)));
    }

    @Test
    void closes_a_line_comment_mid_statement_a_closing_mark_in_it_kept_from_ending_it() {
        String sql = "SELECT count(*)\n  -- the */ count $1\n  FROM books --\nWHERE id = ?";

        assertEquals(
                List.of(
                        "SELECT count(*) /* the * / count $1 */ FROM books /* */ WHERE id = ?",
                        "SELECT count(*) FROM books WHERE id = ?",
                        "SELECT COUNT books"),
                List.of(Sql.statement(sql), Sql.fingerprint(sql), Sql.spanName(sql)));
    }

    @Test
    void leaves_comment_marks_in_a_string_or_a_quoted_name_as_they_are() {
        String sql = "SELECT \"a--b\", `c/*d` FROM notes WHERE body = 'it''s -- not /* a comment' AND x = \"--\"";

        assertEquals(
                List.of(sql, sql, "SELECT notes"),
                List.of(Sql.statement(sql), Sql.fingerprint(sql), Sql.spanName(sql)));
    }
}
