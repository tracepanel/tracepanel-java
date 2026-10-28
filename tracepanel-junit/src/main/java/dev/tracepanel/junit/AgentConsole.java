package dev.tracepanel.junit;

import dev.tracepanel.core.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The agent's console API, where a test's traces are read back with what the agent found in them. */
final class AgentConsole {
    // How long to wait for the agent to store a test's traces, and how often to ask.
    private static final long STORED_WAIT_MS = 5000;
    private static final long POLL_MS = 10;

    final String url;

    AgentConsole(String url) {
        this.url = url.endsWith("/") ? url : url + "/";
    }

    static String traceUrl(String console, String traceId) {
        return console.replaceAll("/+$", "") + "/#/t/" + traceId;
    }

    /** Whether it answers: what the tests read the findings from. */
    static boolean answers(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            HttpURLConnection connection = open(url.replaceAll("/+$", "") + "/api/traces?limit=1", 2000);
            int status = connection.getResponseCode();
            connection.disconnect();
            return status == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static HttpURLConnection open(String address, int timeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        return connection;
    }

    /** The answer's JSON, or null for a 404. */
    private Object get(String path) {
        String address = url + "api/" + path;
        try {
            HttpURLConnection connection = open(address, (int) STORED_WAIT_MS);
            int status = connection.getResponseCode();
            if (status == 404) return null;
            if (status != 200) throw new IllegalStateException("tracepanel: the agent's console answered " + status + " for " + address);
            try (InputStream in = connection.getInputStream()) {
                return Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("tracepanel: the agent's console didn't answer for " + address, e);
        }
    }

    /** The traces' summaries once the agent has stored them all (it writes in a thread of its own), by id. */
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> stored(List<String> ids) {
        long deadline = System.nanoTime() + STORED_WAIT_MS * 1_000_000;
        String query = "traces?ids=" + URLEncoder.encode(String.join(",", ids), StandardCharsets.UTF_8) + "&limit=" + ids.size();
        while (true) {
            Map<String, Map<String, Object>> found = new HashMap<>();
            if (get(query) instanceof Map<?, ?> answer && answer.get("traces") instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> summary) found.put(String.valueOf(summary.get("id")), (Map<String, Object>) summary);
                }
            }
            if (found.size() >= ids.size() || System.nanoTime() >= deadline) return found;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return found;
            }
        }
    }

    /** What the agent found in these traces (their documents, as sent), the oldest trace's first. */
    @SuppressWarnings("unchecked")
    List<Finding> findings(List<Map<String, Object>> traces) {
        if (traces.isEmpty()) return List.of();
        Map<String, Map<String, Object>> spans = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> trace : traces) {
            ids.add(String.valueOf(trace.get("id")));
            for (Object span : (List<Object>) trace.getOrDefault("spans", List.of())) {
                Map<String, Object> each = (Map<String, Object>) span;
                spans.put(String.valueOf(each.get("id")), each);
            }
        }
        Map<String, Map<String, Object>> stored = stored(ids);
        List<Map<String, Object>> oldestFirst = new ArrayList<>(traces);
        oldestFirst.sort(Comparator.comparingLong(trace -> ((Number) trace.getOrDefault("start", 0L)).longValue()));
        List<Finding> found = new ArrayList<>();
        for (Map<String, Object> trace : oldestFirst) {
            String id = String.valueOf(trace.get("id"));
            Map<String, Object> summary = stored.get(id);
            if (summary == null || !(summary.get("issues") instanceof Number count) || count.longValue() == 0) continue;
            Object document = get("traces/" + id);
            Object issues = document instanceof Map<?, ?> map ? map.get("issues") : null;
            if (!(issues instanceof List<?> list)) continue;
            for (Object each : list) {
                Map<String, Object> issue = each instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
                Map<String, Object> first = null;
                if (issue.get("span_ids") instanceof List<?> spanIds) {
                    for (Object spanId : spanIds) {
                        first = spans.get(String.valueOf(spanId));
                        if (first != null) break;
                    }
                }
                found.add(new Finding(
                        String.valueOf(issue.get("kind")),
                        String.valueOf(issue.get("title")),
                        text(issue.get("culprit")),
                        text(issue.get("hint")),
                        issue.get("count") instanceof Number number ? number.intValue() : 1,
                        first != null && "sql".equals(first.get("kind")) ? Query.statement(first) : null,
                        id,
                        String.valueOf(trace.get("name")),
                        traceUrl(url, id)));
            }
        }
        return found;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
