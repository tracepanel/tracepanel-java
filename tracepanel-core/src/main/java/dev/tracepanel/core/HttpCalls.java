package dev.tracepanel.core;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls the app makes to HTTP APIs: each an http.client span of the current trace (GET api.example.com/users/1),
 * with its URL, status and the app's line that made it. A framework's client integration starts a call where the
 * app makes it, puts its traceparent on the request, and ends it with the answer.
 */
public final class HttpCalls {
    private static final int ERROR_STATUS = 500;

    private HttpCalls() {}

    /** A call the app starts now, in the current trace; null outside one. */
    public static Call start(String method, URI url) {
        Trace trace = Tracepanel.current();
        if (trace == null) return null;
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
            attrs.put("url.full", url.toString());
            attrs.put("url.scheme", scheme);
            attrs.put("server.address", url.getHost());
            attrs.put("server.port", url.getPort() > 0 ? url.getPort() : scheme.equals("https") ? 443 : 80);
            attrs.put("w3c.span_id", W3c.spanId(spanId));
            if (status != null) attrs.put("http.response.status_code", status);
            if (error != null) attrs.put("error.type", error);
            attrs.put("code.origin", origin);
            boolean failed = error != null || (status != null && status >= ERROR_STATUS);
            String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
            trace.done("http.client", method + " " + url.getHost() + path, attrs, start, failed, spanId);
        }
    }
}
