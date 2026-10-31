package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** The sampling profiler: a trace's stacks counted, as the trace format's `profile`. */
class ProfilerTest {
    private final Profiler profiler = new Profiler(new Stacks(Path.of("/srv/shop"), List.of("com.shop")), 1);

    private static StackTraceElement at(String className, String method, String file, int line) {
        return new StackTraceElement(className, method, file, line);
    }

    /** What a profiled trace says on its root span, profiled by `profile` under the settings `env`. */
    private static Object reason(Map<String, String> env, BiConsumer<Tracepanel, Trace> profile) {
        Map<String, String> settings = new HashMap<>(env);
        settings.put("TRACEPANEL_AGENT", "off");
        Tracepanel tracepanel = new Tracepanel(Config.load(settings, "shop"), List.of(), settings);
        try {
            Trace trace = tracepanel.begin("http", "GET /", Map.of());
            profile.accept(tracepanel, trace);
            return trace.attr("profile.reason");
        } finally {
            Tracepanel.bind(null);
        }
    }

    @Test
    void a_profiled_trace_says_why_on_its_root_span() {
        Map<String, String> none = Map.of();

        assertEquals("setting", reason(Map.of("TRACEPANEL_PROFILE", "1"), (tracepanel, trace) -> {}));
        assertEquals("code", reason(none, (tracepanel, trace) -> tracepanel.profile(trace)));
        assertEquals("header", reason(none, (tracepanel, trace) -> tracepanel.profile(trace, "1")));
        assertEquals("cookie", reason(none, (tracepanel, trace) -> tracepanel.profile(trace, "memory", Profiler.COOKIE_REASON)));
        assertEquals("parent", reason(none, (tracepanel, trace) -> tracepanel.profile(trace, "1", Profiler.PARENT)));

        // What started its profile stays its reason; nothing asked, nothing profiled, no reason.
        assertEquals("setting", reason(Map.of("TRACEPANEL_PROFILE", "1"), (tracepanel, trace) -> tracepanel.profile(trace, "1")));
        assertNull(reason(none, (tracepanel, trace) -> tracepanel.profile(trace, null)));
        assertNull(reason(Map.of("TRACEPANEL_PROFILE", "1", "TRACEPANEL_PROFILE_ENABLED", "0"), (tracepanel, trace) -> {}));
        assertNull(reason(Map.of("TRACEPANEL_PROFILE_ENABLED", "0"), (tracepanel, trace) -> tracepanel.profile(trace)));
    }

    /** What `profile` (the app's code) found in a trace begun under the settings `env`: its answer, reason, ask. */
    private static List<Object> byCode(Map<String, String> env, BiConsumer<Tracepanel, Trace> before) {
        Map<String, String> settings = new HashMap<>(env);
        settings.put("TRACEPANEL_AGENT", "off");
        Tracepanel tracepanel = new Tracepanel(Config.load(settings, "shop"), List.of(), settings);
        try {
            Trace trace = tracepanel.begin("http", "GET /", Map.of());
            before.accept(tracepanel, trace);
            boolean profiled = tracepanel.profiledByCode(Tracepanel.current());
            Messages.Publish publish = Messages.publish("kafka");
            publish.handedOver();
            String call = HttpCalls.start(trace, "GET", URI.create("http://127.0.0.1:1/")).profile();
            return Arrays.asList(profiled, trace.attr("profile.reason"), call, publish.profile());
        } finally {
            Tracepanel.bind(null);
        }
    }

    @Test
    void the_app_s_code_profiles_the_rest_of_its_trace_and_its_calls_and_messages_ask_for_a_profile_too() {
        Map<String, String> none = Map.of();

        assertEquals(Arrays.asList(true, "code", "1", "1"), byCode(none, (tracepanel, trace) -> {}));

        // Profiled already: its reason stays, and what its request asked for goes on.
        assertEquals(Arrays.asList(true, "setting", "1", "1"), byCode(Map.of("TRACEPANEL_PROFILE", "1"), (tracepanel, trace) -> {}));
        assertEquals(Arrays.asList(true, "header", "memory", "memory"),
                byCode(none, (tracepanel, trace) -> tracepanel.profile(trace, "memory")));

        // Profiling off: nothing profiled, nothing asked.
        assertEquals(Arrays.asList(false, null, null, null),
                byCode(Map.of("TRACEPANEL_PROFILE_ENABLED", "0"), (tracepanel, trace) -> {}));
    }

