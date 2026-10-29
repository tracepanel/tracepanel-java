package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A message the app sends: what it asks of the app that receives it, a profile its trace's request asked for too. */
class MessagesTest {
    @AfterEach
    void unbind() {
        Messages.Publish publish = Messages.publishing();
        if (publish != null) publish.handedOver();
        Tracepanel.bind(null);
    }

    /** A publish in `trace`, which hands its message over at once. */
    private static Messages.Publish publish(Trace trace) {
        Tracepanel.bind(trace);
        Messages.Publish publish = Messages.publish("kafka");
        publish.handedOver();
        return publish;
    }

    @Test
    void a_message_asks_for_the_profile_its_trace_s_request_asked_for() {
        Trace time = new Trace("http", "GET /time", Map.of());
        time.asked = "1";
        Trace memory = new Trace("http", "GET /memory", Map.of());
        memory.asked = "memory";

        assertEquals("1", publish(time).profile());
        assertEquals("memory", publish(memory).profile());
    }

    @Test
    void a_trace_its_request_didn_t_ask_to_profile_asks_nothing_of_its_messages() {
        // Profiled by the setting (TRACEPANEL_PROFILE) or not at all: nothing asked, each app's setting decides.
        assertNull(publish(new Trace("http", "GET /", Map.of())).profile());
    }

    @Test
    void the_header_is_the_one_the_other_sdks_read() {
        assertEquals("tracepanel-profile", Messages.PROFILE);
    }
}
