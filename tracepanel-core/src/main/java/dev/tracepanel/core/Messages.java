package dev.tracepanel.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Messages the app sends to a broker and receives from it (Kafka, RabbitMQ). Each one sent is a span of the trace
 * that sent it (publish orders), its message carrying a traceparent; each one received is a task trace of its own,
 * which the agent links to the publish by it. A framework's integration says where: this is what every broker shares.
 */
public final class Messages {
    // The publish under way on this thread: the broker client's hook, deeper in the same call, adds its traceparent.
    private static final ThreadLocal<Publish> PUBLISHING = new ThreadLocal<>();

    private Messages() {}

    /** A message the app starts sending now, in the current trace; null outside one. */
    public static Publish publish(String system) {
        Trace trace = Tracepanel.current();
        if (trace == null || PUBLISHING.get() != null) return null;
        Tracepanel tracepanel = Tracepanel.get();
        Publish publish = new Publish(trace, system, tracepanel == null ? null : tracepanel.stacks().origin());
        PUBLISHING.set(publish);
        return publish;
    }

    /** The publish under way on this thread, for the hook that writes its message's headers; null if none. */
    public static Publish publishing() {
        return PUBLISHING.get();
    }

    /** One message sent: its span's id and start, its destination once the client knows it. */
    public static final class Publish {
        private final Trace trace;
        private final String system;
        private final String origin;
        private final String spanId = Ulid.next();
        private final String parentId;
        private final long start = Trace.nowMicros();
        private String destination = "";
        private final Map<String, Object> extra = new LinkedHashMap<>();

        private Publish(Trace trace, String system, String origin) {
            this.trace = trace;
            this.system = system;
            this.origin = origin;
            this.parentId = trace.currentSpan();
        }

        /** The topic, or the exchange (the queue's name for the default one). */
        public void destination(String name) {
            if (name != null && !name.isEmpty()) destination = name;
        }

        public void attr(String key, Object value) {
            extra.put(key, value);
        }

        /** The traceparent its message carries. */
        public String traceparent() {
            return W3c.outgoing(trace, spanId);
        }

        /** The client took the message: the thread sends no other now. The span ends with `sent` or `failed`. */
        public void handedOver() {
            if (PUBLISHING.get() == this) PUBLISHING.remove();
        }

        public void sent() {
            record(null);
        }

        public void failed(Throwable error) {
            record(error);
        }

        private void record(Throwable error) {
            handedOver();
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("messaging.system", system);
            attrs.put("messaging.operation", "publish");
            attrs.put("messaging.destination.name", destination);
            attrs.putAll(extra);
            attrs.put("w3c.span_id", W3c.spanId(spanId));
            if (error != null) attrs.put("error.type", error.getClass().getSimpleName());
            attrs.put("code.origin", origin);
            trace.done(system, "publish " + destination, attrs, start, error != null, spanId, parentId);
        }
    }

    /**
     * A message received: a task trace of its own, `name` the destination until the listener names it, linked to the
     * publish by its traceparent. Null when Tracepanel is off, or the thread already works for a trace.
     */
    public static Trace receive(String system, String destination, String traceparent, Map<String, Object> attrs) {
        Tracepanel tracepanel = Tracepanel.get();
        if (tracepanel == null || Tracepanel.current() != null) return null;
        Map<String, Object> all = new LinkedHashMap<>(attrs);
        all.put("task.system", system);
        all.put("task.name", destination);
        all.put("task.queue", destination);
        all.put("messaging.system", system);
        all.put("messaging.operation", "process");
        all.put("messaging.destination.name", destination);
        all.putAll(W3c.incoming(traceparent));
        all.put("task.trigger", all.containsKey("w3c.parent_span_id") ? "message" : "other");
        return tracepanel.begin("task", "task.run", destination, all);
    }

    /** The listener that handles the message names its trace: Class.method. */
    public static void handledBy(String name) {
        Trace trace = Tracepanel.current();
        if (trace == null || !"process".equals(trace.attr("messaging.operation"))) return;
        trace.name(name);
        trace.attr("task.name", name);
    }

    /** The message was handled: its trace goes to the agent, failed if `error` isn't null. */
    public static void done(Trace trace, Throwable error) {
        Tracepanel tracepanel = Tracepanel.get();
        if (trace == null || tracepanel == null) return;
        if (error != null) {
            trace.error(error, tracepanel.stacks(), false);
            trace.failed();
        }
        tracepanel.finish(trace);
    }
}
