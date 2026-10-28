package shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * With the real agent, started by the SDK as in a project, and a real database (H2): the agent finds the N+1 the test
 * makes, and the test fails with it.
 */
class LiveTest {
    @Test
    void the_agent_finds_the_n_plus_one_and_the_test_fails_with_it() throws Exception {
        Library.authors();

        Map<String, String> outcomes = Kit.outcomes(Kit.run(Examples.Found.class));

        String console = Files.readString(Path.of(System.getenv("TRACEPANEL_DIR"), "console.addr")).trim();
        assertThat(outcomes.keySet()).containsExactly("nPlusOne()");
        assertThat(outcomes.get("nPlusOne()").replaceAll("#/t/[0-9A-Z]{26}", "#/t/ID")).isEqualTo("""
                AssertionFailedError: tracepanel found 1 issue in this test:

                N+1: 4 similar queries · SELECT author
                  SELECT name FROM author WHERE id = ?
                  at src/test/java/shop/Library.java:54 in titlesWithAuthors
                  Load the related rows with the parent rows (a join, or one batched query) instead of one query per row.

                The test's trace: %s#/t/ID
                Allowed here with @Tracepanel(allow = "n+1").""".formatted(console));
    }
}