    @Test
    void the_app_s_code_profiles_nothing_outside_a_trace_or_with_tracepanel_off() {
        Map<String, String> settings = Map.of("TRACEPANEL_AGENT", "off");
        Tracepanel tracepanel = new Tracepanel(Config.load(settings, "shop"), List.of(), settings);

        assertEquals(false, tracepanel.profiledByCode(null));
        assertEquals(false, Tracepanel.profile());

        // No Tracepanel started in this process: it is off, and a trace bound here stays as it was.
        Trace trace = new Trace("http", "GET /", Map.of());
        Tracepanel.bind(trace);
        try {
            assertNull(Tracepanel.get());
            assertEquals(false, Tracepanel.profile());
            assertNull(trace.attr("profile.reason"));
            assertNull(trace.asked);
        } finally {
            Tracepanel.bind(null);
        }
    }

    @Test
    void a_request_says_why_it_asks_its_header_from_another_app_s_trace_its_parent() {
        assertEquals("header", Profiler.reason("1", "tracepanel_profile=1", false));
        assertEquals("parent", Profiler.reason("memory", null, true));
        assertEquals("cookie", Profiler.reason("0", "theme=dark; tracepanel_profile=1", true));
        assertNull(Profiler.reason(null, "tracepanel_profile=0", false));
    }

    @Test
    void a_request_asks_by_its_header_or_the_panel_s_cookie() {
        assertTrue(Profiler.requested("1", null));
        assertTrue(Profiler.requested(" 1 ", null));
        assertTrue(Profiler.requested(null, "theme=dark; tracepanel_profile=1"));

        assertEquals(false, Profiler.requested("0", null));
        assertEquals(false, Profiler.requested(null, "tracepanel_profile=0"));
        assertEquals(false, Profiler.requested(null, "not_tracepanel_profile=1"));
        assertEquals(false, Profiler.requested(null, null));
    }

