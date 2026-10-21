package dev.tracepanel.core;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tasks in flight, for the console's Live (the Tasks page's Now). A task's trace reaches the agent when the task
 * ends; until then these events say where it is, as the other SDKs send them: `enqueued` when it is queued in a trace
 * (with the trace and span that queued it), `started` when it runs, `finished` when it ends. Each app instance is a
 * worker, `service@host`: while it runs tasks, it sends a `heartbeat` with them every 30 seconds, so a task whose
 * process was killed leaves the list. `system` says which kind of task it is; the agent keeps them in memory only.
 */
public final class LiveTasks {
    /** What the app says in its hello once it sends task events. */
    public static final String CAPABILITY = "live_tasks";
    // The agent drops a running task after 2 minutes without news of it.
    static final long HEARTBEAT_MS = 30_000;
    // The kind of task the app was found to run first, if any: the heartbeat's system before a task runs.
    private static volatile String expected;

    private final Sender sender;
    private final String service;
    // The tasks this process runs now, by id.
    private final Map<String, Started> running = new ConcurrentHashMap<>();
    private final AtomicBoolean beating = new AtomicBoolean();
    // The system the worker's heartbeat names: its latest task's.
    private volatile String system;
    private volatile String worker;

    /** A task this process runs: what its `started` said, said again when its name is known. */
    private record Started(String system, String name, String queue, long retries, long time) {}

    LiveTasks(Sender sender, String service) {
        this.sender = sender;
        this.service = service;
    }

    /** The process's, or null when Tracepanel is off. */
    public static LiveTasks get() {
        Tracepanel tracepanel = Tracepanel.get();
        return tracepanel == null ? null : tracepanel.tasks();
    }

    /** This app instance as a worker: `service@host`. */
    public String worker() {
        String name = worker;
        if (name == null) {
            name = service + "@" + host();
            worker = name;
        }
        return name;
    }

    /** A task queued in a trace, by its span there (task.enqueue, or a message's publish). */
    public void enqueued(String system, String id, String name, String queue, String traceId, String spanId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name);
        fields.put("queue", queue);
        fields.put("trace_id", traceId);
        fields.put("span_id", spanId);
        send("enqueued", system, id, Trace.nowMicros(), fields);
    }

    /** A task this process runs from now: `retries`, how many times it ran before. */
    public void started(String system, String id, String name, String queue, long retries) {
        Started started = new Started(system, name, queue, retries, Trace.nowMicros());
        running.put(id, started);
        this.system = system;
        start();
        send(id, started);
    }

    /** The running task's name, once known (the listener that handles a message): said again, from the same start. */
    public void named(String id, String name) {
        Started started = running.computeIfPresent(id,
                (key, was) -> new Started(was.system(), name, was.queue(), was.retries(), was.time()));
        if (started != null) send(id, started);
    }

    /** The task ended: `state` is SUCCESS or FAILURE. A task queued that ran inside its caller ends so too. */
    public void finished(String system, String id, String state) {
        running.remove(id);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("worker", worker());
        fields.put("state", state);
        send("finished", system, id, Trace.nowMicros(), fields);
    }

    /**
     * The app runs tasks of `system` (found at startup, before any runs): its hello says it sends task events, and
     * `start` starts its heartbeat. Before Tracepanel starts too.
     */
    public static void expect(String system) {
        Sender.sends(CAPABILITY);
        if (expected == null) expected = system;
    }

    /**
     * The worker's heartbeat, from now on, if the app runs tasks: one now, so the console lists the worker before it
     * runs anything, then every 30 seconds. Once per process.
     */
    public void start() {
        if (system == null) system = expected;
        if (system == null || !beating.compareAndSet(false, true)) return;
        beat();
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    return;
                }
                beat();
            }
        }, "tracepanel-heartbeat");
        thread.setDaemon(true);
        thread.start();
    }

    void beat() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("worker", worker());
        fields.put("ids", new ArrayList<>(running.keySet()));
        send("heartbeat", system, "", Trace.nowMicros(), fields);
    }

    private void send(String id, Started started) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", started.name());
        fields.put("queue", started.queue());
        fields.put("worker", worker());
        fields.put("retries", started.retries());
        send("started", started.system(), id, started.time(), fields);
    }

    /** An event to the agent: what this step knows, null fields left out. */
    private void send(String event, String system, String id, long time, Map<String, Object> fields) {
        Sender.sends(CAPABILITY);
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("event", event);
        sent.put("system", system);
        sent.put("id", id);
        sent.put("time", time);
        fields.forEach((key, value) -> {
            if (value != null) sent.put(key, value);
        });
        sender.submitEvent(sent);
    }

    /** The machine's name: HOSTNAME when the environment has it (a container's), else the network's. */
    private static String host() {
        String named = System.getenv("HOSTNAME");
        if (named != null && !named.isBlank()) return named;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
