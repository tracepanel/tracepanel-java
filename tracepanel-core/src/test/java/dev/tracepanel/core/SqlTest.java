package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
