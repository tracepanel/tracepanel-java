package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SenderTest {
    /**
     * An agent on a port of its own: answers the hello, keeps the frames it gets, goes when closed. It asks the app
     * down the last connection (ask), and sends `first` right behind its answer to the hello, in the same write.
     */
    static final class OneAgent implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final List<String> frames = new CopyOnWriteArrayList<>();
        final List<String> hellos = new CopyOnWriteArrayList<>();
        final List<Socket> connections = new CopyOnWriteArrayList<>();
        final List<Socket> answered = new CopyOnWriteArrayList<>();
        private final String first;

        OneAgent() throws IOException {
            this(null);
        }

        OneAgent(String first) throws IOException {
            this.first = first;
            Thread accept = new Thread(() -> {
                try {
                    while (true) {
                        Socket socket = server.accept();
                        connections.add(socket);
                        new Thread(() -> serve(socket)).start();
                    }
                } catch (IOException e) {
                    // Closed.
                }
            });
            accept.setDaemon(true);
            accept.start();
        }

        private void serve(Socket socket) {
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                StringBuilder hello = new StringBuilder();
                for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) hello.append((char) c);
                hellos.add(hello.toString());
                synchronized (socket) {
                    socket.getOutputStream().write(first == null ? ok() : concat(ok(), frame(first)));
                }
                answered.add(socket);
                while (true) {
                    byte[] frame = new byte[in.readInt()];
                    in.readFully(frame);
                    frames.add(new String(frame, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // The SDK closed it, or this agent went.
            }
        }

        String address() {
            return "tcp:127.0.0.1:" + server.getLocalPort();
        }

        /** `request` down the last connection the agent answered the hello of, once there is one. */
        void ask(String request) throws IOException, InterruptedException {
            for (int i = 0; i < 250 && answered.isEmpty(); i++) TimeUnit.MILLISECONDS.sleep(20);
            Socket socket = answered.get(answered.size() - 1);
            synchronized (socket) {
                socket.getOutputStream().write(frame(request));
            }
        }

        /** The frame that answers the request with `id`, once the app sent it. */
        Object answer(long id) throws InterruptedException {
            return SenderTest.answer(frames, id);
        }

        boolean got(String id) throws InterruptedException {
            for (int i = 0; i < 250; i++) {
                if (frames.stream().anyMatch(frame -> frame.contains(id))) return true;
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return false;
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : connections) socket.close();
        }
    }

    /** The same agent on a unix socket, where the SDK reads and writes a channel. */
    static final class UnixAgent implements AutoCloseable {
        final Path path;
        final ServerSocketChannel server;
        final List<String> frames = new CopyOnWriteArrayList<>();
        final List<SocketChannel> connections = new CopyOnWriteArrayList<>();
        final List<SocketChannel> answered = new CopyOnWriteArrayList<>();

        UnixAgent(Path path) throws IOException {
            this.path = path;
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(path));
            Thread accept = new Thread(() -> {
                try {
                    while (true) {
                        SocketChannel socket = server.accept();
                        connections.add(socket);
                        new Thread(() -> serve(socket)).start();
                    }
                } catch (IOException e) {
                    // Closed.
                }
            });
            accept.setDaemon(true);
            accept.start();
        }

        private void serve(SocketChannel socket) {
            try {
                DataInputStream in = new DataInputStream(Channels.newInputStream(socket));
                for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) {
                    // The hello line.
                }
                synchronized (socket) {
                    socket.write(ByteBuffer.wrap(ok()));
                }
                answered.add(socket);
                while (true) {
                    byte[] frame = new byte[in.readInt()];
                    in.readFully(frame);
                    frames.add(new String(frame, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // The SDK closed it, or this agent went.
            }
        }

        boolean got(String id) throws InterruptedException {
            for (int i = 0; i < 250; i++) {
                if (frames.stream().anyMatch(frame -> frame.contains(id))) return true;
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return false;
        }

        void ask(String request) throws IOException, InterruptedException {
            for (int i = 0; i < 250 && answered.isEmpty(); i++) TimeUnit.MILLISECONDS.sleep(20);
            SocketChannel socket = answered.get(answered.size() - 1);
            synchronized (socket) {
                socket.write(ByteBuffer.wrap(frame(request)));
            }
        }

        Object answer(long id) throws InterruptedException {
            return SenderTest.answer(frames, id);
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (SocketChannel socket : connections) socket.close();
            Files.deleteIfExists(path);
        }
    }

    private static byte[] ok() {
        return "{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8);
    }

    /** A frame as the agent sends one: a 4-byte big-endian length, then the JSON. */
    static byte[] frame(String json) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + data.length).putInt(data.length).put(data).array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
    }

    /** Of `frames`, the one that answers the request with `id`, read; null when none came. */
    static Object answer(List<String> frames, long id) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            for (String frame : frames) {
                if (frame.startsWith("{") && Json.read(frame) instanceof Map<?, ?> answer && Long.valueOf(id).equals(answer.get("answer"))) {
                    return answer;
                }
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return null;
    }

    @Test
    void on_a_unix_socket_too(@TempDir Path root) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        Map<String, String> env = Map.of("TRACEPANEL_ROOT", root.toString(), "TRACEPANEL_DIR", store.toString());
        Sender sender;
        try (UnixAgent first = new UnixAgent(root.resolve("a.sock"))) {
            Files.writeString(store.resolve("agent.addr"), "unix:" + first.path + "\n");
            sender = new Sender(Config.load(env, "shop"), new Agent(store, env));
            sender.submit(Map.of("id", "before"));
            assertTrue(first.got("before"));
            // Sending goes on while the SDK watches the connection: neither waits on the other.
            sender.submit(Map.of("id", "while"));
            assertTrue(first.got("while"));
        }
        try (UnixAgent second = new UnixAgent(root.resolve("b.sock"))) {
            Files.writeString(store.resolve("agent.addr"), "unix:" + second.path + "\n");
            TimeUnit.MILLISECONDS.sleep(100);
            sender.submit(Map.of("id", "after"));

            assertTrue(second.got("after"), "the first trace after the restart reached the new agent");
        }
    }

    @Test
    void the_first_trace_after_the_agent_restarted_reaches_the_new_one(@TempDir Path root) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        Map<String, String> env = Map.of("TRACEPANEL_ROOT", root.toString(), "TRACEPANEL_DIR", store.toString());
        Sender sender;
        try (OneAgent first = new OneAgent()) {
            Files.writeString(store.resolve("agent.addr"), first.address() + "\n");
            sender = new Sender(Config.load(env, "shop"), new Agent(store, env));
            sender.submit(Map.of("id", "before"));
            assertTrue(first.got("before"));
        }
        // The agent went (a restart): the sender's connection to it is still open on its side.
        try (OneAgent second = new OneAgent()) {
            Files.writeString(store.resolve("agent.addr"), second.address() + "\n");
            TimeUnit.MILLISECONDS.sleep(100);
            sender.submit(Map.of("id", "after"));

            assertTrue(second.got("after"), "the first trace after the restart reached the new agent");
        }
    }

    @Test
    void the_hello_sends_the_limits_set_per_database(@TempDir Path root) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        Map<String, String> env = Map.of(
                "TRACEPANEL_ROOT", root.toString(),
                "TRACEPANEL_DIR", store.toString(),
                "TRACEPANEL_SLOW_SQL_MS", "100,snowflake=3000",
                "TRACEPANEL_LONG_TRANSACTION_MS", "snowflake=10000");
        try (OneAgent agent = new OneAgent()) {
            Files.writeString(store.resolve("agent.addr"), agent.address() + "\n");
            new Sender(Config.load(env, "shop"), new Agent(store, env)).submit(Map.of("id", "first"));
            assertTrue(agent.got("first"));

            assertTrue(agent.hellos.get(0).contains("\"slow_sql_ms\":100,"), agent.hellos.get(0));
            assertTrue(agent.hellos.get(0).contains(
                    "\"slow_sql_ms_by_db\":{\"snowflake\":3000},\"long_transaction_ms_by_db\":{\"snowflake\":10000}"),
                    agent.hellos.get(0));
        }
    }

    @Test
    void the_hello_says_every_trace_is_profiled_when_the_settings_say_so() {
        Set<String> sends = Set.of("live_tasks");

        assertEquals(List.of("profile", "profile_memory", "request", "live_tasks"),
                Sender.capabilities(Config.load(Map.of(), "shop"), sends, false));
        assertEquals(List.of("profile", "profile_memory", "profile_all", "request", "live_tasks"),
                Sender.capabilities(Config.load(Map.of("TRACEPANEL_PROFILE", "1"), "shop"), sends, false));
        assertEquals(List.of("profile", "profile_memory", "profile_all"),
                Sender.capabilities(Config.load(Map.of("TRACEPANEL_PROFILE", "1", "TRACEPANEL_CAPTURE_REQUEST", "0"), "shop"), Set.of(), false));

        // Every profiled trace's memory measured, by the settings.
        assertEquals(List.of("profile", "profile_memory", "profile_all", "profile_memory_all"),
                Sender.capabilities(Config.load(Map.of("TRACEPANEL_PROFILE", "1", "TRACEPANEL_PROFILE_MEMORY", "1",
                        "TRACEPANEL_CAPTURE_REQUEST", "0"), "shop"), Set.of(), false));
        assertEquals(List.of("profile", "profile_memory", "profile_memory_all"),
                Sender.capabilities(Config.load(Map.of("TRACEPANEL_PROFILE_MEMORY", "1", "TRACEPANEL_CAPTURE_REQUEST", "0"), "shop"), Set.of(), false));

        // Profiling off: nothing profiles, whatever TRACEPANEL_PROFILE says.
        assertEquals(List.of("request"),
                Sender.capabilities(Config.load(Map.of("TRACEPANEL_PROFILE", "1", "TRACEPANEL_PROFILE_MEMORY", "1",
                        "TRACEPANEL_PROFILE_ENABLED", "0"), "shop"), Set.of(), false));
    }

    @Test
    void the_hello_says_the_app_explains_down_its_connection_once_it_has_a_database() {
        assertEquals(List.of("explain", "profile", "profile_memory", "request"), Sender.capabilities(Config.load(Map.of(), "shop"), Set.of(), true));
    }

    @Test
    void the_hello_on_the_wire_carries_profile_all(@TempDir Path root) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        Map<String, String> env = Map.of(
                "TRACEPANEL_ROOT", root.toString(),
                "TRACEPANEL_DIR", store.toString(),
                "TRACEPANEL_PROFILE", "1",
                "TRACEPANEL_CAPTURE_REQUEST", "0");
        try (OneAgent agent = new OneAgent()) {
            Files.writeString(store.resolve("agent.addr"), agent.address() + "\n");
            new Sender(Config.load(env, "shop"), new Agent(store, env)).submit(Map.of("id", "profiled"));

            assertTrue(agent.got("profiled"));
            // After "explain", when an earlier test gave the app a database.
            assertTrue(agent.hellos.get(0).matches(".*\"capabilities\":\\[(\"explain\",)?\"profile\",\"profile_memory\",\"profile_all\".*"), agent.hellos.get(0));
        }
    }
}
