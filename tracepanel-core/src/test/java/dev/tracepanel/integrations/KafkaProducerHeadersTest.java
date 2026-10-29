package dev.tracepanel.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tracepanel.core.Messages;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import dev.tracepanel.core.Traces;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A record sent in a trace: the headers it carries to the app that receives it. */
class KafkaProducerHeadersTest {
    @AfterEach
    void unbind() {
        Messages.Publish publish = Messages.publishing();
        if (publish != null) publish.handedOver();
        Tracepanel.bind(null);
    }

    /** The headers of a record sent in `trace` with `own` headers of the app's, by name: the last of each. */
    private static Map<String, String> sent(Trace trace, String... own) {
        Tracepanel.bind(trace);
        Messages.Publish publish = Messages.publish("kafka");
        ProducerRecord<Object, Object> record = new ProducerRecord<>("books", "Dune");
        for (int i = 0; i < own.length; i += 2) record.headers().add(own[i], own[i + 1].getBytes(StandardCharsets.UTF_8));
        new KafkaProducerHeaders().onSend(record);
        if (publish != null) publish.handedOver();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        return headers;
    }

    @Test
    void a_record_asks_for_the_profile_its_trace_s_request_asked_for() {
        Map<String, String> time = sent(Traces.asking("GET /time", "1"));
        Map<String, String> memory = sent(Traces.asking("GET /memory", "memory"));

        assertEquals("1", time.get("tracepanel-profile"));
        assertEquals("memory", memory.get("tracepanel-profile"));
        assertEquals(List.of("traceparent", "tracepanel-sent-by", "tracepanel-task-id", "tracepanel-profile"),
                List.copyOf(time.keySet()));
    }

    @Test
    void a_trace_its_request_didn_t_ask_to_profile_asks_nothing_of_its_records() {
        Map<String, String> headers = sent(Traces.request("GET /"));

        assertEquals(List.of("traceparent", "tracepanel-sent-by", "tracepanel-task-id"), List.copyOf(headers.keySet()));
    }

    @Test
    void the_app_s_own_headers_are_the_ones_that_go() {
        String own = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        Map<String, String> profile = sent(Traces.asking("GET /", "memory"), "tracepanel-profile", "1");
        Map<String, String> traceparent = sent(Traces.asking("GET /", "memory"), "traceparent", own);

        assertEquals("1", profile.get("tracepanel-profile"));
        assertEquals(List.of("tracepanel-profile", "traceparent", "tracepanel-sent-by", "tracepanel-task-id"),
                List.copyOf(profile.keySet()));

        // Its own traceparent: the trace context is the app's, the profile asked for still goes.
        assertEquals(Map.of("traceparent", own, "tracepanel-profile", "memory"), traceparent);
    }

    @Test
    void outside_a_trace_a_record_carries_nothing() {
        assertEquals(Map.of(), sent(null));
    }
}
