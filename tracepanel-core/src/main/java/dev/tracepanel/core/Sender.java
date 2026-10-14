package dev.tracepanel.core;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Sends the finished traces to the agent from a thread of its own, in batches: a hello line, then frames of a
 * 4-byte big-endian length and a JSON array of traces. A full queue drops a trace rather than slow a request down.
 */
public final class Sender {
    private static final int PROTOCOL = 1;
    private static final int QUEUE_SIZE = 1000;
    private static final int BATCH_SIZE = 50;
    private static final long RETRY_AFTER_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 500;

    private final Config config;
    private final Agent agent;
    private final BlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private Connection connection;
    private long retryAt;

    private record Connection(AutoCloseable channel, DataOutputStream out) {}

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

    private void run() {
        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);
        while (true) {
            try {
                Map<String, Object> first = queue.poll(1, TimeUnit.SECONDS);
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

    private void send(List<Map<String, Object>> batch) {
        Connection open = connection();
        if (open == null) return;
        byte[] data = Json.write(batch).getBytes(StandardCharsets.UTF_8);
        try {
            open.out().writeInt(data.length);
            open.out().write(data);
            open.out().flush();
        } catch (IOException e) {
            close();
        }
    }

    private Connection connection() {
        if (connection != null) return connection;
        if (System.currentTimeMillis() < retryAt) return null;
        connection = handshake(agent.address());
        if (connection == null && agent.ensure(true)) connection = handshake(agent.address());
        if (connection == null) retryAt = System.currentTimeMillis() + RETRY_AFTER_MS;
        return connection;
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
            out.write((Json.write(hello) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String reply = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
            if (channel instanceof Socket socket) socket.setSoTimeout(0);
            if (reply == null || !reply.replace(" ", "").contains("\"ok\":true")) {
                Agent.warn("the agent refused: " + reply);
                channel.close();
                return null;
            }
            return new Connection(channel, new DataOutputStream(out));
        } catch (Exception e) {
            return null;
        }
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
