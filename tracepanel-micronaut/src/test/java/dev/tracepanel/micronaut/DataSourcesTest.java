package dev.tracepanel.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class DataSourcesTest {
    @Test
    void namedPoolsAreWrappedAndTheirQueriesAreRecorded() throws Exception {
        try (ApplicationContext context = ApplicationContext.builder().deduceEnvironment(false).environments("test")
                .properties(Map.of("tracepanel.enabled", true,
                        "datasources.default.url", "jdbc:h2:mem:first;DB_CLOSE_DELAY=-1",
                        "datasources.default.driver-class-name", "org.h2.Driver",
                        "datasources.second.url", "jdbc:h2:mem:second;DB_CLOSE_DELAY=-1",
                        "datasources.second.driver-class-name", "org.h2.Driver")).start()) {
            Tracepanel recorder = context.getBean(Tracepanel.class);
            assertNotNull(recorder);
            List<Trace> traces = new CopyOnWriteArrayList<>();
            CountDownLatch finished = new CountDownLatch(2);
            Tracepanel.tests(new Tracepanel.Tests() {
                public void begun(Trace trace) {}
                public Trace caller() { return null; }
                public boolean finished(Trace trace, Map<String, Object> document) { traces.add(trace); finished.countDown(); return false; }
            });
            try {
                Trace trace = recorder.begin("command", "two databases", Map.of());
                for (String name : List.of("default", "second")) {
                    DataSource pool = context.getBean(DataSource.class, Qualifiers.byName(name));
                    try (var connection = pool.getConnection(); var query = connection.prepareStatement("SELECT 1");
                            var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                    }
                }
                recorder.finish(trace);
                long queries = trace.spans().stream().filter(span -> "sql".equals(span.get("kind"))).count();
                assertEquals(2, queries);
                TracepanelServerFilter filter = new TracepanelServerFilter(recorder, "", request -> "http://localhost",
                () -> { throw new AssertionError("No route lookup expected for a synthetic response"); });
                Flux.from(filter.doFilter(HttpRequest.GET("/reactive"), request -> Mono.<io.micronaut.http.MutableHttpResponse<?>>fromCallable(() -> {
                    assertNotNull(Tracepanel.current());
                    DataSource pool = context.getBean(DataSource.class);
                    try (var connection = pool.getConnection(); var query = connection.prepareStatement("SELECT 2");
                            var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                    }
                    return HttpResponse.ok("reactive");
                }).subscribeOn(Schedulers.boundedElastic()))).blockLast();
                assertNull(Tracepanel.current());
                assertTrue(finished.await(5, TimeUnit.SECONDS));
                assertEquals(2, traces.size());
                assertEquals(1, traces.get(1).spans().stream().filter(span -> "sql".equals(span.get("kind"))).count());
            } finally {
                Tracepanel.bind(null);
                Tracepanel.tests(null);
            }
        }
    }

    @Test
    void productionContextHasNoSdkBeans() {
        try (ApplicationContext context = ApplicationContext.builder().deduceEnvironment(false)
                .environments("production").start()) {
            assertTrue(context.findBean(Tracepanel.class).isEmpty());
            assertTrue(context.findBean(TracepanelServerFilter.class).isEmpty());
            assertTrue(context.findBean(TracepanelClientFilter.class).isEmpty());
        }
    }
}
