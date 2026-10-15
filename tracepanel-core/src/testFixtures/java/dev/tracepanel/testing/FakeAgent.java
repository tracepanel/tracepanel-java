package dev.tracepanel.testing;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands for the project's agent: it takes the SDK's hello and frames of traces on its protocol port, and its console
 * answers with what it was asked. Its addresses go where the SDK reads them (TRACEPANEL_DIR, set by the build).
 */
public final class FakeAgent {
    public static final List<Map<String, Object>> TRACES = new CopyOnWriteArrayList<>();
    public static final List<String> CONSOLE_CALLS = new CopyOnWriteArrayList<>();
    private static boolean started;

    private FakeAgent() {}

    public static synchronized void start() throws IOException {
        if (started) return;
        started = true;
        Path store = Path.of(System.getenv("TRACEPANEL_DIR"));
        Files.createDirectories(store);
        ServerSocket protocol = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread accept = new Thread(() -> {
            while (true) {
                try {
                    Socket socket = protocol.accept();
                    new Thread(() -> serve(socket)).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        accept.setDaemon(true);
        accept.start();
        HttpServer console = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        console.createContext("/", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            CONSOLE_CALLS.add(call.trim());
            // A stored trace, as the agent's API gives it (the app's EXPLAIN reads its statement there).
            String path = exchange.getRequestURI().getPath();
            Map<String, Object> stored = path.startsWith("/api/traces/") ? TRACES.stream()
                    .filter(trace -> path.substring("/api/traces/".length()).equals(trace.get("id"))).findFirst().orElse(null) : null;
            byte[] body = (stored != null ? dev.tracepanel.core.Json.write(stored) : "console " + exchange.getRequestURI())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stored != null ? "application/json" : "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        console.start();
        Files.writeString(store.resolve("agent.addr"), "tcp:127.0.0.1:" + protocol.getLocalPort() + "\n");
        Files.writeString(store.resolve("console.addr"), "http://127.0.0.1:" + console.getAddress().getPort() + "/\n");
    }

    @SuppressWarnings("unchecked")
    private static void serve(Socket socket) {
        try (socket) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            String hello = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)) {
                @Override
                public String readLine() throws IOException {
                    StringBuilder line = new StringBuilder();
                    for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) line.append((char) c);
                    return line.toString();
                }
            }.readLine();
            if (!hello.contains("\"protocol\":1")) return;
            socket.getOutputStream().write("{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8));
            while (true) {
                byte[] frame = new byte[in.readInt()];
                in.readFully(frame);
                TRACES.addAll(JsonPath.parse(new String(frame, StandardCharsets.UTF_8)).read("$", List.class));
            }
        } catch (IOException e) {
            // The SDK closed it.
        }
    }

    /** The trace of the request to `path`, once the SDK sent it. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> traceAt(String path) throws InterruptedException {
        for (int waited = 0; waited < 5000; waited += 20) {
            for (Map<String, Object> trace : TRACES) {
                if (path.equals(((Map<String, Object>) trace.get("attrs")).get("http.path"))) return trace;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no trace of " + path);
    }

    /** The trace named so, once the SDK sent it. */
    public static Map<String, Object> trace(String name) throws InterruptedException {
        for (int waited = 0; waited < 5000; waited += 20) {
            for (Map<String, Object> trace : TRACES) if (name.equals(trace.get("name"))) return trace;
            Thread.sleep(20);
        }
        throw new AssertionError("no trace named " + name + "; sent: " + TRACES.stream().map(t -> t.get("name")).toList());
    }
}
