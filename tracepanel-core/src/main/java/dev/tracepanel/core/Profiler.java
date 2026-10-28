package dev.tracepanel.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Where a trace's time goes in code: a sampling profiler (the trace's `profile`, a flame graph in the console), as
 * the Python SDK's.
 *
 * <p>One daemon thread looks at the stack of the thread running each profiled trace every `profileIntervalMs`
 * (Thread.getStackTrace), and counts each stack it sees. It starts with the first profiled trace and ends once none
 * has run for a while: a trace nobody asked to profile costs nothing. A trace is sampled on the thread it is bound to
 * now (Tracepanel.current), so an async request or a coroutine is followed across the threads it resumes on, and a
 * thread back in its pool, serving another request, is not sampled for it.
 */
public final class Profiler {
    public static final String HEADER = "X-Tracepanel-Profile";
    public static final String COOKIE = "tracepanel_profile";
    /** What a request asks for: the profile ("1"), or the profile with its memory ("memory", the time here). */
    public static final String TIME = "1";
    public static final String MEMORY = "memory";
    /** How deep a stack is kept: a recursion beyond it is cut, its outermost frames kept. */
    static final int MAX_DEPTH = 256;
    /** Distinct stacks one trace keeps; the rest are counted as one "(other)" frame, so a profile stays small. */
    static final int MAX_STACKS = 2000;
    /** Ticks without a profiled trace before the sampler thread ends (it starts again with the next one). */
    private static final int IDLE_TICKS = 400;
    // This SDK's own frames say nothing about the app.
    private static final String OWN = "dev.tracepanel.";
    private static final Frame OTHER = new Frame("(other)", "(other)");

    private final Stacks stacks;
    private final long intervalMs;
    private final Set<Recording> running = ConcurrentHashMap.newKeySet();
    private Thread thread;

    Profiler(Stacks stacks, int intervalMs) {
        this.stacks = stacks;
        this.intervalMs = Math.max(intervalMs, 1);
    }

    /** What the X-Tracepanel-Profile header asks for, else the panel's cookie: "1", "memory", or null for nothing. */
    public static String asked(String header, String cookies) {
        String asked = asking(header);
        return asked != null ? asked : byCookie(cookies);
    }

    /**
     * Whether a request asks to be profiled: the X-Tracepanel-Profile header ("1", or "memory", which profiles its
     * time here: this SDK has no memory profile), or the cookie a browser sends.
     */
    public static boolean requested(String header, String cookies) {
        return asked(header, cookies) != null;
    }

    /** "1" or "memory" as they are; anything else asks for nothing. */
    static String asking(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.equals(TIME) || stripped.equals(MEMORY) ? stripped : null;
    }

