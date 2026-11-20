package dev.tracepanel.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
    /** Another app's EXPLAIN, which the agent asks it for: above the 15 seconds the agent waits for that app. */
    private static final int EXPLAIN_TIMEOUT_MS = 20_000;
    // The client's address as the server read it off the connection; Ktor's test engine says "localhost".
    private static final Set<String> LOOPBACK =
            Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1", "localhost");

    /** The agent's answer. */
    public record Answer(int status, String contentType, Map<String, String> headers, byte[] body) {
        static Answer text(int status, String message) {
            return new Answer(status, "text/plain; charset=utf-8", Map.of(), message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** Why the console doesn't answer, and what to do (Agent.noConsole). */
    public record NoConsole(String title, String detail) {}

    // While the console doesn't answer: the browser asks again this often, and the page reloads itself as often.
    private static final int RETRY_S = 2;

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
        return forward(tracepanel, method, path, contentType, body, from, null);
    }

    /**
     * As above, the request's Accept header given: without a console, a browser's page (it accepts HTML) gets a page
     * that says why and reloads itself until the console answers.
     */
    public static Answer forward(
            Tracepanel tracepanel, String method, String path, String contentType, byte[] body, String from, String accept) {
        if (!answers(from, tracepanel.config().consoleRemote())) {
            return Answer.text(403, "The Tracepanel console answers this machine only. TRACEPANEL_CONSOLE_REMOTE=1 opens it"
                    + " to others, such as when the app runs in a container.");
        }
        // EXPLAIN runs on the database of the app that recorded the query: this app's traces it answers itself,
        // another service's (a linked trace of the chain) the agent asks that app for, down its connection.
        if (method.equals("GET") && (path.equals("api/explain") || path.startsWith("api/explain?"))) {
            Answer here = Explain.answer(tracepanel, path.substring(path.indexOf('?') + 1));
            return here != null ? here : agent(tracepanel, method, path, contentType, body, accept, EXPLAIN_TIMEOUT_MS);
        }
        if (method.equals("GET") && (path.equals("api/capabilities") || path.startsWith("api/capabilities?"))) {
            // The agent answers what the console can offer, from the app's hello: connected now, it has heard it.
            tracepanel.connected();
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            if (!local(from)) return Answer.text(403, "The Tracepanel console takes writes from this machine only.");
            if (body == null) return Answer.text(413, "Too large.");
        }
        return agent(tracepanel, method, path, contentType, body, accept, TIMEOUT_MS);
    }

    /**
     * The agent's answer to the request, waited for up to `timeout` milliseconds; the agent started if none answers.
     * Without a console, 503: why, and what to do.
     */
    private static Answer agent(
            Tracepanel tracepanel, String method, String path, String contentType, byte[] body, String accept, int timeout) {
        Agent agent = tracepanel.agent();
        String base = agent.console();
        if (base == null && agent.ensure(false)) base = agent.console();
        if (base == null) return unavailable(agent.noConsole(), accept);
        try {
            return fetch(base + path, method, contentType, body, tracepanel.config().service(), timeout);
        } catch (IOException e) {
            // The agent at the address left there exited: a new one, and the request once more.
            if (agent.ensure(true) && (base = agent.console()) != null) {
                try {
                    return fetch(base + path, method, contentType, body, tracepanel.config().service(), timeout);
                } catch (IOException again) {
                    return unavailable(agent.noConsole(), accept);
                }
            }
            return unavailable(agent.noConsole(), accept);
        }
    }

    /**
     * No console: 503 with why and what to do. A browser's page (it accepts HTML) gets a small page that reloads itself
     * until the console answers; the console's API calls and its files get `{"error": ...}`.
     */
    static Answer unavailable(NoConsole why, String accept) {
        Map<String, String> headers = Map.of("Retry-After", String.valueOf(RETRY_S), "Cache-Control", "no-store");
        if (accept != null && accept.contains("text/html")) {
            return new Answer(503, "text/html; charset=utf-8", headers, page(why).getBytes(StandardCharsets.UTF_8));
        }
        return new Answer(503, "application/json", headers, Json.write(Map.of("error", why.detail())).getBytes(StandardCharsets.UTF_8));
    }

    /** The page in the console's look (its dark tokens), with nothing to load: the console is what's missing. */
    static String page(NoConsole why) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="refresh" content="%1$d">
                <title>Tracepanel: %2$s</title>
                <style>
                :root { color-scheme: dark; }
                body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #0a0c11; color: #eceef3;
                  font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; }
                main { box-sizing: border-box; max-width: 38rem; margin: 2rem 1rem; padding: 1.5rem 1.75rem; border: 1px solid #2a3040;
                  border-radius: 12px; background: rgb(245 181 68 / 0.06); }
                h1 { margin: 0 0 0.75rem; font-size: 1.05rem; font-weight: 600; color: #f5b544; }
                p { margin: 0.5rem 0; overflow-wrap: anywhere; }
                .muted { color: #8c93a6; font-size: 13px; }
                </style>
                </head>
                <body>
                <main>
                <h1>%2$s</h1>
                <p>%3$s</p>
                <p class="muted">This page reloads itself every %1$d seconds and shows the console once it answers.</p>
                </main>
                </body>
                </html>
                """.formatted(RETRY_S, escape(why.title()), escape(why.detail()));
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if ("&<>\"'".indexOf(c) >= 0) out.append("&#").append((int) c).append(';');
            else out.append(c);
        }
        return out.toString();
    }

    /**
     * The agent's answer; `service` says which app's console asked, so the agent offers it what the app answers here
     * (EXPLAIN).
     */
    private static Answer fetch(String url, String method, String contentType, byte[] body, String service, int timeout)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Tracepanel-Console", service);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(timeout);
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
