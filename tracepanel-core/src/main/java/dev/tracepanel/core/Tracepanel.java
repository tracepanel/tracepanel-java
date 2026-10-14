package dev.tracepanel.core;

import java.util.List;
import java.util.Map;

/**
 * Tracepanel in a JVM: the trace the current thread works for, started and finished by a framework's integration,
 * and sent to the project's agent. One per process.
 */
public final class Tracepanel {
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    // Which part of the project made a trace, unless its integration says: a task's is a worker, as in every SDK.
    private static final Map<String, String> ROLES = Map.of("http", "web", "task", "worker");
    private static volatile Tracepanel instance;

    private final Config config;
    private final Stacks stacks;
    private final Agent agent;
    private final Sender sender;
    private volatile String language = "java";
    private volatile String languageVersion;
    private volatile String framework;

    private Tracepanel(Config config, List<String> appPackages) {
        this.config = config;
        this.stacks = new Stacks(config.root(), appPackages);
        this.agent = new Agent(config.storeDir(), System.getenv());
        this.sender = new Sender(config, agent);
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

    public Config config() {
        return config;
    }

    public Stacks stacks() {
        return stacks;
    }

    public Agent agent() {
        return agent;
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
        trace.attr("service.name", config.service());
        trace.attr("service.role", attrs.getOrDefault("service.role", ROLES.getOrDefault(kind, kind)));
        trace.attr("telemetry.sdk.language", language);
        if (languageVersion != null) trace.attr("tracepanel.language.version", languageVersion);
        if (framework != null) trace.attr("tracepanel.framework", framework);
        trace.attr("process.runtime.name", System.getProperty("java.vm.name", "java"));
        trace.attr("process.runtime.version", System.getProperty("java.version", ""));
        CURRENT.set(trace);
        return trace;
    }

    /** The trace ended: it goes to the agent, and the thread works for none. */
    public void finish(Trace trace) {
        if (CURRENT.get() == trace) CURRENT.remove();
        sender.submit(trace.finish());
    }
}
