package dev.tracepanel.core;

import java.util.Map;

/** A request's trace for the tests of other packages, made without a Tracepanel and its agent. */
public final class Traces {
    private Traces() {}

    public static Trace request(String name) {
        return new Trace("http", name, Map.of());
    }
}
