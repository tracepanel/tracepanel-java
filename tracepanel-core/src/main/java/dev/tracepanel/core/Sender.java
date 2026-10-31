package dev.tracepanel.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends the finished traces to the agent from a thread of its own, in batches: a hello line, then frames of a
 * 4-byte big-endian length and a JSON array of traces, or an object of task events, {"tasks": [...]} (an agent from
 * before ignores it). A full queue drops a trace rather than slow a request down. The agent's requests come down the
 * same connection (AgentRequests: EXPLAIN on the app's database), their answers go up it between the batches.
 */
public final class Sender {
    private static final int PROTOCOL = 1;
    private static final int QUEUE_SIZE = 1000;
    private static final int BATCH_SIZE = 50;
    private static final long RETRY_AFTER_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 500;

    // What the app sends besides traces (live_tasks: task events), as its integrations say once they know.
    private static final Set<String> SENDS = new CopyOnWriteArraySet<>();

    private final Config config;
    private final Agent agent;
    // Traces and task events (Event) in one queue: they reach the agent in the order they happened.
    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private Connection connection;
    // What the connection's hello said the app sends: once it sends more, it connects again to say so.
    private Set<String> said = Set.of();
    // Whether the connection's hello said the app can EXPLAIN: once it has a database, it connects again to say so.
    private boolean saidExplain;
    private long retryAt;

    /** A connection to the agent; `gone` once the agent closed it (its reader saw the end). */
    private record Connection(AutoCloseable channel, DataOutputStream out, AtomicBoolean gone) {}

    /** A task event, sent in a frame of task events. */
    private record Event(Map<String, Object> fields) {}

    public Sender(Config config, Agent agent) {
        this.config = config;
        this.agent = agent;
        Thread thread = new Thread(this::run, "tracepanel-sender");
        thread.setDaemon(true);
        thread.start();
    }

    public void submit(Map<String, Object> trace) {
        queue.offer(trace);
    }

    /** A task event (LiveTasks): dropped, as a trace is, when the queue is full. */
    public void submitEvent(Map<String, Object> event) {
        queue.offer(new Event(event));
    }

    /** Says in the hello that the app sends `name` (live_tasks) besides traces: an earlier connection says it anew. */
    static void sends(String name) {
        SENDS.add(name);
    }

    private void run() {
        List<Object> batch = new ArrayList<>(BATCH_SIZE);
        while (true) {
            try {
                Object first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                send(batch);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Never let one bad trace stop the thread.
                Agent.warn("a trace wasn't sent: " + e);
            } finally {
                batch.clear();
            }
        }
    }

    /**
     * Connect now if not yet, so the agent has heard this app's hello (what it offers the console) before the console
     * asks; whether it is connected.
     */
    public synchronized boolean connected() {
        return connection() != null;
    }

    /** The batch as frames, in its order: each run of traces a frame of traces, each run of events one of events. */
    private synchronized void send(List<Object> batch) {
        Connection open = connection();
        if (open == null) return;
        // An answer to the agent goes up between frames, never inside one.
        synchronized (open.out()) {
            sendFrames(open, batch);
        }
    }

    private void sendFrames(Connection open, List<Object> batch) {
        try {
            List<Object> run = new ArrayList<>();
            boolean events = false;
            for (Object item : batch) {
                boolean event = item instanceof Event;
                if (event != events && !run.isEmpty()) {
                    frame(open, run, events);
                    run.clear();
                }
                events = event;
                run.add(item instanceof Event found ? found.fields() : item);
            }
            frame(open, run, events);
            open.out().flush();
        } catch (IOException e) {
            close();
        }
    }

    private static void frame(Connection open, List<Object> items, boolean events) throws IOException {
        byte[] data = Json.write(events ? Map.of("tasks", items) : items).getBytes(StandardCharsets.UTF_8);
        open.out().writeInt(data.length);
        open.out().write(data);
    }

    private Connection connection() {
        if (connection != null && connection.gone().get()) {
            // The agent went (a restart, a rebuild): a write would still go into this socket and be lost, unseen.
            close();
            retryAt = 0;
        }
        if (connection != null && !said.containsAll(SENDS)) {
            // The app sends what its hello didn't say (its first task event): the agent hears a new hello.
            close();
            retryAt = 0;
        }
        if (connection != null && !saidExplain && Jdbc.hasDatabases()) {
            // The app has a database now, which it can EXPLAIN on: the agent hears a new hello that says so.
            close();
            retryAt = 0;
        }
        if (connection != null) return connection;
        if (System.currentTimeMillis() < retryAt) return null;
        connection = handshake(agent.address());
        if (connection == null && agent.ensure(true)) connection = handshake(agent.address());
        if (connection == null) retryAt = System.currentTimeMillis() + RETRY_AFTER_MS;
        return connection;
    }

    /**
     * Reads the agent's requests (AgentRequests), answering each up `out`, and marks the connection gone once the
     * agent closes it.
     */
    private static void watch(AutoCloseable channel, InputStream in, DataOutputStream out, AtomicBoolean gone) {
        AgentRequests requests = new AgentRequests(answer -> answer(out, answer));
        Thread reader = new Thread(() -> {
            try {
                byte[] bytes = new byte[8192];
                // A unix socket's channel is read as a channel: its stream would hold the lock its writes need.
                if (channel instanceof SocketChannel socket) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    for (int read; (read = socket.read(buffer.clear())) != -1; ) requests.read(bytes, read);
                } else {
                    for (int read; (read = in.read(bytes)) != -1; ) requests.read(bytes, read);
                }
            } catch (IOException e) {
                // Closed on this side, reset by the agent, or a frame no agent sends: gone either way.
            }
            gone.set(true);
        }, "tracepanel-agent-watch");
        reader.setDaemon(true);
        reader.start();
    }

    /** An answer to the agent's request: a frame up the connection, between the batches of traces. */
    private static void answer(DataOutputStream out, Map<String, Object> answer) throws IOException {
        byte[] data = Json.write(answer).getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.writeInt(data.length);
            out.write(data);
            out.flush();
        }
    }

    /**
     * What the app's traces hold beyond the usual, said in the hello: a profile when one is asked for, and whether the
     * settings profile every trace (profile_all: the console's Profile button shows on), the request's details; and
     * what it sends besides them (live_tasks: task events); and that it answers EXPLAIN down the connection when it
     * has a database (`databases`). profile_memory: a request may ask for its memory too, and profile_memory_all: the
     * settings measure every profiled trace's (TRACEPANEL_PROFILE_MEMORY).
     */
    static List<String> capabilities(Config config, Set<String> sends, boolean databases) {
        List<String> capabilities = new ArrayList<>();
        if (databases) capabilities.add("explain");
        if (config.profileEnabled()) capabilities.add("profile");
        if (config.profileEnabled()) capabilities.add("profile_memory");
        if (config.profileEnabled() && config.profile()) capabilities.add("profile_all");
        if (config.profileEnabled() && config.profileMemory()) capabilities.add("profile_memory_all");
        if (config.captureRequest()) capabilities.add("request");
        capabilities.addAll(new TreeSet<>(sends));
        return capabilities;
    }

    /** A connection the agent accepted after the hello line, or null. */
    private Connection handshake(String address) {
        try {
            AutoCloseable channel;
            OutputStream out;
            InputStream in;
            if (address.startsWith("unix:")) {
                SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
                socket.connect(UnixDomainSocketAddress.of(Path.of(address.substring("unix:".length()))));
                channel = socket;
                out = Channels.newOutputStream(socket);
                in = Channels.newInputStream(socket);
            } else if (address.startsWith("tcp:")) {
                String hostPort = address.substring("tcp:".length());
                int at = hostPort.lastIndexOf(':');
                Socket socket = new Socket();
                socket.connect(
                        new InetSocketAddress(hostPort.substring(0, at).replaceAll("^\\[|]$", ""), Integer.parseInt(hostPort.substring(at + 1))),
                        CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                channel = socket;
                out = socket.getOutputStream();
                in = socket.getInputStream();
            } else {
                return null;
            }
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("protocol", PROTOCOL);
            hello.put("max_traces", config.maxTraces());
            // The agent finds the issues with this project's limits.
            hello.put("thresholds", config.thresholds());
            // What the app offers the console, the one place it says so: the agent alone answers api/capabilities.
            // EXPLAIN runs on the app's database: asked down this connection (AgentRequests), so the console of any app
            // in a chain can explain this one's queries, and at the app's own console (Console), for an agent from
            // before.
            hello.put("service", config.service());
            Set<String> sends = Set.copyOf(SENDS);
            boolean databases = Jdbc.hasDatabases();
            List<String> capabilities = capabilities(config, sends, databases);
            if (!capabilities.isEmpty()) hello.put("capabilities", capabilities);
            if (databases) hello.put("console", List.of("explain"));
            out.write((Json.write(hello) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String reply = line(in);
            if (channel instanceof Socket socket) socket.setSoTimeout(0);
            if (reply == null || !reply.replace(" ", "").contains("\"ok\":true")) {
                Agent.warn("the agent refused: " + reply);
                channel.close();
                return null;
            }
            AtomicBoolean gone = new AtomicBoolean();
            DataOutputStream data = new DataOutputStream(out);
            watch(channel, in, data, gone);
            said = sends;
            saidExplain = databases;
            return new Connection(channel, data, gone);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The agent's reply to the hello, read a byte at a time: what follows it is the agent's first request, which a
     * buffered reader would take.
     */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c == -1) return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
            line.write(c);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private void close() {
        if (connection != null) {
            try {
                connection.channel().close();
            } catch (Exception ignored) {
                // Gone already.
            }
        }
        connection = null;
    }
}
