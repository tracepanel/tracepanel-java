package shop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tracepanel.testing.FakeAgent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The extension on tests of a plain JDBC app, with the fake agent: each test a trace, failed by its rules. */
class ExtensionTest {
    private static final String KINDS = "n+1, duplicates, slow-sql, slow-http, slow-call, error, long-transaction, "
            + "in-transaction, htmx, queue-wait, blocking-call, large-result";

    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
    }

    @AfterEach
    void issues() {
        FakeAgent.ISSUES.clear();
    }

    /** An N+1 the fake agent says it found in a trace: the queries after the first, the author lookups. */
    private static void nPlusOne(String test) {
        FakeAgent.ISSUES.put(test, trace -> List.of(Map.of(
                "kind", "n+1",
                "title", "N+1: 4 similar queries · SELECT name FROM author WHERE id = ?",
                "culprit", "src/test/java/shop/Library.java:54 in titlesWithAuthors",
                "hint", "Read the authors with the books: a JOIN, or one query for all of them.",
                "count", 4,
                "span_ids", Kit.sql(trace).stream().skip(1).map(span -> span.get("id")).toList())));
    }

    @Test
    void a_test_is_a_trace_of_its_own_with_its_queries() throws Exception {
        assertThat(Kit.outcomes(Kit.run(Examples.Passing.class))).isEqualTo(Map.of("lists(TestTrace)", "SUCCESSFUL"));

        Map<String, Object> trace = Kit.trace("shop.Examples$Passing > lists(TestTrace)");
        Map<String, Object> attrs = Kit.attrs(trace);
        assertThat(Map.of("kind", trace.get("kind"), "status", trace.get("status"), "errors", trace.get("errors")))
                .isEqualTo(Map.of("kind", "test", "status", "ok", "errors", List.of()));
        assertThat(Map.of(
                "test.case.name", attrs.get("test.case.name"),
                "code.filepath", attrs.get("code.filepath"),
                "code.lineno", attrs.get("code.lineno"),
                "code.function", attrs.get("code.function"),
                "service.role", attrs.get("service.role")))
                .isEqualTo(Map.of(
                        "test.case.name", "shop.Examples$Passing > lists(TestTrace)",
                        "code.filepath", "src/test/java/shop/Examples.java",
                        "code.lineno", 29,
                        "code.function", "lists",
                        "service.role", "test"));

        assertThat(Kit.spans(trace).stream().map(span -> span.get("kind") + " " + span.get("name")).toList())
                .isEqualTo(List.of("function shop.Examples$Passing > lists(TestTrace)", "sql SELECT author"));
        assertThat(Kit.attrs(Kit.sql(trace).get(0)).get("code.origin")).isEqualTo("src/test/java/shop/Library.java:69 in strings");
    }

    @Test
    void junit_s_extension_autodetection_registers_it_from_the_jar() throws Exception {
        assertThat(Kit.outcomes(Kit.run(Examples.Autodetected.class, "junit.jupiter.extensions.autodetection.enabled", "true")))
                .isEqualTo(Map.of("lists()", "SUCCESSFUL"));

        assertThat(Kit.sql(Kit.trace("shop.Examples$Autodetected > lists()"))).hasSize(1);
    }

    @Test
    void more_queries_than_max_queries_fail_the_test_with_them_listed() {
        assertThat(Kit.outcomes(Kit.run(Examples.TooMany.class))).isEqualTo(Map.of("lists()", """
                AssertionFailedError: 5 queries, at most 2 expected:
                  SELECT id, author_id FROM book ORDER BY id
                    at src/test/java/shop/Library.java:48 in titlesWithAuthors
                  SELECT name FROM author WHERE id = ?
                    at src/test/java/shop/Library.java:54 in titlesWithAuthors
                  SELECT name FROM author WHERE id = ?
                    at src/test/java/shop/Library.java:54 in titlesWithAuthors
                  SELECT name FROM author WHERE id = ?
                    at src/test/java/shop/Library.java:54 in titlesWithAuthors
                  SELECT name FROM author WHERE id = ?
                    at src/test/java/shop/Library.java:54 in titlesWithAuthors"""));
    }

    @Test
    void an_issue_the_agent_finds_fails_the_test_with_its_query_culprit_and_link() throws Exception {
        nPlusOne("shop.Examples$Found > nPlusOne()");

        Map<String, String> outcomes = Kit.outcomes(Kit.run(Examples.Found.class));

        String id = (String) Kit.trace("shop.Examples$Found > nPlusOne()").get("id");
        assertThat(outcomes).isEqualTo(Map.of("nPlusOne()", """
                AssertionFailedError: tracepanel found 1 issue in this test:

                N+1: 4 similar queries · SELECT name FROM author WHERE id = ?
                  SELECT name FROM author WHERE id = ?
                  at src/test/java/shop/Library.java:54 in titlesWithAuthors
                  Read the authors with the books: a JOIN, or one query for all of them.

                The test's trace: %s#/t/%s
                Allowed here with @Tracepanel(allow = "n+1").""".formatted(Kit.console(), id)));
    }

    @Test
    void two_kinds_found_are_allowed_together() throws Exception {
        FakeAgent.ISSUES.put("shop.Examples$Found > nPlusOne()", trace -> List.of(
                Map.of("kind", "n+1", "title", "N+1: 4 similar queries · SELECT name FROM author WHERE id = ?", "count", 4),
                Map.of("kind", "duplicates", "title", "Duplicate queries: 2 identical · SELECT name FROM author WHERE id = ?",
                        "count", 2),
                Map.of("kind", "slow-sql", "title", "Slow query", "count", 1)));

        Map<String, String> outcomes = Kit.outcomes(Kit.run(Examples.Found.class));

        String id = (String) Kit.trace("shop.Examples$Found > nPlusOne()").get("id");
        assertThat(outcomes).isEqualTo(Map.of("nPlusOne()", """
                AssertionFailedError: tracepanel found 2 issues in this test:

                N+1: 4 similar queries · SELECT name FROM author WHERE id = ?

                Duplicate queries: 2 identical · SELECT name FROM author WHERE id = ?

                The test's trace: %s#/t/%s
                Allowed here with @Tracepanel(allow = {"duplicates", "n+1"}).""".formatted(Kit.console(), id)));
    }

    @Test
    void allow_and_fail_on_the_nearest_annotation_says_first() {
        for (String test : List.of("nPlusOne()", "nearestWins()", "stillAllowed()")) {
            nPlusOne("shop.Examples$Allowed > " + test);
            nPlusOne("shop.Examples$Allowed > Inner > " + test);
        }
        nPlusOne("shop.Examples$FailsOnNothing > nPlusOne()");

        Map<String, String> outcomes = new LinkedHashMap<>(Kit.outcomes(Kit.run(Examples.Allowed.class)));
        outcomes.putAll(Kit.outcomes(Kit.run(Examples.FailsOnNothing.class)));

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get("nearestWins()")).startsWith("AssertionFailedError: tracepanel found 1 issue in this test:");
        outcomes.remove("nearestWins()");
        assertThat(outcomes).isEqualTo(Map.of("nPlusOne()", "SUCCESSFUL", "stillAllowed()", "SUCCESSFUL"));
    }

    @Test
    void the_run_s_fail_on_setting_names_the_kinds_that_fail() {
        nPlusOne("shop.Examples$Found > nPlusOne()");

        assertThat(Kit.outcomes(Kit.run(Examples.Found.class, "tracepanel.failOn", "duplicates, slow-sql")))
                .isEqualTo(Map.of("nPlusOne()", "SUCCESSFUL"));

        assertThat(Kit.outcomes(Kit.run(Examples.Found.class, "tracepanel.failOn", "n+1 dupes")))
                .isEqualTo(Map.of("nPlusOne()", "ExtensionConfigurationException: tracepanel.failOn: unknown kind dupes (the kinds: "
                        + KINDS + ")"));
    }

    @Test
    void a_misspelled_kind_or_a_negative_limit_fails_the_test_before_it_runs() {
        int ran = Examples.RAN.get();

        assertThat(Kit.outcomes(Kit.run(Examples.Misspelled.class))).isEqualTo(Map.of(
                "lists()", "ExtensionConfigurationException: allow: unknown kind dupes, n+2 (the kinds: " + KINDS + ")",
                "negative()", "ExtensionConfigurationException: maxQueries must be a whole number, got -5"));

        assertThat(Examples.RAN.get()).isEqualTo(ran);
    }

    @Test
    void max_queries_counts_the_block_s_queries() {
        assertThat(Kit.outcomes(Kit.run(Examples.Helper.class))).isEqualTo(Map.of(
                "underTheLimit()", "SUCCESSFUL",
                "overTheLimit()", """
                        AssertionFailedError: 2 queries, at most 1 expected:
                          SELECT name FROM author ORDER BY id
                            at src/test/java/shop/Library.java:69 in strings
                          SELECT name FROM author ORDER BY id
                            at src/test/java/shop/Library.java:69 in strings"""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_failed_test_s_error_is_its_trace_s_an_aborted_one_isn_t_and_each_invocation_is_a_trace() throws Exception {
        assertThat(Kit.outcomes(Kit.run(Examples.Outcomes.class))).isEqualTo(Map.of(
                "fails()", "IllegalStateException: boom",
                "aborted()", "TestAbortedException: Assumption failed: not here",
                "[1] page = 1", "SUCCESSFUL",
                "[2] page = 2", "SUCCESSFUL"));

        Map<String, Object> failed = Kit.trace("shop.Examples$Outcomes > fails()");
        Map<String, Object> error = ((List<Map<String, Object>>) failed.get("errors")).get(0);
        assertThat(Map.of("status", failed.get("status"), "type", error.get("type"), "message", error.get("message"),
                "handled", error.get("handled")))
                .isEqualTo(Map.of("status", "error", "type", "java.lang.IllegalStateException", "message", "boom", "handled", false));

        Map<String, Object> aborted = Kit.trace("shop.Examples$Outcomes > aborted()");
        assertThat(Map.of("status", aborted.get("status"), "errors", aborted.get("errors")))
                .isEqualTo(Map.of("status", "ok", "errors", List.of()));

        assertThat(Kit.sql(Kit.trace("shop.Examples$Outcomes > each(int) > [1] page = 1"))).hasSize(1);
        assertThat(Kit.sql(Kit.trace("shop.Examples$Outcomes > each(int) > [2] page = 2"))).hasSize(1);
    }
}
