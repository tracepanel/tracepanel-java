package shop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tracepanel.testing.FakeAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The extension on a Spring Boot app's tests: the requests they make are linked to them, in each kind of test. */
class SpringTest {
    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
        // The examples' own database made, outside any test: its tables aren't a test's queries.
        Library.authors();
    }

    @AfterEach
    void issues() {
        FakeAgent.ISSUES.clear();
    }

    /** The app's traces of the requests a test made, by the W3C trace id of the test's trace. */
    private static List<Map<String, Object>> requests(Map<String, Object> test) {
        Object id = Kit.attrs(test).get("w3c.trace_id");
        return FakeAgent.TRACES.stream().filter(trace -> !"test".equals(trace.get("kind")))
                .filter(trace -> id.equals(Kit.attrs(trace).get("w3c.trace_id"))).toList();
    }

    /** The test's calls of the app: its http.client spans, as "GET localhost/books 200". */
    private static List<String> calls(Map<String, Object> test) {
        return Kit.spans(test).stream().filter(span -> "http.client".equals(span.get("kind")))
                .map(span -> span.get("name") + " " + Kit.attrs(span).get("http.response.status_code")).toList();
    }

    @Test
    void a_mock_mvc_request_is_a_call_of_the_test_and_its_trace_is_linked_to_it() throws Exception {
        FakeAgent.ISSUES.put("GET /books", trace -> List.of(Map.of(
                "kind", "n+1",
                "title", "N+1: 4 similar queries · SELECT name FROM author WHERE id = ?",
                "culprit", "src/test/java/shop/Books.java:20 in lambda$books$0",
                "count", 4,
                "span_ids", Kit.sql(trace).stream().skip(1).map(span -> span.get("id")).toList())));

        Map<String, String> outcomes = Kit.outcomes(Kit.run(SpringExamples.WithMockMvc.class));

        Map<String, Object> test = Kit.trace("shop.SpringExamples$WithMockMvc > books(TestTrace)");
        List<Map<String, Object>> requests = requests(test);
        assertThat(requests.stream().map(trace -> trace.get("name")).toList()).isEqualTo(List.of("GET /books", "GET /books"));
        assertThat(calls(test)).isEqualTo(List.of("GET localhost/books 200", "GET localhost/books 200"));

        Map<String, Object> call = Kit.spans(test).stream().filter(span -> "http.client".equals(span.get("kind"))).findFirst().orElseThrow();
        assertThat(Kit.attrs(requests.get(0)).get("w3c.parent_span_id")).isEqualTo(Kit.attrs(call).get("w3c.span_id"));

        assertThat(outcomes).isEqualTo(Map.of("books(TestTrace)", """
                AssertionFailedError: tracepanel found 2 issues in this test:

                N+1: 4 similar queries · SELECT name FROM author WHERE id = ?
                  SELECT name FROM author WHERE id = ?
                  at src/test/java/shop/Books.java:20 in lambda$books$0
                  in GET /books → %1$s#/t/%2$s

                N+1: 4 similar queries · SELECT name FROM author WHERE id = ?
                  SELECT name FROM author WHERE id = ?
                  at src/test/java/shop/Books.java:20 in lambda$books$0
                  in GET /books → %1$s#/t/%3$s

                The test's trace: %1$s#/t/%4$s
                Allowed here with @Tracepanel(allow = "n+1").""".formatted(
                Kit.console(), requests.get(0).get("id"), requests.get(1).get("id"), test.get("id"))));
    }

    @Test
    void a_request_over_http_from_test_rest_template_or_web_test_client_is_linked_to_the_test() throws Exception {
        assertThat(Kit.outcomes(Kit.run(SpringExamples.OverHttp.class)))
                .isEqualTo(Map.of("testRestTemplate(TestTrace)", "SUCCESSFUL", "webTestClient(TestTrace)", "SUCCESSFUL"));

        for (String name : List.of("testRestTemplate(TestTrace)", "webTestClient(TestTrace)")) {
            Map<String, Object> test = Kit.trace("shop.SpringExamples$OverHttp > " + name);
            assertThat(requests(test).stream().map(trace -> trace.get("name")).toList()).isEqualTo(List.of("GET /books"));
            assertThat(calls(test)).isEqualTo(List.of("GET localhost:8261/books 200"));
        }
    }

    @Test
    void in_a_web_mvc_slice_the_request_is_linked_and_its_page_has_no_widget() throws Exception {
        assertThat(Kit.outcomes(Kit.run(SpringExamples.Slice.class))).isEqualTo(Map.of("page()", "SUCCESSFUL"));

        Map<String, Object> test = Kit.trace("shop.SpringExamples$Slice > page()");
        assertThat(requests(test).stream().map(trace -> trace.get("name")).toList()).isEqualTo(List.of("GET /hello"));
    }

    @Test
    void in_a_jdbc_slice_the_queries_are_counted() {
        assertThat(Kit.outcomes(Kit.run(SpringExamples.Jdbc.class))).isEqualTo(Map.of("twoQueries()", """
                AssertionFailedError: 2 queries, at most 1 expected:
                  SELECT name FROM author ORDER BY id
                    at src/test/java/shop/SpringExamples.java:95 in twoQueries
                  SELECT title FROM book ORDER BY id
                    at src/test/java/shop/SpringExamples.java:96 in twoQueries"""));
    }
}
