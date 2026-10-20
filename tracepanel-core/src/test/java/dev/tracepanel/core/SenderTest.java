package dev.tracepanel.core;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SenderTest {
    /** An agent on a port of its own: answers the hello, keeps the frames it gets, goes when closed. */
    static final class OneAgent implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final List<String> frames = new CopyOnWriteArrayList<>();
        final List<Socket> connections = new CopyOnWriteArrayList<>();

        OneAgent() throws IOException {
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
                for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) {
                    // The hello line.
                }
                socket.getOutputStream().write("{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8));
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
                socket.write(ByteBuffer.wrap("{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8)));
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

        @Override
        public void close() throws IOException {
            server.close();
            for (SocketChannel socket : connections) socket.close();
            Files.deleteIfExists(path);
        }
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
}
