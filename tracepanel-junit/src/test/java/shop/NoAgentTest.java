package shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Without an agent (TRACEPANEL_AGENT=off): one warning, findings not checked, query counts still are. */
class NoAgentTest {
    @Test
    void the_run_warns_once_and_still_counts_the_queries() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        ByteArrayOutputStream warned = new ByteArrayOutputStream();
        Map<String, String> outcomes = new LinkedHashMap<>();
        try {
            System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(warned, true, StandardCharsets.UTF_8));
            outcomes.putAll(Kit.outcomes(Kit.run(Examples.Found.class)));
            outcomes.putAll(Kit.outcomes(Kit.run(Examples.TooMany.class)));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertThat(outcomes).isEqualTo(Map.of(
                "nPlusOne()", "SUCCESSFUL",
                "lists()", """
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

        assertThat(said.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("tracepanel:")).toList())
                .containsExactly("tracepanel: each test a trace · fails on duplicates, n+1 · no agent: findings not checked");
        assertThat(warned.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("tracepanel")).toList())
                .containsExactly("tracepanel: the agent is off (TRACEPANEL_AGENT=off). The tests run without the agent's findings.");
    }
}
