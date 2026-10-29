package dev.tracepanel.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the agent asks of the app down the connection its traces go up: frames of a 4-byte big-endian length and a
 * JSON object, `{"id": 7, "explain": {"sql": ..., "params": [...], "alias": ...}}`. Each is answered up the same
 * connection, a frame too: `{"answer": 7, "columns": [...], "rows": [[...]]}`, or `{"answer": 7, "error": "..."}`.
 * Each request runs on a thread of its own, so neither the connection's reader nor the traces wait for a database.
 */
final class AgentRequests {
    /** A frame larger than this is a broken peer, not a request. */
    private static final int MAX_FRAME = 16 << 20;

    /** Where an answer goes: a frame up the connection the request came down. */
    interface Answers {
        void send(Map<String, Object> answer) throws IOException;
    }

    private final Answers answers;
    private byte[] pending = new byte[256];
    private int size;

    AgentRequests(Answers answers) {
        this.answers = answers;
    }

    /** Bytes as they came down the connection: each frame they complete is a request, run. */
    void read(byte[] bytes, int length) throws IOException {
        if (size + length > pending.length) pending = Arrays.copyOf(pending, Math.max(pending.length * 2, size + length));
        System.arraycopy(bytes, 0, pending, size, length);
        size += length;
        int at = 0;
        while (size - at >= 4) {
            int frame = ((pending[at] & 0xff) << 24) | ((pending[at + 1] & 0xff) << 16) | ((pending[at + 2] & 0xff) << 8)
                    | (pending[at + 3] & 0xff);
            if (frame < 0 || frame > MAX_FRAME) throw new IOException("a frame of " + Integer.toUnsignedString(frame) + " bytes");
            if (size - at - 4 < frame) break;
            String body = new String(pending, at + 4, frame, StandardCharsets.UTF_8);
            at += 4 + frame;
            handle(body);
        }
        System.arraycopy(pending, at, pending, 0, size - at);
        size -= at;
    }

    private void handle(String body) {
        Object request;
        try {
            request = Json.read(body);
        } catch (RuntimeException e) {
            // A frame this SDK can't read: the agent times its request out.
            return;
        }
        if (!(request instanceof Map<?, ?> asked) || !(asked.get("explain") instanceof Map<?, ?> explain)) return;
        Object id = asked.get("id");
        Thread worker = new Thread(() -> answer(id, explain), "tracepanel-explain");
        worker.setDaemon(true);
        worker.start();
    }

    /** The request run, and its answer sent: the rows, or what went wrong. */
    private void answer(Object id, Map<?, ?> explain) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("answer", id);
        try {
            answer.putAll(Explain.rows(explain));
        } catch (Exception e) {
            answer.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
        }
        try {
            answers.send(answer);
        } catch (IOException | RuntimeException e) {
            // The connection went meanwhile: the agent has stopped waiting.
        }
    }
}