    /** What the panel's cookie asks for, when the request carries it: the Profile button sets it. */
    static String byCookie(String cookies) {
        if (cookies == null || !cookies.contains(COOKIE)) return null;
        for (String part : cookies.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0 && part.substring(0, equals).strip().equals(COOKIE)) return asking(part.substring(equals + 1));
        }
        return null;
    }

    /** A function in the stacks: its class and method. */
    private record Frame(String className, String method) {}

    /** One trace's samples, as the sampler counts them in its thread. */
    static final class Recording {
        // The thread the trace is bound to now, or null while it runs on none.
        private final AtomicReference<Thread> thread = new AtomicReference<>();
        private final Map<Frame, Integer> index = new HashMap<>();
        private final List<Frame> frames = new ArrayList<>();
        private final Map<Frame, String> files = new HashMap<>();
        private final Map<Frame, Integer> lines = new HashMap<>();
        private final Map<List<Integer>, Integer> stacks = new LinkedHashMap<>();
        private int samples;

        void enter(Thread on) {
            thread.set(on);
        }

        void leave(Thread on) {
            thread.compareAndSet(on, null);
        }
    }

    /** Begin recording a trace's stacks, on the thread it is bound to now. */
    synchronized Recording start(Thread on) {
        Recording recording = new Recording();
        recording.enter(on);
        running.add(recording);
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this::run, "tracepanel-profiler");
            thread.setDaemon(true);
            thread.start();
        }
        return recording;
    }

    /** End a trace's recording: its profile, or null when no sample saw it. */
    Map<String, Object> stop(Recording recording) {
        running.remove(recording);
        synchronized (recording) {
            recording.thread.set(null);
            return document(recording);
        }
    }

    private void run() {
        int idle = 0;
        long intervalNs = TimeUnit.MILLISECONDS.toNanos(intervalMs);
        long next = System.nanoTime();
        while (true) {
            // At a fixed rate, so a sample stands for interval_us: reading the stacks takes time of its own. Behind by
            // more than a tick (a pause), it goes on from now.
            next += intervalNs;
            long wait = next - System.nanoTime();
            if (wait < -intervalNs) next = System.nanoTime();
            try {
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            if (running.isEmpty()) {
                synchronized (this) {
                    // Decided under the lock that start() takes: a trace starting now finds no thread and starts one,
                    // rather than counting on this one, which is leaving.
                    if (running.isEmpty() && ++idle >= IDLE_TICKS) {
                        thread = null;
                        return;
                    }
                }
                continue;
            }
            idle = 0;
            for (Recording recording : running) sample(recording);
        }
    }

    private void sample(Recording recording) {
        Thread on = recording.thread.get();
        if (on == null) return;
        StackTraceElement[] stack;
        try {
            stack = on.getStackTrace();
        } catch (RuntimeException e) {
            return;
        }
        // The thread left the trace while its stack was read: that stack may be another's.
        if (stack.length == 0 || recording.thread.get() != on) return;
        synchronized (recording) {
            if (!running.contains(recording)) return;
            count(recording, stack);
        }
    }

    /** Count one sample of a stack (innermost call first, as the JVM gives it); past MAX_STACKS, it is "(other)". */
    void count(Recording recording, StackTraceElement[] stack) {
        List<StackTraceElement> kept = new ArrayList<>(Math.min(stack.length, MAX_DEPTH));
        for (int i = stack.length - 1; i >= 0 && kept.size() < MAX_DEPTH; i--) {
            if (!stack[i].getClassName().startsWith(OWN)) kept.add(stack[i]);
        }
        if (kept.isEmpty()) return;
        recording.samples++;
        List<Integer> keys = new ArrayList<>(kept.size());
        for (StackTraceElement element : kept) keys.add(frame(recording, element));
        if (recording.stacks.containsKey(keys) || recording.stacks.size() < MAX_STACKS) {
            recording.stacks.merge(keys, 1, Integer::sum);
        } else {
            recording.stacks.merge(List.of(frame(recording, OTHER, null, 0)), 1, Integer::sum);
        }
    }

    private Integer frame(Recording recording, StackTraceElement element) {
        Frame frame = new Frame(element.getClassName(), element.getMethodName());
        return frame(recording, frame, element.getFileName(), element.getLineNumber());
    }

    /**
     * A function's index in the trace's frames. Its line is the first of it the samples saw: the JVM's stack has the
     * line running, not the one the method starts at.
     */
    private static Integer frame(Recording recording, Frame frame, String fileName, int line) {
        Integer found = recording.index.get(frame);
        if (found == null) {
            found = recording.frames.size();
            recording.index.put(frame, found);
            recording.frames.add(frame);
            if (fileName != null) recording.files.put(frame, fileName);
        }
        if (line > 0) recording.lines.merge(frame, line, Math::min);
        return found;
    }

    /** The trace's `profile`: the functions the stacks name, each once, and each stack as indexes into them. */
    Map<String, Object> document(Recording recording) {
        if (recording.samples == 0) return null;
        List<Map.Entry<List<Integer>, Integer>> seen = new ArrayList<>(recording.stacks.entrySet());
        // The most common first, as the Python SDK lists them; a tie in the order they were first seen.
        seen.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<Integer, Integer> renumbered = new HashMap<>();
        List<Map<String, Object>> frames = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<List<Integer>, Integer> entry : seen) {
            List<Integer> indexes = new ArrayList<>(entry.getKey().size());
            for (Integer key : entry.getKey()) {
                indexes.add(renumbered.computeIfAbsent(key, k -> {
                    Frame frame = recording.frames.get(k);
                    frames.add(describe(frame, recording.files.get(frame), recording.lines.getOrDefault(frame, 0)));
                    return frames.size() - 1;
                }));
            }
            Map<String, Object> stack = new LinkedHashMap<>();
            stack.put("frames", indexes);
            stack.put("count", entry.getValue());
            out.add(stack);
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("interval_us", intervalMs * 1000);
        profile.put("samples", recording.samples);
        profile.put("frames", frames);
        profile.put("stacks", out);
        return profile;
    }

    /** A function as the schema has it: Class.method, its class as the module, its file and whether it is the app's. */
    private Map<String, Object> describe(Frame frame, String fileName, int line) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (frame == OTHER) {
            out.put("function", "(other)");
            out.put("module", null);
            out.put("file", "");
            out.put("line", 0);
            out.put("in_app", false);
            return out;
        }
        String className = frame.className();
        out.put("function", className.substring(className.lastIndexOf('.') + 1) + "." + frame.method());
        out.put("module", className);
        out.put("file", stacks.path(className, fileName));
        out.put("line", line);
        out.put("in_app", stacks.inApp(className));
        return out;
    }
}
