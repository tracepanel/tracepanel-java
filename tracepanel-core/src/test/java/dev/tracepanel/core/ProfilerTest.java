package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** The sampling profiler: a trace's stacks counted, as the trace format's `profile`. */
class ProfilerTest {
    private final Profiler profiler = new Profiler(new Stacks(Path.of("/srv/shop"), List.of("com.shop")), 1);

    private static StackTraceElement at(String className, String method, String file, int line) {
        return new StackTraceElement(className, method, file, line);
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
