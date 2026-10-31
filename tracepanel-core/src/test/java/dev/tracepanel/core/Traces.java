package dev.tracepanel.core;

import java.util.List;
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

    /** A request's trace the app's own code profiled (Tracepanel.profile), in a Tracepanel of its own. */
    public static Trace profiledByCode(String name) {
        Map<String, String> settings = Map.of("TRACEPANEL_AGENT", "off");
        Tracepanel tracepanel = new Tracepanel(Config.load(settings, "shop"), List.of(), settings);
        Trace trace = tracepanel.begin("http", name, Map.of());
        tracepanel.profiledByCode(trace);
        Tracepanel.bind(null);
        return trace;
    }
}
