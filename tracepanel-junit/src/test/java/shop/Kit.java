package shop;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import dev.tracepanel.testing.FakeAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;

/** Runs example tests as a project's run would, and reads what came of them. */
final class Kit {
    private Kit() {}

    /** The tests of `type`, run with these configuration parameters (name, value, ...). */
    static Events run(Class<?> type, String... parameters) {
        Map<String, String> config = new LinkedHashMap<>();
        for (int i = 0; i + 1 < parameters.length; i += 2) config.put(parameters[i], parameters[i + 1]);
        return EngineTestKit.engine("junit-jupiter").selectors(selectClass(type)).configurationParameters(config)
                .execute().testEvents();
    }

    /** Each test that finished, by its display name: "SUCCESSFUL", "ABORTED", or the failure's class and message. */
    static Map<String, String> outcomes(Events events) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (Event event : events.finished().list()) {
            TestExecutionResult result = event.getRequiredPayload(TestExecutionResult.class);
            String outcome = result.getThrowable()
                    .map(error -> error.getClass().getSimpleName() + ": " + error.getMessage())
                    .orElse(result.getStatus().name());
            outcomes.put(event.getTestDescriptor().getDisplayName(), outcome);
        }
        return outcomes;
    }

    /** The console the fake agent serves. */
    static String console() throws IOException {
        return Files.readString(Path.of(System.getenv("TRACEPANEL_DIR"), "console.addr")).trim();
    }

    /** The latest trace named so, once it was sent: an example run again sends another. */
    static Map<String, Object> trace(String name) throws InterruptedException {
        FakeAgent.trace(name);
        List<Map<String, Object>> named = FakeAgent.TRACES.stream().filter(trace -> name.equals(trace.get("name"))).toList();
        return named.get(named.size() - 1);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> spans(Map<String, Object> trace) {
        return (List<Map<String, Object>>) trace.get("spans");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> attrs(Map<String, Object> traceOrSpan) {
        return (Map<String, Object>) traceOrSpan.get("attrs");
    }

    static List<Map<String, Object>> sql(Map<String, Object> trace) {
        return spans(trace).stream().filter(span -> "sql".equals(span.get("kind"))).toList();
    }
}
