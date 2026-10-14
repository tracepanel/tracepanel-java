package dev.tracepanel.core;

import java.util.List;
import java.util.Map;

/**
 * Tracepanel in a JVM: the trace the current thread works for, started and finished by a framework's integration,
 * and sent to the project's agent. One per process.
 */
public final class Tracepanel {
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    private static volatile Tracepanel instance;

    private final Config config;
    private final Stacks stacks;
    private final Agent agent;
    private final Sender sender;
    private volatile String language = "java";
    private volatile String languageVersion;

    private Tracepanel(Config config, List<String> appPackages) {
        this.config = config;
        this.stacks = new Stacks(config.root(), appPackages);
        this.agent = new Agent(config.storeDir(), System.getenv());
        this.sender = new Sender(config, agent);
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

    /** The trace this thread works for, or null. */
    public static Trace current() {
        return CURRENT.get();
    }

    /** A trace for the work this thread starts now; it is the current one until it finishes. */
    public Trace begin(String kind, String name, Map<String, Object> attrs) {
        Trace trace = new Trace(kind, name, attrs);
        trace.attr("service.name", config.service());
        trace.attr("telemetry.sdk.language", language);
        if (languageVersion != null) trace.attr("tracepanel.language.version", languageVersion);
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
