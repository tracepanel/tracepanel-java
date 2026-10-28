package dev.tracepanel.core;

import java.util.List;
import java.util.Map;

/**
 * Tracepanel in a JVM: the trace the current thread works for, started and finished by a framework's integration,
 * and sent to the project's agent. One per process.
 */
public final class Tracepanel {
    private static final ThreadLocal<Trace> CURRENT = new Current();
    // Which part of the project made a trace, unless its integration says: a task's is a worker, as in every SDK.
    private static final Map<String, String> ROLES = Map.of("http", "web", "task", "worker");
    private static volatile Tracepanel instance;
    private static volatile Tests tests;

    private final Config config;
    private final Stacks stacks;
    private final Agent agent;
    private final Sender sender;
    private final Profiler profiler;
    private final LiveTasks tasks;
    private volatile String language = "java";
    private volatile String languageVersion;
    private volatile String framework;

    private Tracepanel(Config config, List<String> appPackages) {
        this.config = config;
        this.stacks = new Stacks(config.root(), appPackages);
        this.agent = new Agent(config.storeDir(), System.getenv());
        this.sender = new Sender(config, agent);
        this.profiler = new Profiler(stacks, config.profileIntervalMs());
        this.tasks = new LiveTasks(sender, config.service());
        agent.prepare();
    }

    /** The process's Tracepanel, set up on the first call: `appPackages` are the app's own (its frames in stacks). */
    public static synchronized Tracepanel start(Config config, List<String> appPackages) {
        if (instance == null) instance = new Tracepanel(config, appPackages);
        return instance;
    }

    /** The process's Tracepanel, or null before it started (or when off). */
    public static Tracepanel get() {
        return instance;
    }

    /** Connected to the agent now if it can be, so it has heard what this app offers the console. */
    boolean connected() {
        return sender.connected();
    }

    public Config config() {
        return config;
    }

    public Stacks stacks() {
        return stacks;
    }

    public Agent agent() {
        return agent;
    }

    /** The tasks in flight in this process, told to the console's Live as they go. */
    public LiveTasks tasks() {
        return tasks;
    }

    /** The app's language when it isn't Java: "kotlin", with Kotlin's own version (the JVM's is the runtime's). */
    public void language(String name, String version) {
        language = name;
        languageVersion = version;
    }

    /** The app's framework with its version (Spring Boot 4.1.1), on every trace: the app's label is its latest's. */
    public void framework(String name) {
        framework = name;
    }

    /**
     * What a test runner's integration (tracepanel-junit) is told of the traces, and answers: each test a trace, the
     * app's requests it makes linked to it. Set, the app's pages go without the widget, as the tests read them.
     */
    public interface Tests {
        /** A trace began: an app's request a test made carries the test's W3C trace id (w3c.trace_id). */
        void begun(Trace trace);

        /** A trace ended with this document: whether it goes to the agent. */
        boolean finished(Trace trace, Map<String, Object> document);

        /**
         * The trace of the test that made a request of the app in this process (MockMvc, a test client) without a
         * traceparent, or null: the request is a call of that trace.
         */
        Trace caller();
    }

    /** Hands each trace to a test runner's integration from now on; null: no tests. */
    public static void tests(Tests hooks) {
        tests = hooks;
    }

    /** The test runner's integration, or null outside tests. */
    public static Tests tests() {
        return tests;
    }

    /** The trace this thread works for, or null. */
    public static Trace current() {
        return CURRENT.get();
    }

    /**
     * Makes `trace` this thread's (null: none) and returns the one it had: work that moves between threads (an async
     * request's dispatches, a coroutine's resumes) takes its trace along, and gives the thread back as it was.
     */
    public static Trace bind(Trace trace) {
        Trace before = CURRENT.get();
        if (trace == null) CURRENT.remove();
        else CURRENT.set(trace);
        return before;
    }

