package shop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tracepanel.testing.FakeAgent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The extension on a Ktor app's test: the calls its testApplication client makes are linked to it. */
class KtorTest {
    @BeforeAll
    static void agent() throws Exception {
        FakeAgent.start();
        Library.authors();
    }

    @Test
    void a_test_application_call_is_a_call_of_the_test_and_its_trace_is_linked_to_it() throws Exception {
        assertThat(Kit.outcomes(Kit.run(KtorExamples.class))).isEqualTo(Map.of("books()", "SUCCESSFUL"));

        Map<String, Object> test = Kit.trace("shop.KtorExamples > books()");
        Object id = Kit.attrs(test).get("w3c.trace_id");
        List<Map<String, Object>> requests = FakeAgent.TRACES.stream().filter(trace -> !"test".equals(trace.get("kind")))
                .filter(trace -> id.equals(Kit.attrs(trace).get("w3c.trace_id"))).toList();
        assertThat(requests.stream().map(trace -> trace.get("name") + " " + Kit.sql(trace).size()).toList())
                .isEqualTo(List.of("GET /books 5", "GET /hello 0"));
        assertThat(Kit.spans(test).stream().filter(span -> "http.client".equals(span.get("kind")))
                .map(span -> span.get("name") + " " + Kit.attrs(span).get("http.response.status_code")).toList())
                .isEqualTo(List.of("GET localhost/books 200", "GET localhost/hello 200"));
        assertThat(Kit.sql(test)).hasSize(1);
    }
}
