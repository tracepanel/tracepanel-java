package dev.tracepanel.junit;

import dev.tracepanel.core.Config;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the extension knows for the whole run, in this JVM: the tests running now, by the W3C trace id of their trace
 * (the requests they make carry it), what fails a test, and the agent's console (null without one). It is the core's
 * Tests hooks: each trace that begins or ends is told here.
 */
final class Session implements Tracepanel.Tests {
    static final Session INSTANCE = new Session();
    private static final Set<String> OFF = Set.of("0", "false", "no", "off");

    private final Map<String, TestTrace> running = new ConcurrentHashMap<>();
    private volatile boolean prepared;
    private volatile boolean agent;

    private Session() {}

    /** Whether TRACEPANEL_ENABLED turns Tracepanel off: then the tests run as they would without the extension. */
    static boolean off() {
        String explicit = System.getenv("TRACEPANEL_ENABLED");
        if (explicit == null || explicit.isBlank()) explicit = System.getProperty("tracepanel.enabled");
        return explicit != null && OFF.contains(explicit.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * On before the app starts: Tracepanel on as in development (a Spring app's TracepanelEnabled, Ktor's plugin read
     * tracepanel.enabled), each trace told here, the pages without the widget.
     */
    static synchronized void install() {
        if (off() || Tracepanel.tests() == INSTANCE) return;
        String explicit = System.getenv("TRACEPANEL_ENABLED");
        if ((explicit == null || explicit.isBlank()) && System.getProperty("tracepanel.enabled") == null) {
            System.setProperty("tracepanel.enabled", "true");
        }
        Tracepanel.tests(INSTANCE);
    }

    /**
     * The run's settings: what fails a test (`tracepanel.failOn`), and, once, the agent, started unless one answers.
     * A misspelled kind fails each test with the setting's error.
     */
    synchronized Set<String> prepare(String failOnSetting) {
        Set<String> kinds = failOnSetting == null
                ? Set.copyOf(Kinds.DEFAULT_FAIL_ON)
                : Kinds.parse(failOnSetting, "tracepanel.failOn");
        if (prepared) return kinds;
        prepared = true;
        String console = agentConsole(tracepanel());
        agent = console != null;
        String fails = kinds.isEmpty() ? "nothing" : String.join(", ", new TreeSet<>(kinds));
        System.out.println("tracepanel: each test a trace · fails on " + fails + " · "
                + (console != null ? "console " + console : "no agent: findings not checked"));
        if (console == null) System.err.println("tracepanel: " + why() + " The tests run without the agent's findings.");
        return kinds;
    }

    /** The process's Tracepanel: the app's (Spring Boot started it), or one of the tests' own. */
    static Tracepanel tracepanel() {
        Tracepanel started = Tracepanel.get();
        return started != null ? started : Tracepanel.start(Config.load(null), List.of());
    }

    /** The console of the project's agent, the agent started now unless one answers: null when there is none. */
    private static String agentConsole(Tracepanel tracepanel) {
        if ("off".equals(System.getenv("TRACEPANEL_AGENT"))) return null;
        String url = tracepanel.agent().console();
        if (AgentConsole.answers(url)) return url;
        // None answers at the address the project's agent left (it exited, or was killed): a new one starts.
        if (!tracepanel.agent().ensure(true)) return null;
        for (int tries = 0; tries < 40; tries++) {
            url = tracepanel.agent().console();
            if (AgentConsole.answers(url)) return url;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String why() {
        String named = System.getenv("TRACEPANEL_AGENT");
        if ("off".equals(named)) return "the agent is off (TRACEPANEL_AGENT=off).";
        if (named != null && !named.isBlank() && !named.equals("auto")) return "no agent's console answers for " + named + ".";
        return "the agent didn't start.";
    }

    /** The agent's console as it is now (an agent started anew listens on a new port), or null without an agent. */
    AgentConsole console() {
        if (!agent) return null;
        String url = tracepanel().agent().console();
        return url == null ? null : new AgentConsole(url);
    }

    void started(TestTrace test) {
        running.put(test.w3cTraceId(), test);
    }

    void stopped(TestTrace test) {
        running.remove(test.w3cTraceId(), test);
    }

    /** The test whose trace is this thread's, else the only one running (a Ktor client's coroutine): or null. */
    TestTrace current() {
        Trace current = Tracepanel.current();
        for (Trace trace = current; trace != null; trace = trace.outer()) {
            for (TestTrace test : running.values()) if (test.trace() == trace) return test;
        }
        if (running.size() != 1) return null;
        return running.values().stream().findFirst().orElse(null);
    }

    private TestTrace of(Trace trace) {
        Object id = trace.attr("w3c.trace_id");
        return id == null ? null : running.get(String.valueOf(id));
    }

    @Override
    public void begun(Trace trace) {
        TestTrace test = of(trace);
        if (test != null) test.begun(trace);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean finished(Trace trace, Map<String, Object> document) {
        Object attrs = document.get("attrs");
        Object id = attrs instanceof Map<?, ?> map ? ((Map<String, Object>) map).get("w3c.trace_id") : null;
        TestTrace test = id == null ? null : running.get(String.valueOf(id));
        if (test != null) test.finished(trace, document);
        // What ends while no test runs (a request a @BeforeAll made, the app's startup) is not a test's: not sent.
        return agent && ("test".equals(trace.kind()) || !running.isEmpty());
    }

    @Override
    public Trace caller() {
        TestTrace test = current();
        return test == null ? null : test.trace();
    }
}
