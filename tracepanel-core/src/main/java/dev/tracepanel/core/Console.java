package dev.tracepanel.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The console on the app's own origin, under the prefix: each request sent on to the console the project's agent
 * serves (its UI, widget.min.js and its API), the agent started when none answers. It answers this machine only: the
 * traces hold SQL parameters, errors and logs, and EXPLAIN runs queries. TRACEPANEL_CONSOLE_REMOTE=1 lets other
 * machines read it (an app in a container, a shared dev server). Writes (a page's traces from the browser, clearing
 * the store) come from this machine only, and up to the agent's own limit.
 */
public final class Console {
    public static final int MAX_BODY = 8 << 20;
    private static final int TIMEOUT_MS = 10_000;
    // The client's address as the server read it off the connection; Ktor's test engine says "localhost".
    private static final Set<String> LOOPBACK =
            Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "localhost");

    /** The agent's answer. */
    public record Answer(int status, String contentType, Map<String, String> headers, byte[] body) {
        static Answer text(int status, String message) {
            return new Answer(status, "text/plain; charset=utf-8", Map.of(), message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private Console() {}

    public static boolean local(String address) {
        return address != null && (LOOPBACK.contains(address) || address.startsWith("127."));
    }

    /** Whether the console answers a client at `from`: this machine, or any once TRACEPANEL_CONSOLE_REMOTE opens it. */
    static boolean answers(String from, boolean remote) {
        return remote || local(from);
    }

    /**
     * `path` below the prefix with its query ("api/traces?limit=5"); `from` the client's address. A write from
     * elsewhere is refused; so is one over MAX_BODY (`body` null: too large to read).
     */
    public static Answer forward(Tracepanel tracepanel, String method, String path, String contentType, byte[] body, String from) {
        if (!answers(from, tracepanel.config().consoleRemote())) {
            return Answer.text(403, "The Tracepanel console answers this machine only. TRACEPANEL_CONSOLE_REMOTE=1 opens it"
                    + " to others, such as when the app runs in a container.");
        }
        // EXPLAIN runs on the app's database: the app answers it, and what it can do, not the agent.
        if (method.equals("GET") && (path.equals("api/explain") || path.startsWith("api/explain?"))) {
            return Explain.answer(tracepanel, path.substring(path.indexOf('?') + 1));
        }
        if (method.equals("GET") && (path.equals("api/capabilities") || path.startsWith("api/capabilities?"))) {
            return Explain.capabilities();
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            if (!local(from)) return Answer.text(403, "The Tracepanel console takes writes from this machine only.");
            if (body == null) return Answer.text(413, "Too large.");
        }
        Agent agent = tracepanel.agent();
        String base = agent.console();
        if (base == null && agent.ensure(false)) base = agent.console();
        if (base == null) return Answer.text(503, "No Tracepanel agent: set TRACEPANEL_AGENT_BIN, or put tracepanel-agent on the PATH.");
        try {
            return fetch(base + path, method, contentType, body);
        } catch (IOException e) {
            // The agent at the address left there exited: a new one, and the request once more.
            if (agent.ensure(true) && (base = agent.console()) != null) {
                try {
                    return fetch(base + path, method, contentType, body);
                } catch (IOException again) {
                    return Answer.text(502, "Tracepanel's agent doesn't answer.");
                }
            }
            return Answer.text(502, "Tracepanel's agent doesn't answer.");
        }
    }

    private static Answer fetch(String url, String method, String contentType, byte[] body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        if (body != null && body.length > 0) {
            connection.setDoOutput(true);
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] answer = stream == null ? new byte[0] : stream.readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : new String[] {"Cache-Control", "ETag", "Last-Modified"}) {
            String value = connection.getHeaderField(name);
            if (value != null) headers.put(name, value);
        }
        return new Answer(status, connection.getContentType(), headers, answer);
    }
}
