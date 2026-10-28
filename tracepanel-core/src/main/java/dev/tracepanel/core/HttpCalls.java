package dev.tracepanel.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls the app makes to HTTP APIs: each an http.client span of the current trace (GET api.example.com/users/1, with
 * the port when it isn't the scheme's),
 * with its URL, status and the app's line that made it. A framework's client integration starts a call where the
 * app makes it, puts its traceparent on the request (and X-Tracepanel-Profile when its trace was asked to be profiled:
 * profile()), and ends it with the answer.
 */
public final class HttpCalls {
    private static final int ERROR_STATUS = 500;

    // A call already being recorded on this thread: the client under the client (RestClient on the JDK's) isn't another.
    private static final ThreadLocal<Boolean> IN_CALL = new ThreadLocal<>();

    private HttpCalls() {}

    /** The JDK's HttpClient, with each call the app makes through it recorded. */
    public static HttpClient wrap(HttpClient client) {
        return client instanceof TracedHttpClient ? client : new TracedHttpClient(client);
    }

    /** What a recorded call does to send itself: a client under it doesn't record it again. */
    public interface Sending<T, E extends Exception> {
        T send() throws E, InterruptedException;
    }

    public static <T, E extends Exception> T nested(Sending<T, E> sending) throws E, InterruptedException {
        IN_CALL.set(Boolean.TRUE);
        try {
            return sending.send();
        } finally {
            IN_CALL.remove();
        }
    }

    /** A call the app starts now, in the current trace; null outside one, or inside a call being recorded. */
    public static Call start(String method, URI url) {
        Trace trace = Tracepanel.current();
        if (trace == null || IN_CALL.get() != null) return null;
        return start(trace, method, url);
    }

    /** A call made in `trace`, whichever thread this is: a test's request of the app, seen by the app's side. */
    public static Call start(Trace trace, String method, URI url) {
        Tracepanel tracepanel = Tracepanel.get();
        String origin = tracepanel == null ? null : tracepanel.stacks().origin();
        return new Call(trace, method.toUpperCase(java.util.Locale.ROOT), url, origin);
    }

    /** One call: its span's id and start, until it ends. */
    public static final class Call {
        private final Trace trace;
        private final String method;
        private final URI url;
        private final String origin;
        private final String spanId = Ulid.next();
        private final long start = Trace.nowMicros();

        private Call(Trace trace, String method, URI url, String origin) {
            this.trace = trace;
            this.method = method;
            this.url = url;
            this.origin = origin;
        }

        /** The traceparent the request carries, so the app it reaches links its trace to this call. */
        public String traceparent() {
            return W3c.outgoing(trace, spanId);
        }

        /**
         * What the request asks the app it reaches to profile its trace for ("1", "memory"): what this call's trace
         * was asked for by its own request, else null. Sent as the X-Tracepanel-Profile header next to the
         * traceparent, so a profile asked for in the browser follows the whole chain.
         */
        public String profile() {
            return trace.asked;
        }

        /** It answered with `status`. */
        public void end(int status) {
            record(status, null);
        }

        /** It failed before an answer: `error` is what it threw. */
        public void failed(Throwable error) {
            record(null, error.getClass().getSimpleName());
        }

        private void record(Integer status, String error) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            String scheme = url.getScheme() == null ? "http" : url.getScheme();
            attrs.put("http.request.method", method);
            attrs.put("url.full", Secrets.url(url));
            attrs.put("url.scheme", scheme);
            attrs.put("server.address", url.getHost());
            int defaultPort = scheme.equals("https") ? 443 : 80;
            int port = url.getPort() > 0 ? url.getPort() : defaultPort;
            attrs.put("server.port", port);
            attrs.put("w3c.span_id", W3c.spanId(spanId));
            if (status != null) attrs.put("http.response.status_code", status);
            if (error != null) attrs.put("error.type", error);
            attrs.put("code.origin", origin);
            boolean failed = error != null || (status != null && status >= ERROR_STATUS);
            String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
            // The port when it isn't the scheme's: two services on one host are two names (GET 127.0.0.1:8001/rates).
            String host = port == defaultPort ? url.getHost() : url.getHost() + ":" + port;
            trace.done("http.client", method + " " + host + path, attrs, start, failed, spanId);
        }
    }
}
