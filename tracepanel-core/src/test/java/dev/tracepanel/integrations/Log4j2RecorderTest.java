package dev.tracepanel.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tracepanel.core.Stacks;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import dev.tracepanel.core.Traces;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Log4j2RecorderTest {
    private static final Logger LOG = LogManager.getLogger("shop.orders");

    @BeforeAll
    static void install() {
        Stacks stacks = new Stacks(Path.of("/srv/shop"), List.of("dev.tracepanel.integrations"));
        Log4j2Recorder.install(stacks);
        // Twice, as a second app in the process would: still one appender.
        Log4j2Recorder.install(stacks);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> logs(Runnable work) {
        Trace trace = Traces.request("GET /orders");
        Trace before = Tracepanel.bind(trace);
        try {
            work.run();
        } finally {
            Tracepanel.bind(before);
        }
        return (List<Map<String, Object>>) trace.finish().get("logs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void records_a_log_with_its_level_logger_message_line_and_mdc() {
        List<Map<String, Object>> logs = logs(() -> {
            ThreadContext.put("orderId", "42");
            ThreadContext.put("sessionToken", "abc");
            try {
                LOG.info("placed {}", "order");
            } finally {
                ThreadContext.clearMap();
            }
        });

        assertEquals(1, logs.size());
        Map<String, Object> log = new LinkedHashMap<>(logs.get(0));
        Map<String, Object> attrs = new LinkedHashMap<>((Map<String, Object>) log.remove("attrs"));
        assertTrue(log.remove("span_id") instanceof String);
        assertTrue(log.remove("time") instanceof Long);
        assertTrue(((Integer) attrs.remove("code.lineno")) > 0);

        assertEquals(Map.of("level", "info", "logger", "shop.orders", "message", "placed order"),
                withNull(log, "exception"));

        assertEquals(Map.of(
                "code.filepath", "dev/tracepanel/integrations/Log4j2RecorderTest.java",
                "code.function", "lambda$records_a_log_with_its_level_logger_message_line_and_mdc$0",
                "thread.name", Thread.currentThread().getName(),
                "log.template", "placed {}",
                "extra.orderid", "42",
                "extra.sessiontoken", "********"), attrs);
    }

    @Test
    void maps_the_levels_and_records_the_throwable() {
        List<Map<String, Object>> logs = logs(() -> {
            LOG.trace("trace");
            LOG.debug("debug");
            LOG.warn("warn");
            LOG.error("charge failed", new IllegalStateException("declined"));
            LOG.fatal("fatal");
        });

        // trace is below the root logger's level (debug): Log4j drops it before any appender.
        assertEquals(List.of("debug", "warning", "error", "critical"),
                logs.stream().map(log -> log.get("level")).toList());

        String exception = (String) logs.get(2).get("exception");
        assertTrue(exception.startsWith("java.lang.IllegalStateException: declined\n\tat "), exception);
    }

    @Test
    void keeps_recording_once_after_log4j_is_reconfigured() {
        ((LoggerContext) LogManager.getContext(false)).reconfigure();

        List<Map<String, Object>> logs = logs(() -> LOG.info("after"));

        assertEquals(List.of("after"), logs.stream().map(log -> log.get("message")).toList());
    }

    @Test
    void records_nothing_outside_a_trace() {
        Trace trace = Traces.request("GET /orders");

        LOG.info("outside");

        assertEquals(List.of(), trace.finish().get("logs"));
    }

    /** The map without `key`, which must be there and null (Map.of takes no nulls). */
    private static Map<String, Object> withNull(Map<String, Object> map, String key) {
        assertTrue(map.containsKey(key) && map.get(key) == null, key);
        Map<String, Object> rest = new LinkedHashMap<>(map);
        rest.remove(key);
        return rest;
    }
}