    /**
     * Names the signed-in user on the current trace, as the app's authentication knows them: their id and their
     * username (null for none). The username stays on this machine: the agent drops it from what leaves it. Outside a
     * trace it does nothing.
     */
    public static void setUser(String id, String username) {
        Trace trace = CURRENT.get();
        if (trace != null) trace.user(id, username);
    }

    /** Where the current trace lives, for a coroutine context element that restores it on each resume. */
    public static ThreadLocal<Trace> holder() {
        return CURRENT;
    }

    /** A trace for the work this thread starts now; it is the current one until it finishes. */
    public Trace begin(String kind, String name, Map<String, Object> attrs) {
        return begin(kind, kind, name, attrs);
    }

    /** The same, with the root span's kind: a task's trace begins with its task.run. */
    public Trace begin(String kind, String rootKind, String name, Map<String, Object> attrs) {
        Trace trace = new Trace(kind, rootKind, name, attrs);
        // The trace this thread worked for: a test's, whose MockMvc request this is. It is current again after.
        trace.outer = CURRENT.get();
        trace.attr("service.name", config.service());
        trace.attr("service.role", attrs.getOrDefault("service.role", ROLES.getOrDefault(kind, kind)));
        trace.attr("telemetry.sdk.language", language);
        if (languageVersion != null) trace.attr("tracepanel.language.version", languageVersion);
        if (framework != null) trace.attr("tracepanel.framework", framework);
        trace.attr("process.runtime.name", System.getProperty("java.vm.name", "java"));
        trace.attr("process.runtime.version", System.getProperty("java.version", ""));
        CURRENT.set(trace);
        // TRACEPANEL_PROFILE profiles every trace; a request may still ask for its own (profile).
        if (config.profile()) profile(trace);
        Tests hooks = tests;
        if (hooks != null) hooks.begun(trace);
        return trace;
    }

    /**
     * Samples where the trace's time goes in code, from now to its end, when profiling is possible
     * (TRACEPANEL_PROFILE_ENABLED). The trace is this thread's: begin made it so.
     */
    public void profile(Trace trace) {
        if (!config.profileEnabled() || trace.recording != null) return;
        trace.recording = profiler.start(Thread.currentThread());
    }

    /**
     * Profiles a trace whose request asked for it, with an X-Tracepanel-Profile header or the panel's
     * tracepanel_profile cookie (Profiler.asked): `asked` is what it asked for, "1" or "memory", or null for nothing.
     * Its calls to other apps over HTTP ask the same (HttpCalls.Call.profile), so the whole chain is profiled; a trace
     * only the TRACEPANEL_PROFILE setting profiles asks nothing of them.
     */
    public void profile(Trace trace, String asked) {
        if (asked == null || !config.profileEnabled()) return;
        profile(trace);
        trace.asked = asked;
    }

    /** The trace ended: it goes to the agent, and the thread works for the one it worked for before, if any. */
    public void finish(Trace trace) {
        if (CURRENT.get() == trace) {
            if (trace.outer != null) CURRENT.set(trace.outer);
            else CURRENT.remove();
        }
        Profiler.Recording recording = trace.recording;
        if (recording != null) trace.profile(profiler.stop(recording));
        Map<String, Object> document = trace.finish();
        Tests hooks = tests;
        if (hooks == null || hooks.finished(trace, document)) sender.submit(document);
    }

    /**
     * The current trace's holder, which tells a profiled trace the thread it runs on now: whoever sets it (bind, a
     * coroutine's context element on each resume) moves the trace's sampling with it.
     */
    private static final class Current extends ThreadLocal<Trace> {
        @Override
        public void set(Trace trace) {
            Trace before = get();
            super.set(trace);
            moved(before, trace);
        }

        @Override
        public void remove() {
            Trace before = get();
            super.remove();
            moved(before, null);
        }

        private static void moved(Trace before, Trace after) {
            if (before == after) return;
            Thread thread = Thread.currentThread();
            if (before != null && before.recording != null) before.recording.leave(thread);
            if (after != null && after.recording != null) after.recording.enter(thread);
        }
    }
}
