package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** A profiled trace's memory and the collector's runs: the profile's `memory` and `gc`. */
class MemoryTest {
    private static final int MB = 1024 * 1024;
    // What the traces keep until they end, so the heap holds it.
    private static final List<byte[]> KEPT = new ArrayList<>();

    /** Allocates `megabytes` and keeps them: a trace's own allocations. */
    private static void allocate(int megabytes) {
        for (int i = 0; i < megabytes; i++) KEPT.add(new byte[MB]);
    }

    /** The profile of a trace begun under the settings `env`, profiled by `profile`, that allocated 16 MB. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> profiled(Map<String, String> env, BiConsumer<Tracepanel, Trace> profile) {
        Map<String, String> settings = new HashMap<>(env);
        settings.put("TRACEPANEL_AGENT", "off");
        Tracepanel tracepanel = new Tracepanel(Config.load(settings, "shop"), List.of(), settings);
        try {
            Trace trace = tracepanel.begin("http", "GET /report", Map.of());
            profile.accept(tracepanel, trace);
            allocate(16);
            tracepanel.finish(trace);
            return (Map<String, Object>) trace.finish().get("profile");
        } finally {
            Tracepanel.bind(null);
            KEPT.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> memory(Map<String, Object> profile) {
        // A short trace no sample saw has no profile, unless its memory was measured.
        return profile == null ? null : (Map<String, Object>) profile.get("memory");
    }

    @Test
    void a_trace_that_asks_for_its_memory_has_what_its_thread_allocated_and_the_heap_s_peak_and_net() {
        Map<String, Object> memory = memory(profiled(Map.of(), (tracepanel, trace) -> tracepanel.profile(trace, "memory")));

        assertEquals(List.of("peak", "net", "allocated", "shared"), List.copyOf(memory.keySet()));

        // The thread's own count: at least the 16 MB it kept, a little more for the list and the rest.
        long allocated = (Long) memory.get("allocated");
        assertTrue(allocated >= 16L * MB && allocated < 24L * MB, memory.toString());
        // The heap's level: the peak above the start holds the 16 MB, unless a collection freed older garbage meanwhile.
        assertTrue((Long) memory.get("peak") >= 0, memory.toString());
        assertEquals(Boolean.class, memory.get("shared").getClass());
    }

    @Test
    void its_memory_is_measured_when_asked_by_the_request_by_code_or_by_the_settings_and_only_then() {
        Map<String, String> none = Map.of();
        Map<String, String> everyTrace = Map.of("TRACEPANEL_PROFILE", "1");

        assertTrue(memory(profiled(none, (tracepanel, trace) -> tracepanel.profile(trace, "memory", Profiler.COOKIE_REASON))) != null);
        assertTrue(memory(profiled(none, (tracepanel, trace) -> tracepanel.profiledByCode(Tracepanel.current(), true))) != null);
        assertTrue(memory(profiled(Map.of("TRACEPANEL_PROFILE_MEMORY", "1"), (tracepanel, trace) -> tracepanel.profile(trace, "1"))) != null);
        assertTrue(memory(profiled(Map.of("TRACEPANEL_PROFILE", "1", "TRACEPANEL_PROFILE_MEMORY", "1"), (tracepanel, trace) -> {})) != null);
        // Profiled by the setting already, then its request asks for its memory: measured from then on.
        assertTrue(memory(profiled(everyTrace, (tracepanel, trace) -> tracepanel.profile(trace, "memory"))) != null);

        assertNull(memory(profiled(none, (tracepanel, trace) -> tracepanel.profile(trace, "1"))));
        assertNull(memory(profiled(none, (tracepanel, trace) -> tracepanel.profiledByCode(trace, false))));
        assertNull(memory(profiled(everyTrace, (tracepanel, trace) -> {})));
        assertNull(profiled(Map.of("TRACEPANEL_PROFILE_MEMORY", "1"), (tracepanel, trace) -> {}));
    }

    @Test
    void the_app_s_code_asking_for_memory_asks_its_calls_for_it_too() {
        Map<String, String> settings = Map.of("TRACEPANEL_AGENT", "off");
        Tracepanel tracepanel = new Tracepanel(Config.load(settings, "shop"), List.of(), settings);
        try {
            Trace time = tracepanel.begin("http", "GET /", Map.of());
            tracepanel.profile(time, "1");
            tracepanel.profiledByCode(Tracepanel.current(), true);
            tracepanel.finish(time);
            Trace memory = tracepanel.begin("http", "GET /", Map.of());
            tracepanel.profile(memory, "memory");
            tracepanel.profiledByCode(Tracepanel.current(), false);
            tracepanel.finish(memory);

            assertEquals(List.of("memory", "memory"), List.of(time.asked, memory.asked));
        } finally {
            Tracepanel.bind(null);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_trace_that_moves_to_another_thread_counts_what_it_allocated_there() throws Exception {
        Map<String, Object> memory = memory(profiled(Map.of(), (tracepanel, trace) -> {
            tracepanel.profile(trace, "memory");
            Thread other = new Thread(() -> {
                Trace before = Tracepanel.bind(trace);
                try {
                    allocate(32);
                } finally {
                    Tracepanel.bind(before);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }));

        // 32 MB on the other thread while it ran the trace, and 16 MB here.
        long allocated = (Long) memory.get("allocated");
        assertTrue(allocated >= 48L * MB && allocated < 56L * MB, memory.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void every_profile_has_the_collector_s_runs_while_the_trace_ran() throws Exception {
        Map<String, Object> profile = profiled(Map.of(), (tracepanel, trace) -> {
            tracepanel.profile(trace, "1");
            System.gc();
            try {
                // The collector's notification comes from its own thread, a moment after the collection.
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        });
        Map<String, Object> gc = (Map<String, Object>) profile.get("gc");
        List<List<Long>> pauses = (List<List<Long>>) gc.get("pauses");

        assertEquals(List.of("collections", "pause_us", "pauses"), List.copyOf(gc.keySet()));
        assertTrue((Long) gc.get("collections") >= 1, gc.toString());
        assertTrue((Long) gc.get("pause_us") >= 0, gc.toString());
        assertFalse(pauses.isEmpty(), gc.toString());
        // Each pause: when, from the trace's start, how long, and the generation: 0 a young collection, 2 the whole
        // heap, as System.gc() collects it.
        for (List<Long> pause : pauses) {
            assertEquals(3, pause.size());
            assertTrue(pause.get(0) >= 0 && pause.get(0) < 5_000_000 && pause.get(1) >= 0, pause.toString());
            assertTrue(pause.get(2) == 0 || pause.get(2) == 2, pause.toString());
        }
        assertTrue(pauses.stream().anyMatch(pause -> pause.get(2) == 2), pauses.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_trace_no_sample_saw_still_has_its_memory() {
        Map<String, String> settings = Map.of("TRACEPANEL_AGENT", "off", "TRACEPANEL_PROFILE_INTERVAL_MS", "60000");
        Tracepanel tracepanel = new Tracepanel(Config.load(settings, "shop"), List.of(), settings);
        try {
            Trace trace = tracepanel.begin("http", "GET /", Map.of());
            tracepanel.profile(trace, "memory");
            tracepanel.finish(trace);
            Map<String, Object> profile = (Map<String, Object>) trace.finish().get("profile");

            assertEquals(List.of("interval_us", "samples", "frames", "stacks", "memory", "gc"), List.copyOf(profile.keySet()));
            assertEquals(List.of(60_000_000L, 0, List.of(), List.of()), List.of(
                    profile.get("interval_us"), profile.get("samples"), profile.get("frames"), profile.get("stacks")));
        } finally {
            Tracepanel.bind(null);
        }
    }
}
