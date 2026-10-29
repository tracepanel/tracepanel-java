package dev.tracepanel.core;

import java.util.Map;

/** A request's trace for the tests of other packages, made without a Tracepanel and its agent. */
public final class Traces {
    private Traces() {}

    public static Trace request(String name) {
        return new Trace("http", name, Map.of());
    }

    /** A request's trace whose request asked to be profiled for `asked` ("1", "memory"). */
    public static Trace asking(String name, String asked) {
        Trace trace = request(name);
        trace.asked = asked;
        return trace;
    }
}