    @Test
    void what_a_request_asks_for_is_the_profile_or_its_memory_too_by_its_header_else_the_cookie() {
        assertEquals("1", Profiler.asked("1", null));
        assertEquals("memory", Profiler.asked(" memory ", null));
        assertEquals("memory", Profiler.asked(null, "theme=dark; tracepanel_profile=memory"));
        assertEquals("1", Profiler.asked(null, "tracepanel_profile=1"));
        // The header wins over the cookie.
        assertEquals("1", Profiler.asked("1", "tracepanel_profile=memory"));
        assertEquals("memory", Profiler.asked("0", "tracepanel_profile=memory"));
        // "memory" profiles the time here: this SDK has no memory profile.
        assertTrue(Profiler.requested("memory", null));

        assertNull(Profiler.asked("0", null));
        assertNull(Profiler.asked("yes", "tracepanel_profile=2"));
        assertNull(Profiler.asked(null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stacks_are_counted_outermost_first_each_function_once_without_tracepanel_s_frames() {
        Profiler.Recording recording = new Profiler.Recording();
        // As the JVM gives a stack: the innermost call first.
        StackTraceElement[] list = {
                at("com.shop.BookService", "list", "BookService.java", 42),
                at("dev.tracepanel.core.Jdbc", "query", "Jdbc.java", 10),
                at("com.shop.BookController", "books", "BookController.java", 20),
                at("java.lang.Thread", "run", "Thread.java", 1583)};
        StackTraceElement[] render = {
                at("com.shop.BookController", "books", "BookController.java", 18),
                at("java.lang.Thread", "run", "Thread.java", 1583)};
        profiler.count(recording, list);
        profiler.count(recording, render);
        profiler.count(recording, render);

        Map<String, Object> profile = profiler.document(recording);

        assertEquals(Map.of(
                "interval_us", 1000L,
                "samples", 3,
                "frames", List.of(
                        Map.of("function", "Thread.run", "module", "java.lang.Thread", "file", "java/lang/Thread.java",
                                "line", 1583, "in_app", false),
                        // Its line is the first of it the samples saw.
                        Map.of("function", "BookController.books", "module", "com.shop.BookController",
                                "file", "com/shop/BookController.java", "line", 18, "in_app", true),
                        Map.of("function", "BookService.list", "module", "com.shop.BookService",
                                "file", "com/shop/BookService.java", "line", 42, "in_app", true)),
                "stacks", List.of(
                        Map.of("frames", List.of(0, 1), "count", 2),
                        Map.of("frames", List.of(0, 1, 2), "count", 1))), profile);
    }

    @Test
    @SuppressWarnings("unchecked")
    void past_its_limit_a_new_stack_counts_as_other_and_a_deep_one_keeps_its_outermost_frames() {
        Profiler.Recording recording = new Profiler.Recording();
        for (int n = 0; n < Profiler.MAX_STACKS + 3; n++) {
            profiler.count(recording, new StackTraceElement[] {at("com.shop.Step", "step" + n, "Step.java", 1)});
        }
        Profiler.Recording deep = new Profiler.Recording();
        StackTraceElement[] recursion = new StackTraceElement[Profiler.MAX_DEPTH + 50];
        for (int i = 0; i < recursion.length; i++) recursion[i] = at("com.shop.Tree", "walk", "Tree.java", recursion.length - i);
        profiler.count(deep, recursion);

        Map<String, Object> profile = profiler.document(recording);
        List<Map<String, Object>> stacks = (List<Map<String, Object>>) profile.get("stacks");
        List<Map<String, Object>> frames = (List<Map<String, Object>>) profile.get("frames");
        Map<String, Object> other = frames.get(((List<Integer>) stacks.get(0).get("frames")).get(0));
        Map<String, Object> deepStack = ((List<Map<String, Object>>) profiler.document(deep).get("stacks")).get(0);

        assertEquals(Profiler.MAX_STACKS + 3, profile.get("samples"));
        assertEquals(Profiler.MAX_STACKS + 1, stacks.size());
        assertEquals(3, stacks.get(0).get("count"));
        assertEquals(Map.of("function", "(other)", "file", "", "line", 0, "in_app", false), Map.of(
                "function", other.get("function"), "file", other.get("file"), "line", other.get("line"), "in_app", other.get("in_app")));
        assertNull(other.get("module"));

        assertEquals(Profiler.MAX_DEPTH, ((List<Integer>) deepStack.get("frames")).size());
    }

    @Test
    void no_sample_no_profile() {
        assertNull(profiler.document(new Profiler.Recording()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_thread_running_a_profiled_trace_is_sampled_until_it_stops() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread busy = new Thread(() -> {
            long sum = 0;
            while (running.get()) sum += Math.floorMod(System.nanoTime(), 7);
            if (sum == 42) System.out.print("");
        }, "busy");
        busy.start();
        Profiler.Recording recording = profiler.start(busy);
        Thread.sleep(100);
        Map<String, Object> profile = profiler.stop(recording);
        running.set(false);
        busy.join();

        List<Map<String, Object>> frames = (List<Map<String, Object>>) profile.get("frames");

        assertTrue((Integer) profile.get("samples") > 10, profile.toString());
        assertTrue(frames.stream().anyMatch(frame -> frame.get("function").equals("Thread.run")), frames.toString());
        // The test's own lambda is in dev.tracepanel, whose frames say nothing about an app.
        assertTrue(frames.stream().noneMatch(frame -> String.valueOf(frame.get("module")).startsWith("dev.tracepanel.")), frames.toString());
    }
}
